package dev.obsidian.core.stats;

/**
 * Online mean/variance via Welford's algorithm. Constant memory regardless of how
 * many samples flow through it, which is why checks use this instead of keeping
 * raw histories around.
 *
 * <p>Not thread-safe; each instance is owned by a single player's data container
 * and only touched from the packet pipeline for that player.</p>
 */
public final class WelfordAccumulator {

    private long count;
    private double mean;
    private double m2;

    public void add(double sample) {
        count++;
        double delta = sample - mean;
        mean += delta / count;
        m2 += delta * (sample - mean);
    }

    public long count() {
        return count;
    }

    public double mean() {
        return mean;
    }

    /** Sample variance (n-1 denominator). Zero until two samples exist. */
    public double variance() {
        return count < 2 ? 0.0 : m2 / (count - 1);
    }

    public double stdDev() {
        return Math.sqrt(variance());
    }

    public void reset() {
        count = 0;
        mean = 0.0;
        m2 = 0.0;
    }
}
