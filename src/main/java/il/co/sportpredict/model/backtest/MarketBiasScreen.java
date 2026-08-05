package il.co.sportpredict.model.backtest;

import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.MarketOdds;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.repo.MarketOddsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests whether the market itself is systematically mispriced, using no model at all.
 *
 * <p>Every documented betting-market bias is a claim that a particular kind of selection is
 * priced wrongly: longshots too short because punters overpay for big payouts, draws too long
 * because nobody backs them, popular clubs shaded because the money is one-sided. Each of
 * those is directly measurable from stored prices and known results.
 *
 * <p><b>Read against the no-bias line, not against zero.</b> Backing every selection at a
 * price carrying a 5% margin returns about -5% by construction. So -5% means "no bias found",
 * and only a figure clearly above that indicates anything. Positive ROI at these prices would
 * be remarkable.
 *
 * <p>Two caveats that matter for acting on this. The prices here are the international median,
 * which is sharper and cheaper than the one operator you can actually bet with - so a bias
 * visible here may not survive a wider margin. And an effect present in aggregate can vanish
 * per competition, which is the level you would bet at.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketBiasScreen {

    /**
     * Odds buckets, as ordered labels rather than reconstructed bounds.
     *
     * <p>Deriving the label back from the bound dropped the open-ended top bucket entirely -
     * and that is precisely where longshot bias is strongest, so the report was missing the
     * evidence it exists to look for.
     */
    private static final double[] BUCKET_BOUNDS = {1.5, 2.0, 3.0, 5.0, 10.0, Double.MAX_VALUE};
    private static final String[] BUCKET_LABELS = {
            "odds 1.0-1.5", "odds 1.5-2.0", "odds 2.0-3.0",
            "odds 3.0-5.0", "odds 5.0-10.0", "odds 10.0+"};

    private final MarketOddsRepository marketOdds;

    public record BiasReport(
            String sport,
            int matches,
            double averageOverround,
            double noBiasExpectedRoi,
            String howToRead,
            List<Row> byOutcome,
            List<Row> byOddsBucket,
            List<CompetitionRow> byCompetition
    ) {
    }

    /**
     * @param roi           return per unit staked, backing this group flat
     * @param standardError spread on that ROI - anything inside two of these is noise
     * @param impliedProbability what the market said would happen, on average
     * @param actualRate    what did happen. A gap between these two is the bias itself.
     */
    public record Row(
            String group,
            int bets,
            double roi,
            double standardError,
            double impliedProbability,
            double actualRate
    ) {
    }

    /** {@code drawRoi} is null for two-way markets, which have no draw leg. */
    public record CompetitionRow(String competition, int matches,
                                 double homeRoi, Double drawRoi, double awayRoi) {
    }

    public BiasReport run(Sport sport, int minBookmakers) {
        List<MarketOdds> settled = marketOdds.findSettled(sport, minBookmakers);
        if (settled.isEmpty()) {
            throw new IllegalStateException("no settled matches with prices for " + sport
                    + " - run /api/admin/odds-backfill first");
        }

        Accumulator home = new Accumulator();
        Accumulator draw = new Accumulator();
        Accumulator away = new Accumulator();
        Map<String, Accumulator> buckets = new LinkedHashMap<>();
        Map<String, Accumulator[]> perCompetition = new LinkedHashMap<>();
        double overroundSum = 0;
        int matches = 0;

        boolean sawThreeWay = false;
        for (MarketOdds odds : settled) {
            Fixture fixture = odds.getFixture();
            int outcome = outcomeIndex(fixture);
            double[] implied = odds.impliedProbabilities();

            // Two-way markets (basketball moneyline) have no draw leg at all. Previously
            // these were skipped outright, which silently excluded every basketball match
            // from the screen.
            double[] prices;
            int drawSide;
            if (odds.twoWay()) {
                if (outcome == 1) {
                    continue;   // a tied final score has no two-way settlement
                }
                prices = new double[]{odds.getMedianHome(), odds.getMedianAway()};
                outcome = outcome == 0 ? 0 : 1;
                drawSide = -1;
            } else {
                prices = new double[]{odds.getMedianHome(), odds.getMedianDraw(), odds.getMedianAway()};
                drawSide = 1;
                sawThreeWay = true;
            }

            String competition = fixture.getCompetition() == null
                    ? "(unknown)" : fixture.getCompetition().getName();
            Accumulator[] byCompetitionSides = perCompetition.computeIfAbsent(competition,
                    key -> new Accumulator[]{new Accumulator(), new Accumulator(), new Accumulator()});

            for (int side = 0; side < prices.length; side++) {
                boolean won = side == outcome;
                // Map a two-way away leg onto the away accumulator, not the draw one.
                int canonical = drawSide < 0 && side == 1 ? 2 : side;
                Accumulator target = canonical == 0 ? home : (canonical == 1 ? draw : away);
                target.add(prices[side], implied[side], won);
                byCompetitionSides[canonical].add(prices[side], implied[side], won);
                buckets.computeIfAbsent(bucketLabel(prices[side]), key -> new Accumulator())
                        .add(prices[side], implied[side], won);
            }

            double inverseSum = 0;
            for (double price : prices) {
                inverseSum += 1 / price;
            }
            overroundSum += inverseSum - 1;
            matches++;
        }

        if (matches == 0) {
            throw new IllegalStateException("no priced settled matches found for " + sport);
        }

        double averageOverround = overroundSum / matches;
        List<Row> outcomeRows = new ArrayList<>();
        outcomeRows.add(home.toRow("back home"));
        if (sawThreeWay) {
            outcomeRows.add(draw.toRow("back draw"));
        }
        outcomeRows.add(away.toRow("back away"));

        List<Row> bucketRows = new ArrayList<>();
        for (String label : BUCKET_LABELS) {
            Accumulator accumulator = buckets.get(label);
            if (accumulator != null && accumulator.bets > 0) {
                bucketRows.add(accumulator.toRow(label));
            }
        }

        List<CompetitionRow> competitionRows = new ArrayList<>();
        perCompetition.forEach((name, sides) -> {
            if (sides[0].bets >= 30) {
                competitionRows.add(new CompetitionRow(name, sides[0].bets,
                        round(sides[0].roi()),
                        sides[1].bets == 0 ? null : round(sides[1].roi()),
                        round(sides[2].roi())));
            }
        });
        competitionRows.sort((a, b) -> Integer.compare(b.matches(), a.matches()));

        BiasReport report = new BiasReport(sport.name(), matches, round(averageOverround),
                round(-averageOverround),
                "compare every roi against noBiasExpectedRoi, not against 0; differences "
                        + "smaller than two standardErrors are noise",
                outcomeRows, bucketRows, competitionRows);
        log.info("market bias screen {}: {} matches, overround {}, drawRoi {}",
                sport, matches, round(averageOverround), round(draw.roi()));
        return report;
    }

    /** Flat one-unit stakes on one group of selections. */
    private static final class Accumulator {
        private int bets;
        private double returns;
        private double squaredProfit;
        private double impliedSum;
        private int wins;

        void add(double price, double implied, boolean won) {
            double profit = won ? price - 1 : -1;
            bets++;
            returns += won ? price : 0;
            squaredProfit += profit * profit;
            impliedSum += implied;
            if (won) {
                wins++;
            }
        }

        double roi() {
            return bets == 0 ? 0 : (returns - bets) / bets;
        }

        Row toRow(String group) {
            double mean = roi();
            // Standard error of the mean profit per unit staked.
            double variance = bets == 0 ? 0 : squaredProfit / bets - mean * mean;
            double standardError = bets == 0 ? 0 : Math.sqrt(Math.max(0, variance) / bets);
            return new Row(group, bets, round(mean), round(standardError),
                    round(bets == 0 ? 0 : impliedSum / bets),
                    round(bets == 0 ? 0 : (double) wins / bets));
        }
    }

    private String bucketLabel(double price) {
        for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
            if (price < BUCKET_BOUNDS[i]) {
                return BUCKET_LABELS[i];
            }
        }
        return BUCKET_LABELS[BUCKET_LABELS.length - 1];
    }

    private int outcomeIndex(Fixture fixture) {
        int home = fixture.getHomeScore();
        int away = fixture.getAwayScore();
        return home > away ? 0 : (home == away ? 1 : 2);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
