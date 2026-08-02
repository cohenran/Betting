package il.co.sportpredict.model.ufc;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Logistic regression trained by SGD. Kept deliberately small: it is updated once per
 * new fight result (true online learning) and can also be re-run in epochs over history.
 */
@Getter
@Setter
@NoArgsConstructor
public class OnlineLogistic {

    private double[] weights = new double[0];
    private double bias = 0;
    private int updates = 0;

    public OnlineLogistic(int features) {
        this.weights = new double[features];
    }

    public double predict(double[] x) {
        ensureSize(x.length);
        double z = bias;
        for (int i = 0; i < x.length; i++) {
            z += weights[i] * x[i];
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** One SGD step. {@code y} is 1 when the first side won, 0 otherwise. */
    public void update(double[] x, double y, double learningRate, double l2) {
        ensureSize(x.length);
        double error = y - predict(x);
        for (int i = 0; i < x.length; i++) {
            weights[i] += learningRate * (error * x[i] - l2 * weights[i]);
        }
        bias += learningRate * error;
        updates++;
    }

    private void ensureSize(int n) {
        if (weights.length < n) {
            double[] grown = new double[n];
            System.arraycopy(weights, 0, grown, 0, weights.length);
            weights = grown;
        }
    }
}
