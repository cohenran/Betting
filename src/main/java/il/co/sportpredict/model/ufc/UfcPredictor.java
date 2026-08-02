package il.co.sportpredict.model.ufc;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fight;
import il.co.sportpredict.domain.Fighter;
import il.co.sportpredict.model.MatchPrediction;
import il.co.sportpredict.model.ModelStateStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UFC / MMA fight winner probability from a logistic model over fighter differentials
 * (Elo, physical measurements, record, striking and grappling rates).
 *
 * <p>Missing stats become 0 - i.e. "no difference" - so a fight between two fighters we
 * only know the Elo of still produces a sane number.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UfcPredictor {

    public static final String MODEL = "ufc-logistic";
    public static final String STATE_KEY = "ufc-logistic";

    static final String[] FEATURES = {
            "eloDiff", "heightDiff", "reachDiff", "ageDiff", "winPctDiff",
            "streakDiff", "strikesDiff", "accuracyDiff", "takedownDiff", "submissionDiff",
            "experienceDiff", "titleFight"
    };

    /** Draws and no-contests are rare; reserve a flat slice for them. */
    private static final double DRAW_PROBABILITY = 0.005;

    private final ModelStateStore store;
    private final SportPredictProperties props;

    private volatile OnlineLogistic model = prior();

    /**
     * Untrained starting point with a weight of 1.0 on the Elo differential, so a fresh
     * install already predicts something sensible (200 Elo edge ~ 73%) instead of 50/50.
     */
    public static OnlineLogistic prior() {
        OnlineLogistic m = new OnlineLogistic(FEATURES.length);
        m.getWeights()[0] = 1.0;
        return m;
    }

    @PostConstruct
    void loadState() {
        store.load(STATE_KEY, OnlineLogistic.class).ifPresent(m -> {
            model = m;
            log.info("loaded UFC logistic model ({} updates)", m.getUpdates());
        });
    }

    public OnlineLogistic model() {
        return model;
    }

    public void replaceModel(OnlineLogistic replacement) {
        this.model = replacement;
    }

    public MatchPrediction predict(Fight fight) {
        double[] x = features(fight.getFighterA(), fight.getFighterB(), fight.getFightDate(), fight.isTitleFight());
        double pA = model.predict(x);
        double remaining = 1.0 - DRAW_PROBABILITY;
        double pFirst = pA * remaining;
        double pSecond = (1 - pA) * remaining;

        Map<String, Object> detail = new LinkedHashMap<>();
        Map<String, Object> featureMap = new LinkedHashMap<>();
        for (int i = 0; i < FEATURES.length; i++) {
            featureMap.put(FEATURES[i], round(x[i]));
        }
        detail.put("features", featureMap);
        detail.put("updates", model.getUpdates());
        detail.put("eloA", round(fight.getFighterA().getElo()));
        detail.put("eloB", round(fight.getFighterB().getElo()));
        detail.put("recordA", record(fight.getFighterA()));
        detail.put("recordB", record(fight.getFighterB()));

        return new MatchPrediction(
                MODEL,
                "u" + model.getUpdates(),
                pFirst, DRAW_PROBABILITY, pSecond,
                null, null, null, null, null,
                null,
                MatchPrediction.confidenceOf(pFirst, DRAW_PROBABILITY, pSecond),
                detail);
    }

    /** Feature vector, A minus B, scaled so every entry sits roughly in [-2, 2]. */
    public double[] features(Fighter a, Fighter b, Instant when, boolean titleFight) {
        double[] x = new double[FEATURES.length];
        x[0] = (a.getElo() - b.getElo()) / 200.0;
        x[1] = diff(a.getHeightCm(), b.getHeightCm()) / 10.0;
        x[2] = diff(a.getReachCm(), b.getReachCm()) / 10.0;
        x[3] = (age(a, when) - age(b, when)) / 5.0;
        x[4] = winPct(a) - winPct(b);
        x[5] = (a.getWinStreak() - b.getWinStreak()) / 3.0;
        x[6] = diff(a.getStrikesPerMin(), b.getStrikesPerMin()) / 2.0;
        x[7] = diff(a.getStrikeAccuracy(), b.getStrikeAccuracy());
        x[8] = diff(a.getTakedownsAvg(), b.getTakedownsAvg()) / 2.0;
        x[9] = diff(a.getSubmissionsAvg(), b.getSubmissionsAvg());
        x[10] = (fights(a) - fights(b)) / 10.0;
        x[11] = titleFight ? 1 : 0;
        return x;
    }

    public void learn(Fight fight) {
        if (!fight.hasResult()) {
            return;
        }
        double[] x = features(fight.getFighterA(), fight.getFighterB(), fight.getFightDate(), fight.isTitleFight());
        double y = fight.getWinner().getId().equals(fight.getFighterA().getId()) ? 1.0 : 0.0;
        model.update(x, y, props.getModel().getUfc().getLearningRate(), props.getModel().getUfc().getL2());
    }

    private double diff(Double a, Double b) {
        if (a == null || b == null) {
            return 0;
        }
        return a - b;
    }

    private double age(Fighter f, Instant when) {
        if (f.getDateOfBirth() == null) {
            return 30;
        }
        LocalDate at = when.atZone(ZoneOffset.UTC).toLocalDate();
        return ChronoUnit.DAYS.between(f.getDateOfBirth(), at) / 365.25;
    }

    private double winPct(Fighter f) {
        int total = fights(f);
        return total == 0 ? 0.5 : (double) f.getWins() / total;
    }

    private int fights(Fighter f) {
        return f.getWins() + f.getLosses() + f.getDraws();
    }

    private String record(Fighter f) {
        return f.getWins() + "-" + f.getLosses() + (f.getDraws() > 0 ? "-" + f.getDraws() : "");
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
