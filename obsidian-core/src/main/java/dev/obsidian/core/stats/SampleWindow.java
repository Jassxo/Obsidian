package dev.obsidian.core.stats;

/**
 * Fixed-capacity ring of double samples with cheap mean/sigma/lag-1
 * autocorrelation over the current contents. Preallocated once per check per
 * player; nothing is allocated when samples flow through.
 */
public final class SampleWindow {

    private final double[] samples;
    private int head;
    private int size;

    public SampleWindow(int capacity) {
        this.samples = new double[capacity];
    }

    public void add(double sample) {
        samples[head] = sample;
        head = (head + 1) % samples.length;
        if (size < samples.length) {
            size++;
        }
    }

    public int size() {
        return size;
    }

    public boolean isFull() {
        return size == samples.length;
    }

    public double mean() {
        if (size == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            sum += get(i);
        }
        return sum / size;
    }

    public double stdDev() {
        if (size < 2) {
            return 0.0;
        }
        double mean = mean();
        double sq = 0.0;
        for (int i = 0; i < size; i++) {
            double d = get(i) - mean;
            sq += d * d;
        }
        return Math.sqrt(sq / (size - 1));
    }

    /**
     * Lag-1 autocorrelation of the window contents in insertion order.
     *
     * <p>A cheat firing on a fixed delay produces near-identical consecutive
     * intervals: the series barely varies, and what variation exists repeats,
     * pushing this toward 1. Human interval series are noisy and hover near 0.</p>
     *
     * @return value in [-1, 1], or 0 if fewer than 3 samples
     */
    public double lag1Autocorrelation() {
        if (size < 3) {
            return 0.0;
        }
        double mean = mean();
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < size; i++) {
            double d = get(i) - mean;
            den += d * d;
            if (i > 0) {
                num += d * (get(i - 1) - mean);
            }
        }
        if (den == 0.0) {
            // A perfectly flat series is the most machine-like outcome there is.
            return 1.0;
        }
        return num / den;
    }

    public double min() {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            min = Math.min(min, get(i));
        }
        return size == 0 ? 0.0 : min;
    }

    /** Oldest-first access, index 0 = oldest sample currently held. */
    public double get(int index) {
        int start = (head - size + samples.length * 2) % samples.length;
        return samples[(start + index) % samples.length];
    }

    public void clear() {
        head = 0;
        size = 0;
    }
}
