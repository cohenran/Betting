package il.co.sportpredict.model.football;

import il.co.sportpredict.config.SportPredictProperties;
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
 * Trains on a synthetic league where team strength is known, then checks the fit
 * recovers the ordering and that the resulting probabilities are sane.
 */
class DixonColesTrainerTest {

    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");

    @Test
    void recoversTeamStrengthOrdering() {
        List<Team> teams = teams(8);
        // True attack strength: team 0 strongest, team 7 weakest.
        double[] trueAttack = {0.6, 0.4, 0.2, 0.1, -0.1, -0.2, -0.4, -0.6};
        List<Fixture> matches = simulate(teams, trueAttack, 12, 20260701L);

        DixonColesParams params = new DixonColesTrainer().fit(matches, config(), NOW);

        assertThat(params.getSampleSize()).isEqualTo(matches.size());
        assertThat(params.attackOf(teams.get(0).getId()))
                .isGreaterThan(params.attackOf(teams.get(7).getId()));
        assertThat(params.getGlobalHomeAdvantage()).isBetween(0.05, 0.6);
        // Strongest at home against weakest must be a clear favourite.
        double lambda = params.lambdaHome(teams.get(0).getId(), teams.get(7).getId(), "0");
        double mu = params.lambdaAway(teams.get(0).getId(), teams.get(7).getId(), "0");
        assertThat(lambda).isGreaterThan(mu);

        ScoreGrid.Result grid = ScoreGrid.compute(params, lambda, mu, 10, 2.5, "0");
        assertThat(grid.pHome() + grid.pDraw() + grid.pAway()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(grid.pHome()).isGreaterThan(grid.pAway());
    }

    @Test
    void emptyInputProducesNeutralParams() {
        DixonColesParams params = new DixonColesTrainer().fit(List.of(), config(), NOW);
        assertThat(params.getSampleSize()).isZero();
        assertThat(params.knows(1L)).isFalse();
        // Unknown teams fall back to the league average, so the model still returns a number.
        assertThat(params.lambdaHome(1L, 2L, "0")).isGreaterThan(0);
    }

    private SportPredictProperties.Football config() {
        SportPredictProperties.Football cfg = new SportPredictProperties.Football();
        cfg.setIterations(400);
        cfg.setLearningRate(0.05);
        cfg.setHalfLifeDays(400);
        return cfg;
    }

    private List<Team> teams(int count) {
        List<Team> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Team team = new Team(Sport.FOOTBALL, "Team " + i, "team " + i, "IL");
            team.setId((long) (i + 1));
            out.add(team);
        }
        return out;
    }

    /** Double round-robin repeated {@code rounds} times, goals drawn from the true model. */
    private List<Fixture> simulate(List<Team> teams, double[] trueAttack, int rounds, long seed) {
        Random random = new Random(seed);
        List<Fixture> out = new ArrayList<>();
        double base = 1.35;
        double homeAdvantage = 0.25;
        int day = 0;
        for (int r = 0; r < rounds; r++) {
            for (int h = 0; h < teams.size(); h++) {
                for (int a = 0; a < teams.size(); a++) {
                    if (h == a) {
                        continue;
                    }
                    double lambda = base * Math.exp(trueAttack[h] + trueAttack[a] * -1 + homeAdvantage);
                    double mu = base * Math.exp(trueAttack[a] + trueAttack[h] * -1);
                    Fixture f = new Fixture();
                    f.setSport(Sport.FOOTBALL);
                    f.setHomeTeam(teams.get(h));
                    f.setAwayTeam(teams.get(a));
                    f.setKickoff(NOW.minus(400 - (day++ % 400), ChronoUnit.DAYS));
                    f.setHomeScore(poisson(random, lambda));
                    f.setAwayScore(poisson(random, mu));
                    out.add(f);
                }
            }
        }
        return out;
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
