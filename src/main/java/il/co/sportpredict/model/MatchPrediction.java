package il.co.sportpredict.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Model output for one event. For UFC, {@code pHome} is fighter A, {@code pAway} is
 * fighter B and {@code pDraw} is the (small) draw/no-contest probability.
 */
public record MatchPrediction(
        String model,
        String version,
        double pHome,
        double pDraw,
        double pAway,
        Double expectedHome,
        Double expectedAway,
        Double ouLine,
        Double pOver,
        Double pBtts,
        String topScore,
        double confidence,
        Map<String, Object> detail
) {

    /** HOME / DRAW / AWAY - whichever the model rates highest. */
    public String pick() {
        if (pHome >= pDraw && pHome >= pAway) {
            return "HOME";
        }
        return pAway >= pDraw ? "AWAY" : "DRAW";
    }

    public double pickProbability() {
        return Math.max(pHome, Math.max(pDraw, pAway));
    }

    /**
     * 1 - normalized entropy: 0 when the three outcomes are equally likely, 1 when the
     * model is certain. More honest than "highest probability" for 1X2 markets.
     */
    public static double confidenceOf(double pHome, double pDraw, double pAway) {
        double[] ps = {pHome, pDraw, pAway};
        int outcomes = 0;
        double entropy = 0;
        for (double p : ps) {
            if (p > 0) {
                entropy -= p * Math.log(p);
                outcomes++;
            }
        }
        if (outcomes < 2) {
            return 1.0;
        }
        double max = Math.log(outcomes);
        return Math.max(0.0, Math.min(1.0, 1.0 - entropy / max));
    }

    /** Mutable, insertion-ordered detail map for predictors to fill in. */
    public static Map<String, Object> newDetail() {
        return new LinkedHashMap<>();
    }
}
