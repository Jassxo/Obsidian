package dev.obsidian.core.stats;

/**
 * P-square (P²) streaming quantile estimator (Jain &amp; Chlamtac, 1985).
 * Tracks a single quantile with five markers and no sample storage, so the
 * server-wide baseline can hold P5/P50/P95 of every metric for free.
 *
 * <p>Not thread-safe.</p>
 */
public final class P2QuantileEstimator {

    private final double p;
    private final double[] q = new double[5];      // marker heights
    private final int[] n = new int[5];            // marker positions
    private final double[] np = new double[5];     // desired positions
    private final double[] dn = new double[5];     // desired position increments
    private int count;

    public P2QuantileEstimator(double quantile) {
        if (quantile <= 0.0 || quantile >= 1.0) {
            throw new IllegalArgumentException("quantile must be in (0,1): " + quantile);
        }
        this.p = quantile;
    }

    public void add(double sample) {
        if (count < 5) {
            q[count++] = sample;
            if (count == 5) {
                java.util.Arrays.sort(q);
                for (int i = 0; i < 5; i++) {
                    n[i] = i + 1;
                }
                np[0] = 1;
                np[1] = 1 + 2 * p;
                np[2] = 1 + 4 * p;
                np[3] = 3 + 2 * p;
                np[4] = 5;
                dn[0] = 0;
                dn[1] = p / 2;
                dn[2] = p;
                dn[3] = (1 + p) / 2;
                dn[4] = 1;
            }
            return;
        }

        int k;
        if (sample < q[0]) {
            q[0] = sample;
            k = 0;
        } else if (sample < q[1]) {
            k = 0;
        } else if (sample < q[2]) {
            k = 1;
        } else if (sample < q[3]) {
            k = 2;
        } else if (sample <= q[4]) {
            k = 3;
        } else {
            q[4] = sample;
            k = 3;
        }

        for (int i = k + 1; i < 5; i++) {
            n[i]++;
        }
        for (int i = 0; i < 5; i++) {
            np[i] += dn[i];
        }

        for (int i = 1; i <= 3; i++) {
            double d = np[i] - n[i];
            if ((d >= 1 && n[i + 1] - n[i] > 1) || (d <= -1 && n[i - 1] - n[i] < -1)) {
                int sign = d >= 0 ? 1 : -1;
                double parabolic = parabolic(i, sign);
                if (q[i - 1] < parabolic && parabolic < q[i + 1]) {
                    q[i] = parabolic;
                } else {
                    q[i] = linear(i, sign);
                }
                n[i] += sign;
            }
        }
        count++;
    }

    private double parabolic(int i, int sign) {
        return q[i] + (double) sign / (n[i + 1] - n[i - 1]) * (
                (n[i] - n[i - 1] + sign) * (q[i + 1] - q[i]) / (n[i + 1] - n[i])
                        + (n[i + 1] - n[i] - sign) * (q[i] - q[i - 1]) / (n[i] - n[i - 1]));
    }

    private double linear(int i, int sign) {
        return q[i] + sign * (q[i + sign] - q[i]) / (n[i + sign] - n[i]);
    }

    /** Current estimate. Falls back to a sorted-sample quantile until 5 samples exist. */
    public double value() {
        if (count >= 5) {
            return q[2];
        }
        if (count == 0) {
            return 0.0;
        }
        double[] copy = java.util.Arrays.copyOf(q, count);
        java.util.Arrays.sort(copy);
        int idx = (int) Math.round(p * (count - 1));
        return copy[idx];
    }

    public long samples() {
        return count >= 5 ? count : count;
    }
}
