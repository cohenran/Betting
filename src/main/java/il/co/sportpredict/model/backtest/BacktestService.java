package il.co.sportpredict.model.backtest;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.model.ModelStateStore;
import il.co.sportpredict.model.football.DixonColesParams;
import il.co.sportpredict.model.football.DixonColesTrainer;
import il.co.sportpredict.model.football.ScoreGrid;
import il.co.sportpredict.repo.FixtureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
                               int testMatches, String ranAt) {

        /** The minimum bar: the model must be better calibrated than the base rates. */
        public boolean beatsBaseline() {
            return logLoss < baselineLogLoss;
        }
    }

    public Optional<StoredResult> lastResult() {
        return store.load(STATE_KEY, StoredResult.class);
    }

    /** Runs a walk-forward evaluation and persists the headline numbers. */
    public StoredResult runAndStore(int historyDays, int stepDays, double trainFraction) {
        BacktestResult result = runFootball(historyDays, stepDays, trainFraction);
        StoredResult stored = new StoredResult(result.logLoss(), result.baselineLogLoss(),
                result.accuracy(), result.testMatches(), Instant.now().toString());
        store.save(STATE_KEY, stored, result.testMatches(), "walk-forward");
        log.info("stored backtest: logLoss={} baseline={} beatsBaseline={}",
                stored.logLoss(), stored.baselineLogLoss(), stored.beatsBaseline());
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
            List<Bucket> calibration
    ) {
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
                double lambda = params.lambdaHome(f.getHomeTeam().getId(), f.getAwayTeam().getId());
                double mu = params.lambdaAway(f.getHomeTeam().getId(), f.getAwayTeam().getId());
                ScoreGrid.Result grid = ScoreGrid.compute(params, lambda, mu,
                        props.getModel().getFootball().getMaxGoals(), props.getModel().getFootball().getOuLine());

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

        return new BacktestResult(Sport.FOOTBALL.name(), splitIndex, scored, refits,
                round(sumLogLoss / scored), round(sumBrier / scored), round((double) correct / scored),
                round(baseLogLoss / scored), round(baseBrier / scored), round((double) baseCorrect / scored),
                calibration);
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
