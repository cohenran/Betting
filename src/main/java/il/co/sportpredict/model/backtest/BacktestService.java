package il.co.sportpredict.model.backtest;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.MarketOdds;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.model.ModelStateStore;
import il.co.sportpredict.model.football.DixonColesParams;
import il.co.sportpredict.model.football.DixonColesTrainer;
import il.co.sportpredict.model.football.ScoreGrid;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.MarketOddsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Walk-forward backtest of the football model: refit on everything before a cutoff,
 * predict the next window, advance, repeat. Nothing the model scores has ever been in
 * its own training set, so the log-loss it reports is the one you would have lived with.
 *
 * <p>The baseline is the training window's own outcome frequencies - beating it is the
 * minimum bar for the model being worth anything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    /** model_state key holding the most recent walk-forward outcome. */
    public static final String STATE_KEY = "football-backtest";

    private final FixtureRepository fixtures;
    private final MarketOddsRepository marketOdds;
    private final DixonColesTrainer trainer;
    private final ModelStateStore store;
    private final SportPredictProperties props;

    public record Bucket(double predictedLow, double predictedHigh, int count, double predicted, double actual) {
    }

    /**
     * Just enough of a backtest to gate on, persisted so callers do not have to re-run a
     * walk-forward evaluation (dozens of refits) to answer "is the model worth betting".
     */
    public record StoredResult(double logLoss, double baselineLogLoss, double accuracy,
                               int testMatches, String ranAt, MarketComparison market,
                               BlendResult blend) {

        /** The minimum bar: the model must be better calibrated than the base rates. */
        public boolean beatsBaseline() {
            return logLoss < baselineLogLoss;
        }

        /**
         * The bar that actually decides whether betting can be profitable. Null when no
         * prices were available for the tested matches - beating base rates says nothing
         * about beating a bookmaker.
         */
        public Boolean beatsMarket() {
            return market == null ? null : market.modelLogLoss() < market.marketLogLoss();
        }
    }

    public Optional<StoredResult> lastResult() {
        return store.load(STATE_KEY, StoredResult.class);
    }

    /** Runs a walk-forward evaluation and persists the headline numbers. */
    public StoredResult runAndStore(int historyDays, int stepDays, double trainFraction) {
        BacktestResult result = runFootball(historyDays, stepDays, trainFraction);
        StoredResult stored = new StoredResult(result.logLoss(), result.baselineLogLoss(),
                result.accuracy(), result.testMatches(), Instant.now().toString(),
                result.market(), result.blend());
        store.save(STATE_KEY, stored, result.testMatches(), "walk-forward");
        log.info("stored backtest: logLoss={} baseline={} beatsBaseline={} beatsMarket={} blend={}",
                stored.logLoss(), stored.baselineLogLoss(), stored.beatsBaseline(),
                stored.beatsMarket(), result.blend() == null ? "n/a" : result.blend().verdict());
        return stored;
    }

    public record BacktestResult(
            String sport,
            int trainMatches,
            int testMatches,
            int refits,
            double logLoss,
            double brier,
            double accuracy,
            double baselineLogLoss,
            double baselineBrier,
            double baselineAccuracy,
            List<Bucket> calibration,
            MarketComparison market,
            BlendResult blend
    ) {
    }

    /**
     * Model against the bookmakers on the subset of test matches that have stored prices.
     *
     * <p>Both numbers come from the same matches, and the model's predictions are the
     * walk-forward ones - fitted only on earlier matches - so this is a fair comparison
     * rather than a model scored on its own training data.
     *
     * <p>This is the number that decides whether betting could ever be profitable. Beating
     * league base rates is easy; beating the closing price is the actual bar.
     */
    public record MarketComparison(
            int matches,
            double modelLogLoss,
            double marketLogLoss,
            double modelAccuracy,
            double marketAccuracy,
            double averageOverround,
            String verdict
    ) {
    }

    /**
     * Does the market's price plus this model beat the market's price alone?
     *
     * <p>Trying to out-forecast a bookmaker from raw data is the losing framing. The one
     * with a track record for small operations is to start from the price - which already
     * contains everything the market knows - and let a model adjust it only where it holds
     * information the price lacks. If the model carries no residual information, the fitted
     * weight collapses toward zero and the blend simply reproduces the market.
     *
     * <p>Probabilities are pooled log-linearly, {@code p ∝ market^(1-w) · model^w}, which is
     * the standard combination for probability forecasts scored by log-loss.
     *
     * <p>{@code weight} is fitted on the earlier half of the tested matches and every
     * log-loss here is measured on the later half, so the weight never sees what it is
     * scored on.
     */
    public record BlendResult(
            int fitMatches,
            int testMatches,
            double weight,
            double modelLogLoss,
            double marketLogLoss,
            double blendLogLoss,
            String verdict
    ) {
    }

    /** One scored match: walk-forward model probabilities, market probabilities, outcome. */
    private record Sample(double[] model, double[] market, int outcome) {
    }

    public BacktestResult runFootball(int historyDays, int stepDays, double trainFraction) {
        Instant since = Instant.now().minus(historyDays, ChronoUnit.DAYS);
        List<Fixture> all = fixtures.findTrainingSet(Sport.FOOTBALL, since);
        if (all.size() < props.getModel().getMinMatchesForFit() * 2) {
            throw new IllegalStateException(
                    "not enough history to backtest: " + all.size() + " matches");
        }

        int splitIndex = (int) (all.size() * trainFraction);
        List<Fixture> test = all.subList(splitIndex, all.size());

        double sumLogLoss = 0;
        double sumBrier = 0;
        int correct = 0;
        int scored = 0;
        int refits = 0;

        double baseLogLoss = 0;
        double baseBrier = 0;
        int baseCorrect = 0;

        // Calibration on the model's own pick, in 10-point buckets.
        int[] bucketCount = new int[10];
        double[] bucketPredicted = new double[10];
        int[] bucketHit = new int[10];

        // Market prices indexed once; the table holds a few thousand rows at most.
        Map<Long, MarketOdds> oddsByFixture = new HashMap<>();
        marketOdds.findBySport(Sport.FOOTBALL)
                .forEach(o -> oddsByFixture.put(o.getFixture().getId(), o));
        double marketModelLogLoss = 0;
        double marketLogLoss = 0;
        int marketModelCorrect = 0;
        int marketCorrect = 0;
        double overroundSum = 0;
        int marketMatches = 0;
        List<Sample> samples = new ArrayList<>();

        int cursor = 0;
        while (cursor < test.size()) {
            Instant cutoff = test.get(cursor).getKickoff();
            List<Fixture> train = all.stream().filter(f -> f.getKickoff().isBefore(cutoff)).toList();
            DixonColesParams params = trainer.fit(train, props.getModel().getFootball(), cutoff);
            refits++;

            double[] baseline = outcomeFrequencies(train);
            Instant windowEnd = cutoff.plus(stepDays, ChronoUnit.DAYS);

            while (cursor < test.size() && test.get(cursor).getKickoff().isBefore(windowEnd)) {
                Fixture f = test.get(cursor++);
                // Both teams must be in the fit; an unseen team is not a fair test of the model.
                if (!params.knows(f.getHomeTeam().getId()) || !params.knows(f.getAwayTeam().getId())) {
                    continue;
                }
                String compId = f.getCompetition() != null ? String.valueOf(f.getCompetition().getId()) : "0";
                double lambda = params.lambdaHome(f.getHomeTeam().getId(), f.getAwayTeam().getId(), compId);
                double mu = params.lambdaAway(f.getHomeTeam().getId(), f.getAwayTeam().getId(), compId);
                ScoreGrid.Result grid = ScoreGrid.compute(params, lambda, mu,
                        props.getModel().getFootball().getMaxGoals(), props.getModel().getFootball().getOuLine(), compId);

                int actual = outcomeIndex(f);
                double[] p = {grid.pHome(), grid.pDraw(), grid.pAway()};
                sumLogLoss += -Math.log(Math.max(p[actual], 1e-9));
                sumBrier += brier(p, actual);
                int pick = argmax(p);
                if (pick == actual) {
                    correct++;
                }

                baseLogLoss += -Math.log(Math.max(baseline[actual], 1e-9));
                baseBrier += brier(baseline, actual);
                if (argmax(baseline) == actual) {
                    baseCorrect++;
                }

                int bucket = Math.min(9, (int) (p[pick] * 10));
                bucketCount[bucket]++;
                bucketPredicted[bucket] += p[pick];
                if (pick == actual) {
                    bucketHit[bucket]++;
                }
                scored++;

                // Same match, same walk-forward prediction, scored against the book.
                MarketOdds odds = oddsByFixture.get(f.getId());
                if (odds != null && !odds.twoWay()) {
                    double[] market = odds.impliedProbabilities();
                    marketModelLogLoss += -Math.log(Math.max(p[actual], 1e-9));
                    marketLogLoss += -Math.log(Math.max(market[actual], 1e-9));
                    if (argmax(p) == actual) {
                        marketModelCorrect++;
                    }
                    if (argmax(market) == actual) {
                        marketCorrect++;
                    }
                    overroundSum += 1 / odds.getMedianHome() + 1 / odds.getMedianDraw()
                            + 1 / odds.getMedianAway() - 1;
                    marketMatches++;
                    samples.add(new Sample(p, market, actual));
                }
            }
        }

        if (scored == 0) {
            throw new IllegalStateException("backtest scored 0 matches - widen the history range");
        }

        List<Bucket> calibration = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            if (bucketCount[i] > 0) {
                calibration.add(new Bucket(i / 10.0, (i + 1) / 10.0, bucketCount[i],
                        round(bucketPredicted[i] / bucketCount[i]),
                        round((double) bucketHit[i] / bucketCount[i])));
            }
        }

        log.info("backtest: {} matches scored, logLoss={} (baseline {})",
                scored, round(sumLogLoss / scored), round(baseLogLoss / scored));

        MarketComparison comparison = null;
        if (marketMatches > 0) {
            double model = marketModelLogLoss / marketMatches;
            double market = marketLogLoss / marketMatches;
            comparison = new MarketComparison(marketMatches,
                    round(model), round(market),
                    round((double) marketModelCorrect / marketMatches),
                    round((double) marketCorrect / marketMatches),
                    round(overroundSum / marketMatches),
                    model < market
                            ? "model beats the market by %.4f nats/match on %d matches"
                                    .formatted(market - model, marketMatches)
                            // Parentheses matter: .formatted() binds to the last literal in a
                            // concatenation, so without them the placeholder is never filled.
                            : ("model loses to the market by %.4f nats/match on %d matches - "
                                    + "betting into these prices has negative expectation")
                                    .formatted(model - market, marketMatches));
            log.info("market comparison: model={} market={} over {} matches",
                    round(model), round(market), marketMatches);
        }

        return new BacktestResult(Sport.FOOTBALL.name(), splitIndex, scored, refits,
                round(sumLogLoss / scored), round(sumBrier / scored), round((double) correct / scored),
                round(baseLogLoss / scored), round(baseBrier / scored), round((double) baseCorrect / scored),
                calibration, comparison, blend(samples));
    }

    /**
     * Fits the blend weight on the earlier half of the samples and scores every candidate on
     * the later half, so the weight is never evaluated on data it was chosen from.
     */
    private BlendResult blend(List<Sample> samples) {
        if (samples.size() < 200) {
            return null;
        }
        int split = samples.size() / 2;
        List<Sample> fit = samples.subList(0, split);
        List<Sample> test = samples.subList(split, samples.size());

        double bestWeight = 0;
        double bestLoss = Double.MAX_VALUE;
        for (int step = 0; step <= 50; step++) {
            double weight = step / 50.0;
            double loss = logLoss(fit, weight);
            if (loss < bestLoss) {
                bestLoss = loss;
                bestWeight = weight;
            }
        }

        double marketOnly = logLoss(test, 0.0);
        double modelOnly = logLoss(test, 1.0);
        double blended = logLoss(test, bestWeight);
        double gain = marketOnly - blended;

        String verdict;
        if (bestWeight < 0.02) {
            verdict = "fitted weight is ~0: the model carries no information the price lacks";
        } else if (gain > 0) {
            verdict = ("blend beats the market by %.4f nats/match on %d held-out matches "
                    + "at weight %.2f - check this against the margin before believing it")
                    .formatted(gain, test.size(), bestWeight);
        } else {
            verdict = ("blend does not beat the market out of sample (%.4f nats worse); the "
                    + "weight fitted on earlier matches did not generalise")
                    .formatted(-gain);
        }

        return new BlendResult(fit.size(), test.size(), round(bestWeight),
                round(modelOnly), round(marketOnly), round(blended), verdict);
    }

    /** Log-linear pooling: p proportional to market^(1-w) * model^w, then normalised. */
    private double logLoss(List<Sample> samples, double weight) {
        double total = 0;
        for (Sample sample : samples) {
            double[] pooled = new double[sample.market().length];
            double sum = 0;
            for (int i = 0; i < pooled.length; i++) {
                double market = Math.max(sample.market()[i], 1e-9);
                double model = Math.max(sample.model()[i], 1e-9);
                pooled[i] = Math.pow(market, 1 - weight) * Math.pow(model, weight);
                sum += pooled[i];
            }
            total += -Math.log(Math.max(pooled[sample.outcome()] / sum, 1e-9));
        }
        return total / samples.size();
    }

    private double[] outcomeFrequencies(List<Fixture> matches) {
        double[] counts = new double[3];
        for (Fixture f : matches) {
            counts[outcomeIndex(f)]++;
        }
        double total = Math.max(1, matches.size());
        return new double[]{counts[0] / total, counts[1] / total, counts[2] / total};
    }

    private int outcomeIndex(Fixture f) {
        int h = f.getHomeScore();
        int a = f.getAwayScore();
        return h > a ? 0 : (h == a ? 1 : 2);
    }

    private double brier(double[] p, int actual) {
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            double target = i == actual ? 1 : 0;
            sum += (p[i] - target) * (p[i] - target);
        }
        return sum;
    }

    private int argmax(double[] p) {
        int best = 0;
        for (int i = 1; i < p.length; i++) {
            if (p[i] > p[best]) {
                best = i;
            }
        }
        return best;
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
