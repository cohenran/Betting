package il.co.sportpredict.model.football;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Turns a pair of goal expectations into the full scoreline distribution and its markets. */
public final class ScoreGrid {

    public record Result(
            double pHome,
            double pDraw,
            double pAway,
            double pOver,
            double pBtts,
            List<Map.Entry<String, Double>> scores
    ) {
        public String topScore() {
            return scores.getFirst().getKey();
        }
    }

    private ScoreGrid() {
    }

    public static Result compute(DixonColesParams params, double lambda, double mu, int maxGoals, double ouLine) {
        double[] ph = poisson(lambda, maxGoals);
        double[] pa = poisson(mu, maxGoals);

        double total = 0;
        double[][] grid = new double[maxGoals + 1][maxGoals + 1];
        for (int x = 0; x <= maxGoals; x++) {
            for (int y = 0; y <= maxGoals; y++) {
                double cell = params.tau(x, y, lambda, mu) * ph[x] * pa[y];
                grid[x][y] = cell;
                total += cell;
            }
        }

        double pHome = 0;
        double pDraw = 0;
        double pAway = 0;
        double pOver = 0;
        double pBtts = 0;
        List<Map.Entry<String, Double>> scores = new ArrayList<>();
        for (int x = 0; x <= maxGoals; x++) {
            for (int y = 0; y <= maxGoals; y++) {
                double prob = grid[x][y] / total;
                if (x > y) {
                    pHome += prob;
                } else if (x == y) {
                    pDraw += prob;
                } else {
                    pAway += prob;
                }
                if (x + y > ouLine) {
                    pOver += prob;
                }
                if (x > 0 && y > 0) {
                    pBtts += prob;
                }
                scores.add(Map.entry(x + "-" + y, prob));
            }
        }
        scores.sort(Comparator.comparingDouble(Map.Entry<String, Double>::getValue).reversed());
        return new Result(pHome, pDraw, pAway, pOver, pBtts, scores);
    }

    private static double[] poisson(double mean, int max) {
        double[] out = new double[max + 1];
        double p = Math.exp(-mean);
        out[0] = p;
        for (int k = 1; k <= max; k++) {
            p = p * mean / k;
            out[k] = p;
        }
        return out;
    }
}
