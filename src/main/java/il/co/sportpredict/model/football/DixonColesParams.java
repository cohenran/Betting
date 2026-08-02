package il.co.sportpredict.model.football;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Fitted Dixon-Coles parameters.
 *
 * <p>Goal expectations for a match:
 * <pre>
 *   lambda(home) = baseGoals * exp(attack[home] - defense[away] + homeAdvantage)
 *   mu(away)     = baseGoals * exp(attack[away] - defense[home])
 * </pre>
 * {@code rho} is the low-score correlation correction that plain double-Poisson misses.
 * Maps are keyed by team id as a string so the whole thing round-trips through JSON.
 */
@Getter
@Setter
@NoArgsConstructor
public class DixonColesParams {

    private Map<String, Double> attack = new HashMap<>();
    private Map<String, Double> defense = new HashMap<>();
    private double homeAdvantage = 0.25;
    private double rho = -0.04;
    private double baseGoals = 1.35;
    private int sampleSize = 0;
    private double trainLogLikelihood = 0;

    public double attackOf(Long teamId) {
        return attack.getOrDefault(String.valueOf(teamId), 0.0);
    }

    public double defenseOf(Long teamId) {
        return defense.getOrDefault(String.valueOf(teamId), 0.0);
    }

    public boolean knows(Long teamId) {
        return attack.containsKey(String.valueOf(teamId));
    }

    public double lambdaHome(Long homeId, Long awayId) {
        return baseGoals * Math.exp(attackOf(homeId) - defenseOf(awayId) + homeAdvantage);
    }

    public double lambdaAway(Long homeId, Long awayId) {
        return baseGoals * Math.exp(attackOf(awayId) - defenseOf(homeId));
    }

    /** Dixon-Coles tau correction for the four low-score cells. */
    public double tau(int x, int y, double lambda, double mu) {
        double t;
        if (x == 0 && y == 0) {
            t = 1 - lambda * mu * rho;
        } else if (x == 0 && y == 1) {
            t = 1 + lambda * rho;
        } else if (x == 1 && y == 0) {
            t = 1 + mu * rho;
        } else if (x == 1 && y == 1) {
            t = 1 - rho;
        } else {
            t = 1;
        }
        return Math.max(t, 1e-6);
    }
}
