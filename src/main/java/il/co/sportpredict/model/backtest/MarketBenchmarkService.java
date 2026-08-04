package il.co.sportpredict.model.backtest;

import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.ingest.AllSportsOddsClient;
import il.co.sportpredict.ingest.AllSportsProvider;
import il.co.sportpredict.ingest.OddsSnapshot;
import il.co.sportpredict.model.MatchPrediction;
import il.co.sportpredict.model.PredictionService;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.FixtureSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scores the model against the bookmakers on the same finished matches.
 *
 * <p>The paper trade answers this eventually, but far too slowly: distinguishing a +3% edge
 * from zero needs thousands of bets, which is well over a year at realistic volume. This
 * compares model and market log-loss directly on however much settled history has prices,
 * so hundreds of matches can be judged at once.
 *
 * <p>Market probabilities are the median prices with the overround divided out, which is the
 * standard way to read a book's actual opinion.
 *
 * <p><b>Read the result one-directionally.</b> The model's parameters were fitted on history
 * that includes these matches, so its log-loss here is optimistic - this is not a
 * walk-forward test. Therefore: losing to the market is conclusive, since a leak-free model
 * could only do worse. Beating the market is inconclusive and would need the walk-forward
 * backtest restricted to the same fixtures.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketBenchmarkService {

    private final AllSportsOddsClient oddsClient;
    private final FixtureSourceRepository fixtureSources;
    private final FixtureRepository fixtures;
    private final PredictionService predictions;

    public record Comparison(
            String sport,
            String window,
            int pricedMatches,
            int scored,
            double modelLogLoss,
            double marketLogLoss,
            double modelAccuracy,
            double marketAccuracy,
            double averageOverround,
            String verdict,
            String caveat
    ) {
    }

    public Comparison run(Sport sport, LocalDate from, LocalDate to) {
        Map<String, OddsSnapshot> odds = oddsClient.fetch(sport, from, to);
        if (odds.isEmpty()) {
            throw new IllegalStateException(
                    "no odds returned for " + from + ".." + to + " - the provider may not serve history");
        }

        double modelLogLoss = 0;
        double marketLogLoss = 0;
        int modelCorrect = 0;
        int marketCorrect = 0;
        double overround = 0;
        int scored = 0;

        for (OddsSnapshot snapshot : odds.values()) {
            Long fixtureId = fixtureSources
                    .findByProviderAndSportAndExternalId(AllSportsProvider.NAME, sport, snapshot.externalId())
                    .map(source -> source.getFixture().getId())
                    .orElse(null);
            if (fixtureId == null) {
                continue;
            }
            Fixture fixture = fixtures.findWithTeams(fixtureId).orElse(null);
            if (fixture == null || !fixture.hasResult()) {
                continue;
            }

            int outcome = outcomeIndex(fixture);
            double[] market = impliedProbabilities(snapshot);
            MatchPrediction prediction = predictions.predict(fixture);
            double[] model = snapshot.twoWay()
                    ? normalise(new double[]{prediction.pHome(), prediction.pAway()})
                    : normalise(new double[]{prediction.pHome(), prediction.pDraw(), prediction.pAway()});

            // A two-way market cannot score a drawn game; skip rather than distort.
            if (snapshot.twoWay()) {
                if (outcome == 1) {
                    continue;
                }
                outcome = outcome == 0 ? 0 : 1;
            }

            modelLogLoss += -Math.log(clamp(model[outcome]));
            marketLogLoss += -Math.log(clamp(market[outcome]));
            if (argmax(model) == outcome) {
                modelCorrect++;
            }
            if (argmax(market) == outcome) {
                marketCorrect++;
            }
            overround += snapshot.overround();
            scored++;
        }

        if (scored == 0) {
            throw new IllegalStateException(
                    "no finished fixtures with prices in that window - " + odds.size()
                            + " priced matches, none matched a settled fixture");
        }

        double model = modelLogLoss / scored;
        double market = marketLogLoss / scored;
        String verdict = model < market
                ? "model log-loss is lower than the market's - inconclusive, see caveat"
                : "model loses to the market by %.4f nats per match".formatted(model - market);

        Comparison result = new Comparison(sport.name(), from + ".." + to,
                odds.size(), scored, round(model), round(market),
                round((double) modelCorrect / scored), round((double) marketCorrect / scored),
                round(overround / scored), verdict,
                "model params were fitted on history including these matches, so its number "
                        + "is optimistic; losing to the market is therefore conclusive, beating it is not");
        log.info("market benchmark {}: model={} market={} over {} matches",
                sport, result.modelLogLoss(), result.marketLogLoss(), scored);
        return result;
    }

    /** Median prices with the overround removed - the book's actual opinion. */
    private double[] impliedProbabilities(OddsSnapshot snapshot) {
        double[] raw = snapshot.twoWay()
                ? new double[]{1 / snapshot.medianHome(), 1 / snapshot.medianAway()}
                : new double[]{1 / snapshot.medianHome(), 1 / snapshot.medianDraw(), 1 / snapshot.medianAway()};
        return normalise(raw);
    }

    private double[] normalise(double[] values) {
        double total = 0;
        for (double v : values) {
            total += v;
        }
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = total > 0 ? values[i] / total : 1.0 / values.length;
        }
        return out;
    }

    private int outcomeIndex(Fixture fixture) {
        int home = fixture.getHomeScore();
        int away = fixture.getAwayScore();
        return home > away ? 0 : (home == away ? 1 : 2);
    }

    private int argmax(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    private double clamp(double p) {
        return Math.min(0.9999, Math.max(1e-6, p));
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
