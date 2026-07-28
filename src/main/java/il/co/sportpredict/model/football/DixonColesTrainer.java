package il.co.sportpredict.model.football;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fixture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Time-weighted maximum-likelihood fit of the Dixon-Coles model by gradient ascent.
 *
 * <p>Attack/defense/home-advantage are fitted on the double-Poisson likelihood (which has
 * clean analytic gradients); rho is then chosen by a 1-D scan over the full corrected
 * likelihood. That split is the standard practical shortcut and avoids a general-purpose
 * optimizer over ~2N+2 parameters.
 *
 * <p>Older matches count less: weight = 0.5^(ageDays / halfLifeDays).
 */
@Component
@Slf4j
public class DixonColesTrainer {

    private record Row(Long home, Long away, int hg, int ag, double weight) {
    }

    public DixonColesParams fit(List<Fixture> matches, SportPredictProperties.Football cfg, Instant asOf) {
        List<Row> rows = new ArrayList<>(matches.size());
        Set<Long> teamIds = new HashSet<>();
        double totalWeight = 0;
        double goals = 0;

        for (Fixture f : matches) {
            if (f.getHomeScore() == null || f.getAwayScore() == null) {
                continue;
            }
            double ageDays = Math.max(0, Duration.between(f.getKickoff(), asOf).toHours() / 24.0);
            double w = Math.pow(0.5, ageDays / cfg.getHalfLifeDays());
            if (w < 1e-4) {
                continue;
            }
            Long h = f.getHomeTeam().getId();
            Long a = f.getAwayTeam().getId();
            rows.add(new Row(h, a, f.getHomeScore(), f.getAwayScore(), w));
            teamIds.add(h);
            teamIds.add(a);
            totalWeight += w;
            goals += w * (f.getHomeScore() + f.getAwayScore());
        }

        DixonColesParams params = new DixonColesParams();
        if (rows.isEmpty() || totalWeight <= 0) {
            return params;
        }

        params.setSampleSize(rows.size());

        Map<Long, Double> attack = new HashMap<>();
        Map<Long, Double> defense = new HashMap<>();
        teamIds.forEach(id -> {
            attack.put(id, 0.0);
            defense.put(id, 0.0);
        });
        double homeAdv = 0.25;
        // The intercept has to be fitted, not set to the observed mean: attack/defense are
        // recentred to mean zero every iteration, so a mis-set intercept would be absorbed
        // by the home-advantage term instead (Jensen's inequality - exp of a zero-mean
        // spread averages above 1).
        double logBase = Math.log(Math.max(0.3, goals / (2 * totalWeight)));

        double lr = cfg.getLearningRate();
        double l2 = cfg.getL2();
        for (int iter = 0; iter < cfg.getIterations(); iter++) {
            Map<Long, Double> gAttack = new HashMap<>();
            Map<Long, Double> gDefense = new HashMap<>();
            double gHome = 0;
            double gBase = 0;
            double base = Math.exp(logBase);

            for (Row r : rows) {
                double lambda = base * Math.exp(attack.get(r.home()) - defense.get(r.away()) + homeAdv);
                double mu = base * Math.exp(attack.get(r.away()) - defense.get(r.home()));
                double dHome = r.weight() * (r.hg() - lambda);
                double dAway = r.weight() * (r.ag() - mu);

                gAttack.merge(r.home(), dHome, Double::sum);
                gDefense.merge(r.away(), -dHome, Double::sum);
                gAttack.merge(r.away(), dAway, Double::sum);
                gDefense.merge(r.home(), -dAway, Double::sum);
                gHome += dHome;
                gBase += dHome + dAway;
            }

            double scale = lr / totalWeight;
            for (Long id : teamIds) {
                double a = attack.get(id) + scale * gAttack.getOrDefault(id, 0.0) - lr * l2 * attack.get(id);
                double d = defense.get(id) + scale * gDefense.getOrDefault(id, 0.0) - lr * l2 * defense.get(id);
                attack.put(id, clamp(a));
                defense.put(id, clamp(d));
            }
            homeAdv = clamp(homeAdv + scale * gHome);
            logBase = Math.max(Math.log(0.2), Math.min(Math.log(6.0), logBase + scale * gBase));

            // Identifiability: attack and defense are only defined up to a shift.
            recenter(attack);
            recenter(defense);
        }

        params.setBaseGoals(Math.exp(logBase));
        params.setHomeAdvantage(homeAdv);
        attack.forEach((k, v) -> params.getAttack().put(String.valueOf(k), v));
        defense.forEach((k, v) -> params.getDefense().put(String.valueOf(k), v));

        params.setRho(bestRho(rows, params, cfg));
        params.setTrainLogLikelihood(logLikelihood(rows, params));
        log.info("Dixon-Coles fit: {} matches, {} teams, base={}, homeAdv={}, rho={}, ll={}",
                rows.size(), teamIds.size(),
                round(params.getBaseGoals()), round(params.getHomeAdvantage()),
                round(params.getRho()), round(params.getTrainLogLikelihood()));
        return params;
    }

    private double bestRho(List<Row> rows, DixonColesParams params, SportPredictProperties.Football cfg) {
        double best = 0;
        double bestLl = Double.NEGATIVE_INFINITY;
        int steps = Math.max(2, cfg.getRhoSteps());
        for (int i = 0; i < steps; i++) {
            double rho = cfg.getRhoMin() + (cfg.getRhoMax() - cfg.getRhoMin()) * i / (steps - 1.0);
            params.setRho(rho);
            double ll = logLikelihood(rows, params);
            if (ll > bestLl) {
                bestLl = ll;
                best = rho;
            }
        }
        return best;
    }

    private double logLikelihood(List<Row> rows, DixonColesParams params) {
        double ll = 0;
        for (Row r : rows) {
            double lambda = params.lambdaHome(r.home(), r.away());
            double mu = params.lambdaAway(r.home(), r.away());
            double tau = params.tau(r.hg(), r.ag(), lambda, mu);
            ll += r.weight() * (Math.log(tau)
                    + r.hg() * Math.log(lambda) - lambda
                    + r.ag() * Math.log(mu) - mu);
        }
        return ll;
    }

    private void recenter(Map<Long, Double> values) {
        double mean = values.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        values.replaceAll((k, v) -> v - mean);
    }

    private double clamp(double v) {
        return Math.max(-2.5, Math.min(2.5, v));
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
