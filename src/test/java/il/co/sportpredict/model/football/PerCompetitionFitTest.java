package il.co.sportpredict.model.football;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Competition;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.Team;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two leagues that genuinely differ: one high-scoring with a strong home effect, one
 * low-scoring with a weak one, and disjoint squads. The point of per-competition
 * baselines is that both are recovered separately - including the smaller league, which
 * is exactly the case a single global average used to swallow.
 */
class PerCompetitionFitTest {

    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");

    private static final double BIG_BASE = 1.85;
    private static final double BIG_HOME = 0.45;
    private static final double SMALL_BASE = 1.05;
    private static final double SMALL_HOME = 0.08;

    @Test
    void recoversBaselinesForBothTheLargeAndTheSmallCompetition() {
        Competition big = competition(1L, "Big League");
        Competition small = competition(2L, "Small League");

        List<Fixture> matches = new ArrayList<>();
        // Deliberately lopsided: the small league is ~4% of the data, which is realistic
        // next to a dozen European leagues, and is where a global average does the damage.
        matches.addAll(simulate(teams(1, 12), big, BIG_BASE, BIG_HOME, 10, 1L));
        matches.addAll(simulate(teams(101, 8), small, SMALL_BASE, SMALL_HOME, 1, 2L));

        DixonColesParams params = new DixonColesTrainer().fit(matches, config(), NOW);

        assertThat(params.baseGoalsOf("1")).isCloseTo(BIG_BASE, org.assertj.core.data.Percentage.withPercentage(20));
        assertThat(params.baseGoalsOf("2")).isCloseTo(SMALL_BASE, org.assertj.core.data.Percentage.withPercentage(25));

        // The whole purpose: the small league must not inherit the big league's home effect.
        assertThat(params.homeAdvantageOf("1")).isGreaterThan(params.homeAdvantageOf("2") + 0.15);
        assertThat(params.homeAdvantageOf("2")).isLessThan(0.25);
    }

    @Test
    void aCompetitionWithTooFewMatchesFallsBackInsteadOfOverfitting() {
        Competition league = competition(1L, "League");
        Competition oneOffCup = competition(9L, "Super Cup");

        List<Fixture> matches = new ArrayList<>(simulate(teams(1, 8), league, BIG_BASE, BIG_HOME, 10, 3L));
        // A single freak result must not become that competition's permanent baseline.
        Fixture freak = fixture(teams(1, 8).get(0), teams(1, 8).get(1), oneOffCup, 6, 0, 5);
        matches.add(freak);

        DixonColesParams params = new DixonColesTrainer().fit(matches, config(), NOW);

        // Pooled to the global baseline rather than fitted to one 6-0.
        assertThat(params.baseGoalsOf("9")).isBetween(0.8, 3.0);
        assertThat(params.homeAdvantageOf("9")).isBetween(-0.2, 0.8);
    }

    /** Mirrors application.yml. A high learning rate hides convergence problems. */
    private SportPredictProperties.Football config() {
        SportPredictProperties.Football cfg = new SportPredictProperties.Football();
        cfg.setIterations(500);
        cfg.setLearningRate(0.015);
        cfg.setL2(0.02);
        cfg.setHalfLifeDays(240);
        return cfg;
    }

    private Competition competition(Long id, String name) {
        Competition competition = new Competition(Sport.FOOTBALL, name, "IL", null);
        competition.setId(id);
        return competition;
    }

    private List<Team> teams(int firstId, int count) {
        List<Team> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Team team = new Team(Sport.FOOTBALL, "Team " + (firstId + i), "team " + (firstId + i), "IL");
            team.setId((long) (firstId + i));
            out.add(team);
        }
        return out;
    }

    /** Double round-robin, equal-strength teams so only the baselines are under test. */
    private List<Fixture> simulate(List<Team> teams, Competition competition,
                                   double base, double homeAdvantage, int rounds, long seed) {
        Random random = new Random(seed);
        List<Fixture> out = new ArrayList<>();
        int day = 0;
        for (int r = 0; r < rounds; r++) {
            for (int h = 0; h < teams.size(); h++) {
                for (int a = 0; a < teams.size(); a++) {
                    if (h == a) {
                        continue;
                    }
                    double lambda = base * Math.exp(homeAdvantage);
                    out.add(fixture(teams.get(h), teams.get(a), competition,
                            poisson(random, lambda), poisson(random, base), day++ % 300));
                }
            }
        }
        return out;
    }

    private Fixture fixture(Team home, Team away, Competition competition, int hg, int ag, int daysAgo) {
        Fixture f = new Fixture();
        f.setSport(Sport.FOOTBALL);
        f.setCompetition(competition);
        f.setHomeTeam(home);
        f.setAwayTeam(away);
        f.setKickoff(NOW.minus(daysAgo, ChronoUnit.DAYS));
        f.setHomeScore(hg);
        f.setAwayScore(ag);
        return f;
    }

    private int poisson(Random random, double mean) {
        double limit = Math.exp(-mean);
        double product = random.nextDouble();
        int count = 0;
        while (product > limit) {
            count++;
            product *= random.nextDouble();
        }
        return count;
    }
}
