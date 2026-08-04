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

    private record Row(Long home, Long away, int hg, int ag, double weight, String compId) {
    }

    /** Competitions too thin to fit share this bucket, which becomes the global fallback. */
    static final String POOLED = "*";

    public DixonColesParams fit(List<Fixture> matches, SportPredictProperties.Football cfg, Instant asOf) {
        List<Row> raw = new ArrayList<>(matches.size());
        Set<Long> teamIds = new HashSet<>();
        Map<String, Double> compWeight = new HashMap<>();
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
            String compId = f.getCompetition() != null ? String.valueOf(f.getCompetition().getId()) : "0";
            raw.add(new Row(h, a, f.getHomeScore(), f.getAwayScore(), w, compId));
            teamIds.add(h);
            teamIds.add(a);
            compWeight.merge(compId, w, Double::sum);
            totalWeight += w;
            goals += w * (f.getHomeScore() + f.getAwayScore());
        }

        DixonColesParams params = new DixonColesParams();
        if (raw.isEmpty() || totalWeight <= 0) {
            return params;
        }

        // Thin competitions are relabelled into one pooled bucket before fitting, so their
        // matches still inform the fallback baseline without each getting its own.
        Set<String> ownBaseline = compWeight.entrySet().stream()
                .filter(e -> e.getValue() >= cfg.getMinCompetitionWeight())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        List<Row> rows = raw.stream()
                .map(r -> ownBaseline.contains(r.compId())
                        ? r
                        : new Row(r.home(), r.away(), r.hg(), r.ag(), r.weight(), POOLED))
                .toList();

        Map<String, Double> effectiveWeight = new HashMap<>();
        rows.forEach(r -> effectiveWeight.merge(r.compId(), r.weight(), Double::sum));
        Set<String> compIds = effectiveWeight.keySet();

        // The pooled bucket needs the same guard as any other: pooling one 6-0 cup final
        // and then fitting the pool produces an absurd fallback that every unknown
        // competition would then inherit. Frozen baselines still contribute their matches
        // to the team attack/defence estimates.
        Set<String> updatable = compIds.stream()
                .filter(c -> effectiveWeight.get(c) >= cfg.getMinCompetitionWeight())
                .collect(java.util.stream.Collectors.toSet());

        params.setSampleSize(rows.size());

        Map<Long, Double> attack = new HashMap<>();
        Map<Long, Double> defense = new HashMap<>();
        teamIds.forEach(id -> {
            attack.put(id, 0.0);
            defense.put(id, 0.0);
        });
        Map<String, Double> homeAdv = new HashMap<>();
        Map<String, Double> logBase = new HashMap<>();
        double globalLogBase = Math.log(Math.max(0.3, goals / (2 * totalWeight)));
        for (String c : compIds) {
            homeAdv.put(c, 0.25);
            logBase.put(c, globalLogBase);
        }

        double lr = cfg.getLearningRate();
        double l2 = cfg.getL2();
        for (int iter = 0; iter < cfg.getIterations(); iter++) {
            Map<Long, Double> gAttack = new HashMap<>();
            Map<Long, Double> gDefense = new HashMap<>();
            Map<String, Double> gHome = new HashMap<>();
            Map<String, Double> gBase = new HashMap<>();

            for (Row r : rows) {
                double base = Math.exp(logBase.get(r.compId()));
                double hAdv = homeAdv.get(r.compId());
                double lambda = base * Math.exp(attack.get(r.home()) - defense.get(r.away()) + hAdv);
                double mu = base * Math.exp(attack.get(r.away()) - defense.get(r.home()));
                double dHome = r.weight() * (r.hg() - lambda);
                double dAway = r.weight() * (r.ag() - mu);

                gAttack.merge(r.home(), dHome, Double::sum);
                gDefense.merge(r.away(), -dHome, Double::sum);
                gAttack.merge(r.away(), dAway, Double::sum);
                gDefense.merge(r.home(), -dAway, Double::sum);
                gHome.merge(r.compId(), dHome, Double::sum);
                gBase.merge(r.compId(), dHome + dAway, Double::sum);
            }

            double scale = lr / totalWeight;
            for (Long id : teamIds) {
                double a = attack.get(id) + scale * gAttack.getOrDefault(id, 0.0) - lr * l2 * attack.get(id);
                double d = defense.get(id) + scale * gDefense.getOrDefault(id, 0.0) - lr * l2 * defense.get(id);
                attack.put(id, clamp(a));
                defense.put(id, clamp(d));
            }
            for (String c : updatable) {
                // Scale by the competition's own weight, not the global total. Using the
                // global total makes a league holding 4% of the data take steps 25x too
                // small, so at production learning rates it never leaves its initial value
                // and silently keeps the shared default - defeating the whole split.
                double compScale = lr / effectiveWeight.get(c);
                double h = homeAdv.get(c) + compScale * gHome.getOrDefault(c, 0.0);
                homeAdv.put(c, clamp(h));
                double lb = logBase.get(c) + compScale * gBase.getOrDefault(c, 0.0);
                logBase.put(c, Math.max(Math.log(0.2), Math.min(Math.log(6.0), lb)));
            }

            // Identifiability: attack and defense are only defined up to a shift.
            recenter(attack);
            recenter(defense);
        }

        for (String c : updatable) {
            if (!POOLED.equals(c)) {
                params.getBaseGoals().put(c, Math.exp(logBase.get(c)));
                params.getHomeAdvantage().put(c, homeAdv.get(c));
            }
        }

        // Fallback: the pooled bucket if it was thick enough to fit, otherwise a
        // weight-averaged blend of the fitted competitions - never an unweighted mean,
        // which would let a 40-match cup count the same as a 400-match league.
        Set<String> fallbackSource = updatable.contains(POOLED) ? Set.of(POOLED) : updatable;
        params.setGlobalBaseGoals(weightedAverage(fallbackSource, effectiveWeight,
                c -> Math.exp(logBase.get(c)), 1.35));
        params.setGlobalHomeAdvantage(weightedAverage(fallbackSource, effectiveWeight,
                homeAdv::get, 0.25));
        attack.forEach((k, v) -> params.getAttack().put(String.valueOf(k), v));
        defense.forEach((k, v) -> params.getDefense().put(String.valueOf(k), v));

        bestRho(rows, params, updatable, effectiveWeight, cfg);
        params.setTrainLogLikelihood(logLikelihood(rows, params));
        log.info("Dixon-Coles fit: {} matches, {} teams, {} fitted comps ({} pooled), "
                        + "globalBase={}, globalHomeAdv={}, globalRho={}, ll={}",
                rows.size(), teamIds.size(), params.getBaseGoals().size(),
                compWeight.size() - ownBaseline.size(),
                round(params.getGlobalBaseGoals()), round(params.getGlobalHomeAdvantage()),
                round(params.getGlobalRho()), round(params.getTrainLogLikelihood()));
        return params;
    }

    private void bestRho(List<Row> rows, DixonColesParams params, Set<String> compIds,
                         Map<String, Double> effectiveWeight, SportPredictProperties.Football cfg) {
        int steps = Math.max(2, cfg.getRhoSteps());
        Map<String, Double> fitted = new HashMap<>();

        for (String c : compIds) {
            double best = 0;
            double bestLl = Double.NEGATIVE_INFINITY;
            List<Row> compRows = rows.stream().filter(r -> r.compId().equals(c)).toList();

            for (int i = 0; i < steps; i++) {
                double rho = cfg.getRhoMin() + (cfg.getRhoMax() - cfg.getRhoMin()) * i / (steps - 1.0);
                params.getRho().put(c, rho);
                double ll = logLikelihood(compRows, params);
                if (ll > bestLl) {
                    bestLl = ll;
                    best = rho;
                }
            }
            fitted.put(c, best);
            params.getRho().put(c, best);
        }

        Set<String> fallbackSource = compIds.contains(POOLED) ? Set.of(POOLED) : compIds;
        params.setGlobalRho(weightedAverage(fallbackSource, effectiveWeight, fitted::get, -0.04));
        // The pooled bucket must not keep a per-competition entry, or a thin competition
        // would find it by id instead of falling back.
        params.getRho().remove(POOLED);
    }

    private double weightedAverage(Set<String> keys, Map<String, Double> weights,
                                   java.util.function.Function<String, Double> value, double fallback) {
        double weighted = 0;
        double total = 0;
        for (String key : keys) {
            double w = weights.getOrDefault(key, 0.0);
            weighted += w * value.apply(key);
            total += w;
        }
        return total > 0 ? weighted / total : fallback;
    }

    private double logLikelihood(List<Row> rows, DixonColesParams params) {
        double ll = 0;
        for (Row r : rows) {
            double lambda = params.lambdaHome(r.home(), r.away(), r.compId());
            double mu = params.lambdaAway(r.home(), r.away(), r.compId());
            double tau = params.tau(r.hg(), r.ag(), lambda, mu, r.compId());
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
