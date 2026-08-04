package il.co.sportpredict.model;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.*;
import il.co.sportpredict.model.elo.EloService;
import il.co.sportpredict.model.football.DixonColesParams;
import il.co.sportpredict.model.football.DixonColesTrainer;
import il.co.sportpredict.model.football.FootballPredictor;
import il.co.sportpredict.model.ufc.OnlineLogistic;
import il.co.sportpredict.model.ufc.UfcPredictor;
import il.co.sportpredict.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that turns finished results into model state.
 *
 * <p>Two speeds:
 * <ul>
 *   <li>{@link #processNewResults()} - cheap, runs after every ingest. Applies Elo and
 *       scoring averages for matches that just finished, and takes one SGD step per new
 *       fight. This is the "learns from each new result" path.</li>
 *   <li>{@link #rebuildAndRefit()} - nightly. Replays the whole history in chronological
 *       order and refits Dixon-Coles. Needed because the history backfill walks
 *       <em>backwards</em> in time, and Elo is only meaningful when applied in order.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningService {

    private final FixtureRepository fixtures;
    private final FightRepository fights;
    private final TeamRatingRepository ratings;
    private final FighterRepository fighters;
    private final PredictionRepository predictions;
    private final EloService elo;
    private final DixonColesTrainer trainer;
    private final FootballPredictor footballPredictor;
    private final UfcPredictor ufcPredictor;
    private final ModelStateStore store;
    private final SportPredictProperties props;

    public record RefitReport(int fixturesReplayed, int fightsReplayed, int footballFitSize,
                              int settledPredictions, boolean footballFitted) {
    }

    /** Incremental learning for results that arrived since the last run. */
    @Transactional
    public int processNewResults() {
        List<Fixture> pending = fixtures.findUnlearned();
        pending.sort(Comparator.comparing(Fixture::getKickoff));
        for (Fixture f : pending) {
            elo.applyFixture(f);
            f.setLearned(true);
        }
        fixtures.saveAll(pending);

        List<Fight> pendingFights = fights.findUnlearned();
        pendingFights.sort(Comparator.comparing(Fight::getFightDate));
        for (Fight fight : pendingFights) {
            // SGD step first: the features must describe the state *before* this result.
            ufcPredictor.learn(fight);
            elo.applyFight(fight.getFighterA(), fight.getFighterB(), fight.getWinner());
            fighters.save(fight.getFighterA());
            fighters.save(fight.getFighterB());
            fight.setLearned(true);
        }
        fights.saveAll(pendingFights);
        if (!pendingFights.isEmpty()) {
            store.save(UfcPredictor.STATE_KEY, ufcPredictor.model(), ufcPredictor.model().getUpdates(), "online");
        }

        int total = pending.size() + pendingFights.size();
        if (total > 0) {
            log.info("learned from {} new results ({} fixtures, {} fights)",
                    total, pending.size(), pendingFights.size());
        }
        return total;
    }

    /** Full chronological replay plus a Dixon-Coles refit. Safe to run repeatedly. */
    @Transactional
    public RefitReport rebuildAndRefit() {
        Instant since = Instant.now().minus(props.getIngest().getHistoryDays() * 2L, ChronoUnit.DAYS);

        // 1. Reset ratings and replay every finished football/basketball match in order.
        // Ratings are indexed in memory and written once at the end. Looking each one up
        // and saving it per fixture cost four statements a match, which is what made a
        // 60k-fixture rebuild take two hours.
        Map<String, TeamRating> ratingIndex = new HashMap<>();
        for (TeamRating r : ratings.findAll()) {
            r.setElo(props.getModel().getElo().getInitial());
            r.setMatches(0);
            r.setScored(0);
            r.setConceded(0);
            ratingIndex.put(ratingKey(r.getTeam().getId(), r.getSport()), r);
        }

        List<Fixture> football = fixtures.findTrainingSet(Sport.FOOTBALL, since);
        List<Fixture> basketball = fixtures.findTrainingSet(Sport.BASKETBALL, since);
        List<Fixture> all = new ArrayList<>(football.size() + basketball.size());
        all.addAll(football);
        all.addAll(basketball);
        all.sort(Comparator.comparing(Fixture::getKickoff));
        for (Fixture f : all) {
            elo.applyFixture(f, rating(ratingIndex, f.getHomeTeam(), f.getSport()),
                    rating(ratingIndex, f.getAwayTeam(), f.getSport()));
        }
        ratings.saveAll(ratingIndex.values());
        int marked = fixtures.markFinishedAsLearned();
        log.info("replayed {} fixtures, marked {} learned", all.size(), marked);

        // 2. Reset fighters and replay UFC history with a fresh online model.
        List<Fighter> allFighters = fighters.findAll();
        allFighters.forEach(f -> {
            f.setElo(props.getModel().getElo().getInitial());
            f.setWins(0);
            f.setLosses(0);
            f.setDraws(0);
            f.setWinStreak(0);
        });
        fighters.saveAll(allFighters);

        OnlineLogistic fresh = UfcPredictor.prior();
        ufcPredictor.replaceModel(fresh);
        List<Fight> fightHistory = fights.findTrainingSet(since);
        for (int epoch = 0; epoch < Math.max(1, props.getModel().getUfc().getEpochsOnRefit()); epoch++) {
            // Ratings are only rebuilt on the first pass; later epochs just refine weights.
            boolean applyRatings = epoch == 0;
            for (Fight fight : fightHistory) {
                ufcPredictor.learn(fight);
                if (applyRatings) {
                    elo.applyFight(fight.getFighterA(), fight.getFighterB(), fight.getWinner());
                    fight.setLearned(true);
                }
            }
        }
        fights.saveAll(fightHistory);
        fighters.saveAll(allFighters);
        store.save(UfcPredictor.STATE_KEY, ufcPredictor.model(), ufcPredictor.model().getUpdates(), "refit");

        // 3. Refit Dixon-Coles on the football history.
        boolean fitted = false;
        int fitSize = 0;
        if (football.size() >= props.getModel().getMinMatchesForFit()) {
            DixonColesParams params = trainer.fit(football, props.getModel().getFootball(), Instant.now());
            footballPredictor.replaceParams(params);
            store.save(FootballPredictor.STATE_KEY, params, params.getSampleSize(), "dc1");
            fitted = true;
            fitSize = params.getSampleSize();
        } else {
            log.info("skipping Dixon-Coles fit: {} finished matches, need {}",
                    football.size(), props.getModel().getMinMatchesForFit());
        }

        int settled = settlePredictions();
        return new RefitReport(all.size(), fightHistory.size(), fitSize, settled, fitted);
    }

    /** Scores past predictions against reality so calibration can be tracked. */
    @Transactional
    public int settlePredictions() {
        List<Prediction> pending = predictions.findSettleableFixturePredictions();
        for (Prediction p : pending) {
            Fixture f = p.getFixture();
            String outcome = f.getHomeScore() > f.getAwayScore() ? "HOME"
                    : (f.getHomeScore().equals(f.getAwayScore()) ? "DRAW" : "AWAY");
            score(p, outcome);
        }

        List<Prediction> fightPending = predictions.findSettleableFightPredictions();
        for (Prediction p : fightPending) {
            Fight fight = p.getFight();
            String outcome = fight.getWinner().getId().equals(fight.getFighterA().getId()) ? "HOME" : "AWAY";
            score(p, outcome);
        }

        predictions.saveAll(pending);
        predictions.saveAll(fightPending);
        return pending.size() + fightPending.size();
    }

    private void score(Prediction p, String outcome) {
        double pHome = nz(p.getPHome());
        double pDraw = nz(p.getPDraw());
        double pAway = nz(p.getPAway());
        double predicted = switch (outcome) {
            case "HOME" -> pHome;
            case "DRAW" -> pDraw;
            default -> pAway;
        };
        p.setOutcome(outcome);
        p.setLogLoss(-Math.log(Math.max(predicted, 1e-9)));
        double brier = sq(pHome - (outcome.equals("HOME") ? 1 : 0))
                + sq(pDraw - (outcome.equals("DRAW") ? 1 : 0))
                + sq(pAway - (outcome.equals("AWAY") ? 1 : 0));
        p.setBrier(brier);
        p.setSettled(true);
    }

    private String ratingKey(Long teamId, Sport sport) {
        return teamId + "|" + sport;
    }

    /** Existing rating, or a fresh in-memory one for a team seen for the first time. */
    private TeamRating rating(Map<String, TeamRating> index, Team team, Sport sport) {
        return index.computeIfAbsent(ratingKey(team.getId(), sport),
                key -> elo.detachedRatingFor(team, sport));
    }

    private double nz(Double v) {
        return v == null ? 0 : v;
    }

    private double sq(double v) {
        return v * v;
    }
}
