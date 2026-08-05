package il.co.sportpredict.model.backtest;

import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.MarketOdds;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.TeamRating;
import il.co.sportpredict.model.MatchPrediction;
import il.co.sportpredict.model.ModelStateStore;
import il.co.sportpredict.model.basketball.BasketballPredictor;
import il.co.sportpredict.model.elo.EloService;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.MarketOddsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Walk-forward test of the basketball model.
 *
 * <p>Simpler than the football equivalent because Elo is an online model: no refits are
 * needed. History is replayed in order against <em>detached</em> ratings, and each game is
 * predicted from the state before that game was applied - so nothing it scores has
 * influenced its own prediction, and the live ratings are never touched.
 *
 * <p>Two-way market, so the baseline is the home-win rate observed so far. Beating that is
 * the minimum bar for the Elo ratings carrying any information.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BasketballBacktestService {

    public static final String STATE_KEY = "basketball-elo-backtest";

    /** Fraction of history used to warm the ratings before scoring begins. */
    private static final double BURN_IN = 0.4;

    private final FixtureRepository fixtures;
    private final MarketOddsRepository marketOdds;
    private final BasketballPredictor predictor;
    private final EloService elo;
    private final ModelStateStore store;

    public Optional<BacktestService.StoredResult> lastResult() {
        return store.load(STATE_KEY, BacktestService.StoredResult.class);
    }

    /**
     * Reuses {@link BacktestService.StoredResult} so both sports report the same shape and
     * the paper-bet gate has one type to check.
     */
    public BacktestService.StoredResult runAndStore(int historyDays) {
        Instant since = Instant.now().minus(historyDays, ChronoUnit.DAYS);
        List<Fixture> games = fixtures.findTrainingSet(Sport.BASKETBALL, since);
        if (games.size() < 200) {
            throw new IllegalStateException(
                    "not enough basketball history to backtest: " + games.size() + " games");
        }

        Map<String, TeamRating> ratings = new HashMap<>();
        int burnIn = (int) (games.size() * BURN_IN);

        // Market prices for the same games, so the replay can answer the only question that
        // decides profitability: does the model beat the closing line, not just the base rate.
        Map<Long, MarketOdds> oddsByFixture = new HashMap<>();
        marketOdds.findBySport(Sport.BASKETBALL)
                .forEach(o -> oddsByFixture.put(o.getFixture().getId(), o));
        double marketModelLogLoss = 0;
        double marketLogLoss = 0;
        int marketModelCorrect = 0;
        int marketCorrect = 0;
        double overroundSum = 0;
        int marketMatches = 0;

        double sumLogLoss = 0;
        double sumBrier = 0;
        int correct = 0;
        int scored = 0;

        double baseLogLoss = 0;
        int baseCorrect = 0;
        int homeWinsSoFar = 0;
        int gamesSoFar = 0;

        for (int i = 0; i < games.size(); i++) {
            Fixture game = games.get(i);
            TeamRating home = rating(ratings, game, true);
            TeamRating away = rating(ratings, game, false);
            boolean homeWon = game.getHomeScore() > game.getAwayScore();

            if (i >= burnIn && home.getMatches() > 0 && away.getMatches() > 0) {
                MatchPrediction prediction = predictor.predictFrom(home, away);
                double pHome = clamp(prediction.pHome());
                double actual = homeWon ? pHome : 1 - pHome;
                sumLogLoss += -Math.log(actual);
                sumBrier += sq(pHome - (homeWon ? 1 : 0));
                if ((pHome >= 0.5) == homeWon) {
                    correct++;
                }

                // Baseline: the home-win rate seen up to this point, nothing else.
                double baseHome = clamp(gamesSoFar == 0 ? 0.6 : (double) homeWinsSoFar / gamesSoFar);
                baseLogLoss += -Math.log(homeWon ? baseHome : 1 - baseHome);
                if ((baseHome >= 0.5) == homeWon) {
                    baseCorrect++;
                }
                scored++;

                MarketOdds odds = oddsByFixture.get(game.getId());
                if (odds != null && odds.twoWay()) {
                    double marketHome = clamp(odds.impliedProbabilities()[0]);
                    marketModelLogLoss += -Math.log(homeWon ? pHome : 1 - pHome);
                    marketLogLoss += -Math.log(homeWon ? marketHome : 1 - marketHome);
                    if ((pHome >= 0.5) == homeWon) {
                        marketModelCorrect++;
                    }
                    if ((marketHome >= 0.5) == homeWon) {
                        marketCorrect++;
                    }
                    overroundSum += 1 / odds.getMedianHome() + 1 / odds.getMedianAway() - 1;
                    marketMatches++;
                }
            }

            elo.applyFixture(game, home, away);
            gamesSoFar++;
            if (homeWon) {
                homeWinsSoFar++;
            }
        }

        if (scored == 0) {
            throw new IllegalStateException("basketball backtest scored 0 games - widen the range");
        }

        BacktestService.MarketComparison comparison = null;
        if (marketMatches > 0) {
            double model = marketModelLogLoss / marketMatches;
            double market = marketLogLoss / marketMatches;
            comparison = new BacktestService.MarketComparison(marketMatches,
                    round(model), round(market),
                    round((double) marketModelCorrect / marketMatches),
                    round((double) marketCorrect / marketMatches),
                    round(overroundSum / marketMatches),
                    model < market
                            ? "model beats the market by %.4f nats/match on %d games"
                                    .formatted(market - model, marketMatches)
                            : ("model loses to the market by %.4f nats/match on %d games - "
                                    + "betting into these prices has negative expectation")
                                    .formatted(model - market, marketMatches));
        }

        BacktestService.StoredResult result = new BacktestService.StoredResult(
                round(sumLogLoss / scored), round(baseLogLoss / scored),
                round((double) correct / scored), scored, Instant.now().toString(),
                comparison);
        store.save(STATE_KEY, result, scored, "elo-walk-forward");
        log.info("basketball backtest: {} games scored, logLoss={} baseline={} accuracy={} "
                        + "(baseline accuracy {}), brier={}",
                scored, result.logLoss(), result.baselineLogLoss(), result.accuracy(),
                round((double) baseCorrect / scored), round(sumBrier / scored));
        return result;
    }

    private TeamRating rating(Map<String, TeamRating> index, Fixture game, boolean homeSide) {
        var team = homeSide ? game.getHomeTeam() : game.getAwayTeam();
        return index.computeIfAbsent(String.valueOf(team.getId()),
                key -> elo.detachedRatingFor(team, Sport.BASKETBALL));
    }

    private double clamp(double p) {
        return Math.min(0.995, Math.max(0.005, p));
    }

    private double sq(double v) {
        return v * v;
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
