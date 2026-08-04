package il.co.sportpredict.winner;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.ingest.AllSportsOddsClient;
import il.co.sportpredict.ingest.AllSportsProvider;
import il.co.sportpredict.ingest.OddsSnapshot;
import il.co.sportpredict.model.PredictionView;
import il.co.sportpredict.model.PredictionService;
import il.co.sportpredict.model.backtest.BacktestService;
import il.co.sportpredict.model.football.FootballPredictor;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.FixtureSourceRepository;
import il.co.sportpredict.util.CsvHelper;
import il.co.sportpredict.util.ValueBetAdvisor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dry-run bet ledger. Records what the model would have staked, resolves it against real
 * results, and writes one CSV per staking rule so the arms can be compared.
 *
 * <p>Prices come from either the odds API (unattended, market consensus) or a scraped
 * betting form. Median prices across bookmakers are used rather than the best available:
 * shopping the top line across books is not something a bettor tied to one operator can
 * do, and using it would inflate every edge.
 *
 * <p>Three arms, and the order matters:
 * <ul>
 *   <li><b>flat</b> - fixed stake. The only arm that answers "does the edge exist", since
 *       every bet contributes equally and no sizing rule distorts the outcome.</li>
 *   <li><b>half-kelly</b> - what growth looks like if the probabilities are true.</li>
 *   <li><b>full-kelly</b> - diagnostic. Kelly is optimal only when {@code p} is correct;
 *       on an over-confident model it overbets badly (claiming 60% at odds 2.0 stakes 20%
 *       of bankroll where the truth of 52% justifies 4%), and past twice the correct
 *       fraction expected growth turns negative. Expect it to blow up - that is a fact
 *       about stake sizing, not about the model's edge.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperBetManager {

    private static final String CSV_HEADER =
            "Date,FixtureId,Match,Selection,Probability,Odds,Edge,Stake,Status,PnL,Bankroll,Source,Bookmakers";
    private static final String FLAT_CSV = "paper_bets_flat.csv";
    private static final String HALF_KELLY_CSV = "paper_bets_half_kelly.csv";
    private static final String FULL_KELLY_CSV = "paper_bets_full_kelly.csv";

    private final WinnerService winnerService;
    private final AllSportsOddsClient oddsClient;
    private final PredictionService predictions;
    private final FixtureRepository fixtureRepository;
    private final FixtureSourceRepository fixtureSources;
    private final FootballPredictor footballPredictor;
    private final BacktestService backtest;
    private final SportPredictProperties props;

    /** Outcome of the last run, so a silent failure is visible without reading logs. */
    @Getter
    private volatile String lastRunStatus = "not run yet";

    /** One priced fixture, whatever the price came from. */
    private record BetCandidate(
            Long fixtureId, Sport sport, String home, String away,
            double pHome, double pDraw, double pAway,
            double oddsHome, double oddsDraw, double oddsAway,
            String source, int bookmakers) {
    }

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

    // ---------- gating ----------

    /** Empty when betting is allowed, otherwise the reason it is not. */
    private Optional<String> blockedReason() {
        int fitSample = footballPredictor.currentParams().getSampleSize();
        int required = props.getPaperBetting().getMinFitSample();
        if (fitSample < required) {
            return Optional.of("football fit has " + fitSample + " matches, need " + required);
        }
        if (!props.getPaperBetting().isRequireBacktestEdge()) {
            return Optional.empty();
        }
        Optional<BacktestService.StoredResult> stored = backtest.lastResult();
        if (stored.isEmpty()) {
            return Optional.of("no backtest on record yet");
        }
        BacktestService.StoredResult result = stored.get();
        if (!result.beatsBaseline()) {
            return Optional.of("backtest logLoss %.4f does not beat baseline %.4f"
                    .formatted(result.logLoss(), result.baselineLogLoss()));
        }
        return Optional.empty();
    }

    // ---------- placing ----------

    private void placeBets() {
        Optional<String> blocked = blockedReason();
        if (blocked.isPresent()) {
            lastRunStatus = "SKIPPED: " + blocked.get();
            log.warn("paper betting: {}", lastRunStatus);
            return;
        }

        List<BetCandidate> candidates = props.getPaperBetting().getOddsSource()
                == SportPredictProperties.OddsSource.ALLSPORTS
                ? candidatesFromOddsApi()
                : candidatesFromWinner();

        if (candidates.isEmpty()) {
            // A broken price source and a genuine absence of value produce identical empty
            // ledgers, so this has to be loud rather than logged as a normal completion.
            lastRunStatus = "FAILED: no priced fixtures from "
                    + props.getPaperBetting().getOddsSource();
            log.error("paper betting: {} - check the odds source, not the model", lastRunStatus);
            return;
        }

        for (String file : List.of(FLAT_CSV, HALF_KELLY_CSV, FULL_KELLY_CSV)) {
            initCsv(file);
        }
        double flatBankroll = currentBankroll(FLAT_CSV);
        double halfBankroll = currentBankroll(HALF_KELLY_CSV);
        double fullBankroll = currentBankroll(FULL_KELLY_CSV);
        int placed = 0;

        for (BetCandidate c : candidates) {
            ValueBetAdvisor.BetRecommendation advice = advise(c, flatBankroll, 0.5);
            if (advice.expectedValue() < props.getPaperBetting().getMinEdge()) {
                continue;
            }
            if (!alreadyBet(FLAT_CSV, c.fixtureId())) {
                double stake = props.getPaperBetting().getFlatStake();
                flatBankroll -= stake;
                appendBet(FLAT_CSV, c, advice, stake, flatBankroll);
                placed++;
            }
            halfBankroll = placeKelly(HALF_KELLY_CSV, c, halfBankroll, 0.5);
            fullBankroll = placeKelly(FULL_KELLY_CSV, c, fullBankroll, 1.0);
        }

        lastRunStatus = "OK: %d priced fixtures, %d flat bets placed (source %s)"
                .formatted(candidates.size(), placed, props.getPaperBetting().getOddsSource());
        log.info("paper betting: {}", lastRunStatus);
    }

    private ValueBetAdvisor.BetRecommendation advise(BetCandidate c, double bankroll, double kelly) {
        return ValueBetAdvisor.analyze3Way(c.sport(), c.home(), c.away(),
                c.pHome(), c.pDraw(), c.pAway(),
                c.oddsHome(), c.oddsDraw(), c.oddsAway(), bankroll, kelly);
    }

    private double placeKelly(String file, BetCandidate c, double bankroll, double kellyMultiplier) {
        if (alreadyBet(file, c.fixtureId())) {
            return bankroll;
        }
        ValueBetAdvisor.BetRecommendation advice = advise(c, bankroll, kellyMultiplier);
        if (advice.expectedValue() < props.getPaperBetting().getMinEdge()
                || advice.recommendedStakeAmount() <= 0) {
            return bankroll;
        }
        double stake = Math.min(advice.recommendedStakeAmount(), Math.max(0, bankroll));
        double remaining = bankroll - stake;
        appendBet(file, c, advice, stake, remaining);
        return remaining;
    }

    // ---------- candidate sources ----------

    /**
     * Market prices joined onto fixtures by the provider's own match id, so no team-name
     * matching is involved and nothing can silently bind to the wrong game.
     */
    private List<BetCandidate> candidatesFromOddsApi() {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(props.getPaperBetting().getOddsWindowDays());
        Map<String, OddsSnapshot> odds = oddsClient.fetch(from, to);
        if (odds.isEmpty()) {
            log.warn("paper betting: odds API returned nothing for {}..{}", from, to);
            return List.of();
        }

        List<BetCandidate> out = new ArrayList<>();
        for (OddsSnapshot snapshot : odds.values()) {
            fixtureSources.findByProviderAndSportAndExternalId(
                            AllSportsProvider.NAME, Sport.FOOTBALL, snapshot.externalId())
                    .map(source -> source.getFixture().getId())
                    .ifPresent(fixtureId -> {
                        PredictionView view = predictions.predictFixture(fixtureId);
                        if (!"SCHEDULED".equals(view.status())
                                || view.startsAt().isBefore(Instant.now())) {
                            return;
                        }
                        if (!props.getPaperBetting().getSports().contains(Sport.valueOf(view.sport()))) {
                            return;
                        }
                        out.add(new BetCandidate(fixtureId, Sport.valueOf(view.sport()),
                                view.home(), view.away(),
                                view.prediction().pHome(), view.prediction().pDraw(),
                                view.prediction().pAway(),
                                snapshot.medianHome(), snapshot.medianDraw(), snapshot.medianAway(),
                                AllSportsProvider.NAME, snapshot.bookmakers()));
                    });
        }
        log.info("paper betting: {} of {} priced matches matched a stored fixture",
                out.size(), odds.size());
        return out;
    }

    private List<BetCandidate> candidatesFromWinner() {
        WinnerService.RoundAnalysis analysis =
                winnerService.analyzeUrl(props.getPaperBetting().getWinnerUrl());
        if (analysis == null || analysis.results() == null) {
            return List.of();
        }
        List<BetCandidate> out = new ArrayList<>();
        for (WinnerService.LineAnalysis line : analysis.results()) {
            if (line.fixtureId() == null || line.prediction() == null
                    || line.oddsHome() == null || line.oddsDraw() == null || line.oddsAway() == null) {
                continue;
            }
            Sport sport;
            try {
                sport = Sport.valueOf(line.sport());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!props.getPaperBetting().getSports().contains(sport)) {
                continue;
            }
            out.add(new BetCandidate(line.fixtureId(), sport,
                    line.matchedHome(), line.matchedAway(),
                    line.prediction().pHome(), line.prediction().pDraw(), line.prediction().pAway(),
                    line.oddsHome(), line.oddsDraw(), line.oddsAway(),
                    WinnerService.PROVIDER, 1));
        }
        return out;
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
                    Fixture fixture = fixtureRepository.findById(Long.parseLong(parts[1])).orElse(null);
                    if (fixture != null && fixture.getStatus().isFinal() && fixture.getHomeScore() != null) {
                        boolean won = didSelectionWin(parts[3], fixture);
                        parts[8] = won ? "WON" : "LOST";
                        parts[9] = String.format("%.2f", won ? stake * (odds - 1.0) : -stake);
                        resolved++;
                    }
                }

                if ("PENDING".equals(parts[8])) {
                    bankroll -= stake;                          // stake tied up
                } else {
                    bankroll += Double.parseDouble(parts[9]);    // net profit, or -stake
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

    private void appendBet(String file, BetCandidate c,
                           ValueBetAdvisor.BetRecommendation advice, double stake, double bankroll) {
        String match = (c.home() + " vs " + c.away()).replace(",", " ");
        String selection = selectionCode(advice.recommendedSelection(), c);
        String row = String.format("%s,%d,%s,%s,%.4f,%.2f,%.4f,%.2f,PENDING,,%.2f,%s,%d",
                Instant.now(), c.fixtureId(), match, selection,
                advice.winProbability(), advice.offeredOdds(), advice.expectedValue(),
                stake, bankroll, c.source(), c.bookmakers());
        CsvHelper.appendLine(path(file), row);
        log.info("paper betting: {} <- {}", file, row);
    }

    /** ValueBetAdvisor labels the pick with the team name; the ledger stores HOME/DRAW/AWAY. */
    private String selectionCode(String recommendedSelection, BetCandidate c) {
        if ("DRAW".equals(recommendedSelection)) {
            return "DRAW";
        }
        return recommendedSelection.contains("(HOME)") ? "HOME" : "AWAY";
    }
}
