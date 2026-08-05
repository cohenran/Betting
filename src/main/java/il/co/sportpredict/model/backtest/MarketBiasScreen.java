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

    /** Upper bound of each odds bucket; the last bucket catches everything above. */
    private static final double[] BUCKET_BOUNDS = {1.5, 2.0, 3.0, 5.0, 10.0, Double.MAX_VALUE};

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

    public record CompetitionRow(String competition, int matches,
                                 double homeRoi, double drawRoi, double awayRoi) {
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

        for (MarketOdds odds : settled) {
            if (odds.twoWay()) {
                continue;
            }
            Fixture fixture = odds.getFixture();
            int outcome = outcomeIndex(fixture);
            double[] implied = odds.impliedProbabilities();
            double[] prices = {odds.getMedianHome(), odds.getMedianDraw(), odds.getMedianAway()};

            for (int side = 0; side < 3; side++) {
                boolean won = side == outcome;
                Accumulator target = side == 0 ? home : (side == 1 ? draw : away);
                target.add(prices[side], implied[side], won);
                buckets.computeIfAbsent(bucketLabel(prices[side]), key -> new Accumulator())
                        .add(prices[side], implied[side], won);
            }

            String competition = fixture.getCompetition() == null
                    ? "(unknown)" : fixture.getCompetition().getName();
            Accumulator[] byOutcome = perCompetition.computeIfAbsent(competition,
                    key -> new Accumulator[]{new Accumulator(), new Accumulator(), new Accumulator()});
            for (int side = 0; side < 3; side++) {
                byOutcome[side].add(prices[side], implied[side], side == outcome);
            }

            overroundSum += 1 / prices[0] + 1 / prices[1] + 1 / prices[2] - 1;
            matches++;
        }

        if (matches == 0) {
            throw new IllegalStateException("no three-way priced matches found for " + sport);
        }

        double averageOverround = overroundSum / matches;
        List<Row> outcomeRows = List.of(
                home.toRow("back home"), draw.toRow("back draw"), away.toRow("back away"));

        List<Row> bucketRows = new ArrayList<>();
        for (double bound : BUCKET_BOUNDS) {
            String label = bucketLabel(bound - 1e-9);
            Accumulator accumulator = buckets.get(label);
            if (accumulator != null && accumulator.bets > 0) {
                bucketRows.add(accumulator.toRow(label));
            }
        }

        List<CompetitionRow> competitionRows = new ArrayList<>();
        perCompetition.forEach((name, sides) -> {
            if (sides[0].bets >= 30) {
                competitionRows.add(new CompetitionRow(name, sides[0].bets,
                        round(sides[0].roi()), round(sides[1].roi()), round(sides[2].roi())));
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
        double previous = 1.0;
        for (double bound : BUCKET_BOUNDS) {
            if (price < bound) {
                return bound == Double.MAX_VALUE
                        ? "odds %.1f+".formatted(previous)
                        : "odds %.1f-%.1f".formatted(previous, bound);
            }
            previous = bound;
        }
        return "odds ?";
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
