package il.co.sportpredict.winner;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.model.football.FootballPredictor;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.util.CsvHelper;
import il.co.sportpredict.util.ValueBetAdvisor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dry-run bet ledger. Records what the model would have staked, resolves it against real
 * results, and writes a CSV per staking rule so the arms can be compared.
 *
 * <p>Three arms, and the order matters:
 * <ul>
 *   <li><b>flat</b> - fixed stake per bet. The only arm that answers "does the edge exist",
 *       because every bet contributes equally and no sizing rule distorts the result.</li>
 *   <li><b>half-kelly</b> - what growth would look like if the probabilities were true.</li>
 *   <li><b>full-kelly</b> - diagnostic only. Kelly is optimal solely when {@code p} is
 *       correct; on an over-confident model it overbets badly (a model claiming 60% at odds
 *       2.0 stakes 20% of bankroll, when the truth of 52% justifies 4%), and past twice the
 *       correct fraction the expected growth rate turns negative. Expect it to blow up. That
 *       is a statement about stake sizing, not about the model's edge.</li>
 * </ul>
 *
 * <p>Betting is refused until the football model has actually been fitted - below that the
 * predictions are Elo cold-start and a month of betting them measures nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperBetManager {

    private static final String CSV_HEADER =
            "Date,FixtureId,Match,Selection,Probability,Odds,Edge,Stake,Status,PnL,Bankroll";
    private static final String FLAT_CSV = "paper_bets_flat.csv";
    private static final String HALF_KELLY_CSV = "paper_bets_half_kelly.csv";
    private static final String FULL_KELLY_CSV = "paper_bets_full_kelly.csv";

    private final WinnerService winnerService;
    private final FixtureRepository fixtureRepository;
    private final FootballPredictor footballPredictor;
    private final SportPredictProperties props;

    /** Outcome of the last run, so a silent failure is visible without reading logs. */
    @Getter
    private volatile String lastRunStatus = "not run yet";

    @Scheduled(cron = "${sportpredict.paper-betting.cron:0 0 8 * * *}", zone = "Asia/Jerusalem")
    public void runDailyTask() {
        if (!props.getPaperBetting().isEnabled()) {
            return;
        }
        log.info("paper betting: daily run starting");

        resolveBets(FLAT_CSV);
        resolveBets(HALF_KELLY_CSV);
        resolveBets(FULL_KELLY_CSV);

        try {
            placeBets();
        } catch (Exception e) {
            lastRunStatus = "FAILED: " + e.getMessage();
            log.error("paper betting: placing bets failed", e);
        }
        log.info("paper betting: daily run finished - {}", lastRunStatus);
    }

    // ---------- placing ----------

    private void placeBets() {
        int fitSample = footballPredictor.currentParams().getSampleSize();
        int required = props.getPaperBetting().getMinFitSample();
        if (fitSample < required) {
            lastRunStatus = "SKIPPED: football fit has " + fitSample + " matches, need " + required;
            log.warn("paper betting: {} - predictions are still Elo cold-start, refusing to bet",
                    lastRunStatus);
            return;
        }

        WinnerService.RoundAnalysis analysis = winnerService.analyzeUrl(props.getPaperBetting().getWinnerUrl());
        if (analysis == null || analysis.results() == null || analysis.results().isEmpty()) {
            // The most likely failure of this whole exercise: the scrape silently returns
            // nothing for a month and the empty ledger looks like "no value found".
            lastRunStatus = "FAILED: round parsed to 0 lines from " + props.getPaperBetting().getWinnerUrl();
            log.error("paper betting: {} - check /api/winner/debug, the parser or the URL is stale",
                    lastRunStatus);
            return;
        }

        for (String file : List.of(FLAT_CSV, HALF_KELLY_CSV, FULL_KELLY_CSV)) {
            initCsv(file);
        }

        double flatBankroll = currentBankroll(FLAT_CSV);
        double halfBankroll = currentBankroll(HALF_KELLY_CSV);
        double fullBankroll = currentBankroll(FULL_KELLY_CSV);
        int placed = 0;
        int skipped = 0;

        for (WinnerService.LineAnalysis line : analysis.results()) {
            if (!isBettable(line)) {
                skipped++;
                continue;
            }
            Sport sport = Sport.valueOf(line.sport());

            double pHome = line.prediction().pHome();
            double pDraw = line.prediction().pDraw();
            double pAway = line.prediction().pAway();
            double oddsHome = orZero(line.oddsHome());
            double oddsDraw = orZero(line.oddsDraw());
            double oddsAway = orZero(line.oddsAway());

            // Flat arm: same selection as Kelly, fixed stake. Bankroll is only bookkeeping
            // here - a flat arm never sizes down after losses, which is the point.
            ValueBetAdvisor.BetRecommendation flat = ValueBetAdvisor.analyze3Way(
                    sport, line.homeRaw(), line.awayRaw(), pHome, pDraw, pAway,
                    oddsHome, oddsDraw, oddsAway, flatBankroll, 0.5);
            if (flat.expectedValue() >= props.getPaperBetting().getMinEdge()
                    && !alreadyBet(FLAT_CSV, line.fixtureId())) {
                double stake = props.getPaperBetting().getFlatStake();
                flatBankroll -= stake;
                appendBet(FLAT_CSV, line, flat, stake, flatBankroll);
                placed++;
            }

            halfBankroll = placeKelly(HALF_KELLY_CSV, line, sport, pHome, pDraw, pAway,
                    oddsHome, oddsDraw, oddsAway, halfBankroll, 0.5);
            fullBankroll = placeKelly(FULL_KELLY_CSV, line, sport, pHome, pDraw, pAway,
                    oddsHome, oddsDraw, oddsAway, fullBankroll, 1.0);
        }

        lastRunStatus = "OK: %d lines, %d flat bets placed, %d lines skipped"
                .formatted(analysis.results().size(), placed, skipped);
        log.info("paper betting: {}", lastRunStatus);
    }

    private double placeKelly(String file, WinnerService.LineAnalysis line, Sport sport,
                              double pHome, double pDraw, double pAway,
                              double oddsHome, double oddsDraw, double oddsAway,
                              double bankroll, double kellyMultiplier) {
        if (alreadyBet(file, line.fixtureId())) {
            return bankroll;
        }
        ValueBetAdvisor.BetRecommendation advice = ValueBetAdvisor.analyze3Way(
                sport, line.homeRaw(), line.awayRaw(), pHome, pDraw, pAway,
                oddsHome, oddsDraw, oddsAway, bankroll, kellyMultiplier);
        if (advice.expectedValue() < props.getPaperBetting().getMinEdge()
                || advice.recommendedStakeAmount() <= 0) {
            return bankroll;
        }
        double stake = Math.min(advice.recommendedStakeAmount(), Math.max(0, bankroll));
        double remaining = bankroll - stake;
        appendBet(file, line, advice, stake, remaining);
        return remaining;
    }

    /** A line is bettable only when it is bound to a real fixture in a configured sport. */
    private boolean isBettable(WinnerService.LineAnalysis line) {
        if (line.fixtureId() == null || line.prediction() == null) {
            return false;
        }
        if (line.recommendation() == null || "NONE".equals(line.recommendation())) {
            return false;
        }
        Sport sport;
        try {
            sport = Sport.valueOf(line.sport());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return props.getPaperBetting().getSports().contains(sport);
    }

    // ---------- resolving ----------

    private void resolveBets(String csvFile) {
        List<String> lines = CsvHelper.readLines(path(csvFile));
        if (lines.isEmpty()) {
            return;
        }

        List<String> updated = new ArrayList<>();
        updated.add(lines.getFirst());
        // Rewrite the running bankroll on every row so the CSV can be charted directly.
        double bankroll = props.getPaperBetting().getStartingBankroll();
        int resolved = 0;

        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",", -1);
            if (parts.length < 11) {
                updated.add(lines.get(i));
                continue;
            }
            try {
                double stake = Double.parseDouble(parts[7]);
                double odds = Double.parseDouble(parts[5]);

                if ("PENDING".equals(parts[8])) {
                    Long fixtureId = Long.parseLong(parts[1]);
                    Fixture fixture = fixtureRepository.findById(fixtureId).orElse(null);
                    if (fixture != null && fixture.getStatus().isFinal() && fixture.getHomeScore() != null) {
                        boolean won = didSelectionWin(parts[3], fixture);
                        parts[8] = won ? "WON" : "LOST";
                        parts[9] = String.format("%.2f", won ? stake * (odds - 1.0) : -stake);
                        resolved++;
                    }
                }

                if ("PENDING".equals(parts[8])) {
                    bankroll -= stake;              // stake is tied up
                } else {
                    bankroll += Double.parseDouble(parts[9]);   // net profit or -stake
                }
                parts[10] = String.format("%.2f", bankroll);
                updated.add(String.join(",", parts));
            } catch (Exception e) {
                log.warn("paper betting: unparseable row in {}: {}", csvFile, lines.get(i));
                updated.add(lines.get(i));
            }
        }

        CsvHelper.writeLines(path(csvFile), updated);
        if (resolved > 0) {
            log.info("paper betting: resolved {} bets in {}, bankroll now {}",
                    resolved, csvFile, String.format("%.2f", bankroll));
        }
    }

    private boolean didSelectionWin(String selection, Fixture fixture) {
        int home = fixture.getHomeScore();
        int away = fixture.getAwayScore();
        return switch (selection) {
            case "HOME" -> home > away;
            case "AWAY" -> away > home;
            case "DRAW" -> home == away;
            default -> false;
        };
    }

    // ---------- ledger ----------

    private String path(String file) {
        return Path.of(props.getPaperBetting().getDataDir(), file).toString();
    }

    private void initCsv(String file) {
        if (CsvHelper.readLines(path(file)).isEmpty()) {
            CsvHelper.appendLine(path(file), CSV_HEADER);
        }
    }

    private double currentBankroll(String file) {
        List<String> lines = CsvHelper.readLines(path(file));
        double current = props.getPaperBetting().getStartingBankroll();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",", -1);
            if (parts.length < 11) {
                continue;
            }
            try {
                if ("PENDING".equals(parts[8])) {
                    current -= Double.parseDouble(parts[7]);
                } else if (!parts[9].isEmpty()) {
                    current += Double.parseDouble(parts[9]);
                }
            } catch (NumberFormatException e) {
                log.warn("paper betting: bad number in {} row {}", file, i);
            }
        }
        return current;
    }

    private boolean alreadyBet(String file, Long fixtureId) {
        String needle = String.valueOf(fixtureId);
        for (String line : CsvHelper.readLines(path(file))) {
            String[] parts = line.split(",", -1);
            if (parts.length > 1 && parts[1].equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private void appendBet(String file, WinnerService.LineAnalysis line,
                           ValueBetAdvisor.BetRecommendation advice, double stake, double bankroll) {
        String match = (line.matchedHome() + " vs " + line.matchedAway()).replace(",", " ");
        String row = String.format("%s,%d,%s,%s,%.4f,%.2f,%.4f,%.2f,PENDING,,%.2f",
                Instant.now(), line.fixtureId(), match, line.recommendation(),
                advice.winProbability(), advice.offeredOdds(), advice.expectedValue(),
                stake, bankroll);
        CsvHelper.appendLine(path(file), row);
        log.info("paper betting: {} <- {}", file, row);
    }

    private double orZero(Double v) {
        return v == null ? 0 : v;
    }
}
