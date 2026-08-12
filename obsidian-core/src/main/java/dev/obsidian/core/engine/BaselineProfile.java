package dev.obsidian.core.engine;

import dev.obsidian.core.stats.P2QuantileEstimator;
import dev.obsidian.core.stats.WelfordAccumulator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide distributions of every metric, built exclusively from players who
 * stay below the baseline confidence cap for their session. This is what lets
 * checks say "faster than P5 of this server's legit population" instead of
 * relying only on absolute thresholds.
 *
 * <p>Constant memory: Welford for mean/variance, P² estimators for the
 * quantiles. No raw samples are ever stored.</p>
 */
public final class BaselineProfile {

    public static final class Metric {
        final WelfordAccumulator welford = new WelfordAccumulator();
        final P2QuantileEstimator p05 = new P2QuantileEstimator(0.05);
        final P2QuantileEstimator p50 = new P2QuantileEstimator(0.50);
        final P2QuantileEstimator p95 = new P2QuantileEstimator(0.95);

        synchronized void add(double sample) {
            welford.add(sample);
            p05.add(sample);
            p50.add(sample);
            p95.add(sample);
        }

        public synchronized long count() {
            return welford.count();
        }

        public synchronized double mean() {
            return welford.mean();
        }

        public synchronized double stdDev() {
            return welford.stdDev();
        }

        public synchronized double p05() {
            return p05.value();
        }

        public synchronized double p50() {
            return p50.value();
        }

        public synchronized double p95() {
            return p95.value();
        }
    }

    /** Baseline percentiles need this many samples before checks may lean on them. */
    public static final long MIN_SAMPLES = 500;

    private final Map<String, Metric> metrics = new ConcurrentHashMap<>();

    /**
     * Records a sample if the contributing player currently looks legit.
     */
    public void record(String metricId, double sample, double playerConfidence, double maxConfidence) {
        if (playerConfidence >= maxConfidence) {
            return;
        }
        metrics.computeIfAbsent(metricId, k -> new Metric()).add(sample);
    }

    /** Null until the first sample for the metric arrives. */
    public Metric metric(String metricId) {
        return metrics.get(metricId);
    }

    /**
     * True when a value sits below the legit population's P5 for the metric and
     * the baseline is mature enough to trust.
     */
    public boolean isBelowP05(String metricId, double value) {
        Metric m = metrics.get(metricId);
        return m != null && m.count() >= MIN_SAMPLES && value < m.p05();
    }

    public long totalSamples() {
        long total = 0;
        for (Metric m : metrics.values()) {
            total += m.count();
        }
        return total;
    }

    public Map<String, Metric> view() {
        return Map.copyOf(metrics);
    }
}
