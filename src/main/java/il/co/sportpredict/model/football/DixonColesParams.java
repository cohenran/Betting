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
    
    // Per-competition baseline parameters
    private Map<String, Double> baseGoals = new HashMap<>();
    private Map<String, Double> homeAdvantage = new HashMap<>();
    private Map<String, Double> rho = new HashMap<>();
    
    // Global fallbacks for unseen competitions
    @Setter private double globalBaseGoals = 1.35;
    @Setter private double globalHomeAdvantage = 0.25;
    @Setter private double globalRho = -0.04;
    
    private int sampleSize = 0;
    private double trainLogLikelihood = 0;

    public double attackOf(Long teamId) {
        return attack.getOrDefault(String.valueOf(teamId), 0.0);
    }

    public double defenseOf(Long teamId) {
        return defense.getOrDefault(String.valueOf(teamId), 0.0);
    }
    
    public double baseGoalsOf(String compId) {
        if (baseGoals == null) return globalBaseGoals; // Backward compatibility with old JSON
        return baseGoals.getOrDefault(compId, globalBaseGoals);
    }
    
    public double homeAdvantageOf(String compId) {
        if (homeAdvantage == null) return globalHomeAdvantage; // Backward compatibility with old JSON
        return homeAdvantage.getOrDefault(compId, globalHomeAdvantage);
    }
    
    public double rhoOf(String compId) {
        if (rho == null) return globalRho; // Backward compatibility with old JSON
        return rho.getOrDefault(compId, globalRho);
    }

    public boolean knows(Long teamId) {
        return attack.containsKey(String.valueOf(teamId));
    }

    public double lambdaHome(Long homeId, Long awayId, String compId) {
        return baseGoalsOf(compId) * Math.exp(attackOf(homeId) - defenseOf(awayId) + homeAdvantageOf(compId));
    }

    public double lambdaAway(Long homeId, Long awayId, String compId) {
        return baseGoalsOf(compId) * Math.exp(attackOf(awayId) - defenseOf(homeId));
    }

    /** Dixon-Coles tau correction for the four low-score cells. */
    public double tau(int x, int y, double lambda, double mu, String compId) {
        double t;
        double r = rhoOf(compId);
        if (x == 0 && y == 0) {
            t = 1 - lambda * mu * r;
        } else if (x == 0 && y == 1) {
            t = 1 + lambda * r;
        } else if (x == 1 && y == 0) {
            t = 1 + mu * r;
        } else if (x == 1 && y == 1) {
            t = 1 - r;
        } else {
            t = 1;
        }
        return Math.max(t, 1e-6);
    }
}
