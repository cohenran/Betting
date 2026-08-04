package il.co.sportpredict.model.elo;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fighter;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.Team;
import il.co.sportpredict.domain.TeamRating;
import il.co.sportpredict.repo.TeamRatingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Elo with a margin-of-victory multiplier (the FiveThirtyEight variant) plus an
 * exponentially weighted scoring average per team. Updated after every finished event -
 * this is the "learns from every new result" part that needs no retraining.
 */
@Service
@RequiredArgsConstructor
public class EloService {

    /** Weight of the newest game in the scoring average. */
    private static final double EWMA_ALPHA = 0.12;

    private final TeamRatingRepository ratings;
    private final SportPredictProperties props;

    public TeamRating ratingFor(Team team, Sport sport) {
        return ratings.findByTeamAndSport(team, sport)
                .orElseGet(() -> ratings.save(new TeamRating(team, sport, props.getModel().getElo().getInitial())));
    }

    /** Probability the home side wins outright, ignoring draws. */
    public double expectedHomeScore(double eloHome, double eloAway) {
        double diff = eloHome + props.getModel().getElo().getHomeAdvantage() - eloAway;
        return 1.0 / (1.0 + Math.pow(10, -diff / 400.0));
    }

    @Transactional
    public void applyFixture(Fixture fixture) {
        TeamRating home = ratingFor(fixture.getHomeTeam(), fixture.getSport());
        TeamRating away = ratingFor(fixture.getAwayTeam(), fixture.getSport());
        applyFixture(fixture, home, away);
        ratings.save(home);
        ratings.save(away);
    }

    /**
     * Pure in-memory update against ratings the caller already holds.
     *
     * <p>The full history replay uses this: looking each rating up and saving it per fixture
     * costs four statements a match, which is what turned a rebuild into a two-hour job.
     */
    public void applyFixture(Fixture fixture, TeamRating home, TeamRating away) {
        int hs = fixture.getHomeScore();
        int as = fixture.getAwayScore();
        double actual = hs > as ? 1.0 : (hs == as ? 0.5 : 0.0);
        double expected = expectedHomeScore(home.getElo(), away.getElo());

        double k = fixture.getSport() == Sport.BASKETBALL
                ? props.getModel().getElo().getKBasketball()
                : props.getModel().getElo().getKFootball();
        double eloDiffWinner = (actual == 1.0 ? 1 : -1)
                * (home.getElo() + props.getModel().getElo().getHomeAdvantage() - away.getElo());
        double delta = k * movMultiplier(Math.abs(hs - as), eloDiffWinner) * (actual - expected);

        home.setElo(home.getElo() + delta);
        away.setElo(away.getElo() - delta);
        updateScoring(home, hs, as);
        updateScoring(away, as, hs);
    }

    /** Rating for a team, created in memory if absent. Not persisted by this call. */
    public TeamRating detachedRatingFor(Team team, Sport sport) {
        TeamRating rating = new TeamRating(team, sport, props.getModel().getElo().getInitial());
        rating.setUpdatedAt(Instant.now());
        return rating;
    }

    /** Elo update for a finished fight. Draws move both fighters halfway. */
    public void applyFight(Fighter a, Fighter b, Fighter winner) {
        double expectedA = 1.0 / (1.0 + Math.pow(10, -(a.getElo() - b.getElo()) / 400.0));
        double actualA = winner == null ? 0.5 : (winner.getId().equals(a.getId()) ? 1.0 : 0.0);
        double delta = props.getModel().getElo().getKUfc() * (actualA - expectedA);
        a.setElo(a.getElo() + delta);
        b.setElo(b.getElo() - delta);

        if (winner == null) {
            a.setDraws(a.getDraws() + 1);
            b.setDraws(b.getDraws() + 1);
            a.setWinStreak(0);
            b.setWinStreak(0);
        } else {
            Fighter won = winner.getId().equals(a.getId()) ? a : b;
            Fighter lost = winner.getId().equals(a.getId()) ? b : a;
            won.setWins(won.getWins() + 1);
            won.setWinStreak(won.getWinStreak() + 1);
            lost.setLosses(lost.getLosses() + 1);
            lost.setWinStreak(0);
        }
        a.setUpdatedAt(Instant.now());
        b.setUpdatedAt(Instant.now());
    }

    /** Big wins count more, but less so when the favourite was expected to win big. */
    private double movMultiplier(int margin, double eloDiffWinnerPerspective) {
        return Math.log(Math.abs(margin) + 1.0) * (2.2 / (0.001 * eloDiffWinnerPerspective + 2.2));
    }

    private void updateScoring(TeamRating rating, int scored, int conceded) {
        if (rating.getMatches() == 0) {
            rating.setScored(scored);
            rating.setConceded(conceded);
        } else {
            rating.setScored((1 - EWMA_ALPHA) * rating.getScored() + EWMA_ALPHA * scored);
            rating.setConceded((1 - EWMA_ALPHA) * rating.getConceded() + EWMA_ALPHA * conceded);
        }
        rating.setMatches(rating.getMatches() + 1);
        rating.setUpdatedAt(Instant.now());
    }
}
