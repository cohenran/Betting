package il.co.sportpredict.winner;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.util.CsvHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperBetManager {

    private final WinnerService winnerService;
    private final FixtureRepository fixtureRepository;
    private final SportPredictProperties props;

    private static final String CSV_HEADER = "Date,FixtureId,Match,Selection,Probability,Odds,Edge,Stake,Status,PnL,Bankroll";
    private static final String HALF_KELLY_CSV = "paper_bets_half_kelly.csv";
    private static final String FULL_KELLY_CSV = "paper_bets_full_kelly.csv";

    @Scheduled(cron = "${sportpredict.paper-betting.cron:0 0 8 * * *}", zone = "Asia/Jerusalem")
    public void runDailyTask() {
        if (!props.getPaperBetting().isEnabled()) {
            return;
        }
        log.info("Starting daily paper betting task...");
        
        // 1. Resolve yesterday's pending bets
        resolveBets(HALF_KELLY_CSV);
        resolveBets(FULL_KELLY_CSV);

        // 2. Place new bets for today
        placeBets();
        
        log.info("Daily paper betting task completed.");
    }

    private void resolveBets(String csvFile) {
        List<String> lines = CsvHelper.readLines(csvFile);
        if (lines.isEmpty()) return;

        List<String> updatedLines = new ArrayList<>();
        updatedLines.add(lines.get(0)); // Header

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] parts = line.split(",", -1);
            if (parts.length < 11) {
                updatedLines.add(line);
                continue;
            }

            String status = parts[8];
            if (!"PENDING".equals(status)) {
                updatedLines.add(line);
                continue;
            }

            try {
                Long fixtureId = Long.parseLong(parts[1]);
                String selection = parts[3];
                double odds = Double.parseDouble(parts[5]);
                double stake = Double.parseDouble(parts[7]);
                double bankrollBeforeBet = Double.parseDouble(parts[10]) - stake; // reconstruct the pre-bet bankroll if needed, though we just track running total

                fixtureRepository.findById(fixtureId).ifPresentOrElse(fixture -> {
                    if (fixture.getStatus().isFinal() && fixture.getHomeScore() != null) {
                        boolean won = didSelectionWin(selection, fixture);
                        String newStatus = won ? "WON" : "LOST";
                        double pnl = won ? (stake * (odds - 1.0)) : -stake;
                        
                        // We must recalculate the current running bankroll based on previous lines
                        // But for simplicity in a flat file, we can just update the PnL and Status.
                        // Bankroll column already reflects the bankroll *after* the bet was placed but *before* resolution.
                        // We will recalculate the actual bankroll dynamically when placing new bets.
                        
                        parts[8] = newStatus;
                        parts[9] = String.format("%.2f", pnl);
                        updatedLines.add(String.join(",", parts));
                        log.info("Resolved bet for Fixture {}: {} ({})", fixtureId, newStatus, pnl);
                    } else {
                        // Still pending
                        updatedLines.add(line);
                    }
                }, () -> {
                    log.warn("Fixture {} not found, keeping bet PENDING.", fixtureId);
                    updatedLines.add(line);
                });
            } catch (Exception e) {
                log.warn("Error parsing line: {}", line, e);
                updatedLines.add(line);
            }
        }
        
        CsvHelper.writeLines(csvFile, updatedLines);
    }

    private boolean didSelectionWin(String selection, Fixture fixture) {
        int home = fixture.getHomeScore();
        int away = fixture.getAwayScore();
        if (selection.equals("HOME")) {
            return home > away;
        } else if (selection.equals("AWAY")) {
            return away > home;
        } else if (selection.equals("DRAW")) {
            return home == away;
        }
        return false;
    }

    private void placeBets() {
        String url = props.getPaperBetting().getWinnerUrl();
        WinnerService.RoundAnalysis analysis = winnerService.analyzeUrl(url);

        if (analysis == null || analysis.results() == null) {
            log.warn("No results returned from Winner.co.il");
            return;
        }

        // Initialize CSVs if they don't exist
        initCsv(HALF_KELLY_CSV);
        initCsv(FULL_KELLY_CSV);

        double halfKellyBankroll = getCurrentBankroll(HALF_KELLY_CSV);
        double fullKellyBankroll = getCurrentBankroll(FULL_KELLY_CSV);

        for (WinnerService.LineAnalysis line : analysis.results()) {
            if (line.fixtureId() == null || line.recommendationEdge() == null || line.recommendationEdge() <= 0) {
                continue; // Skip lines with no matching fixture or no edge
            }

            // recommendation is HOME, DRAW, or AWAY
            String selection = line.recommendation();
            if (selection.equals("NONE")) continue;
            
            // To prevent betting on the same match twice in the same day (e.g. if the cron runs twice),
            // we should check if the fixtureId already exists in the CSV.
            if (alreadyBet(HALF_KELLY_CSV, line.fixtureId())) {
                continue;
            }

            double pHome = 0, pDraw = 0, pAway = 0;
            if (line.prediction() != null) {
                 pHome = line.prediction().pHome();
                 pDraw = line.prediction().pDraw();
                 pAway = line.prediction().pAway();
            } else {
                continue;
            }

            double oddsHome = line.oddsHome() != null ? line.oddsHome() : 0;
            double oddsDraw = line.oddsDraw() != null ? line.oddsDraw() : 0;
            double oddsAway = line.oddsAway() != null ? line.oddsAway() : 0;

            // Half Kelly Bet
            var halfAdvice = il.co.sportpredict.util.ValueBetAdvisor.analyze3Way(
                    Sport.valueOf(line.sport()), line.homeRaw(), line.awayRaw(),
                    pHome, pDraw, pAway, oddsHome, oddsDraw, oddsAway, halfKellyBankroll, 0.5
            );
            
            if (halfAdvice.recommendedStakeAmount() > 0) {
                halfKellyBankroll -= halfAdvice.recommendedStakeAmount();
                appendBet(HALF_KELLY_CSV, line, halfAdvice, halfKellyBankroll);
            }

            // Full Kelly Bet
            var fullAdvice = il.co.sportpredict.util.ValueBetAdvisor.analyze3Way(
                    Sport.valueOf(line.sport()), line.homeRaw(), line.awayRaw(),
                    pHome, pDraw, pAway, oddsHome, oddsDraw, oddsAway, fullKellyBankroll, 1.0
            );

            if (fullAdvice.recommendedStakeAmount() > 0) {
                fullKellyBankroll -= fullAdvice.recommendedStakeAmount();
                appendBet(FULL_KELLY_CSV, line, fullAdvice, fullKellyBankroll);
            }
        }
    }

    private void initCsv(String file) {
        List<String> lines = CsvHelper.readLines(file);
        if (lines.isEmpty()) {
            CsvHelper.appendLine(file, CSV_HEADER);
        }
    }

    private double getCurrentBankroll(String file) {
        List<String> lines = CsvHelper.readLines(file);
        if (lines.size() <= 1) {
            return props.getPaperBetting().getStartingBankroll();
        }
        
        // Calculate the actual bankroll by summing all PnL since start, minus pending stakes
        double current = props.getPaperBetting().getStartingBankroll();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",", -1);
            if (parts.length < 11) continue;
            
            String status = parts[8];
            double stake = Double.parseDouble(parts[7]);
            double pnl = parts[9].isEmpty() ? 0.0 : Double.parseDouble(parts[9]);
            
            if ("WON".equals(status) || "LOST".equals(status)) {
                current += pnl; // PnL is already net profit or negative stake
            } else if ("PENDING".equals(status)) {
                current -= stake; // Money is tied up in a pending bet
            }
        }
        return current;
    }

    private boolean alreadyBet(String file, Long fixtureId) {
        List<String> lines = CsvHelper.readLines(file);
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",", -1);
            if (parts.length > 1 && parts[1].equals(String.valueOf(fixtureId))) {
                return true;
            }
        }
        return false;
    }

    private void appendBet(String file, WinnerService.LineAnalysis line, il.co.sportpredict.util.ValueBetAdvisor.BetRecommendation advice, double remainingBankroll) {
        String match = line.matchedHome() + " vs " + line.matchedAway();
        // Replace commas in match name to avoid breaking CSV
        match = match.replace(",", " ");
        
        String csvRow = String.format("%s,%d,%s,%s,%.4f,%.2f,%.4f,%.2f,PENDING,,%.2f",
                Instant.now().toString(),
                line.fixtureId(),
                match,
                line.recommendation(),
                advice.winProbability(),
                advice.offeredOdds(),
                advice.expectedValue(),
                advice.recommendedStakeAmount(),
                remainingBankroll
        );
        CsvHelper.appendLine(file, csvRow);
        log.info("Placed paper bet in {}: {}", file, csvRow);
    }
}
