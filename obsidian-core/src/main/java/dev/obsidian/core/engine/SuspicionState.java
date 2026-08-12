package dev.obsidian.core.engine;

import dev.obsidian.api.Signal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-player suspicion accumulator. Bayesian-style: signals multiply the odds
 * by the emitting check's likelihood ratio (scaled by signal strength), and the
 * accumulated log-odds excess decays exponentially toward the prior so stale
 * evidence fades instead of lingering forever.
 *
 * <p>Mutation happens on the player's packet thread; the displayed confidence
 * is published through a volatile for cross-thread reads (commands, API,
 * placeholders).</p>
 */
public final class SuspicionState {

    /** Prior probability that an arbitrary player is cheating: 1%. */
    private static final double PRIOR_LOG_ODDS = Math.log(0.01 / 0.99);

    /** Accumulated evidence in log-odds space, above the prior. */
    private double logOddsExcess;
    private long lastUpdateNanos = System.nanoTime();

    private final ArrayDeque<Signal> recentSignals = new ArrayDeque<>(32);
    private volatile double publishedConfidence;

    /** Highest confidence seen this session, for the ledger profile. */
    private double sessionMax;

    /**
     * Applies decay for elapsed time, then multiplies odds by ratio^strength.
     * Returns the new confidence 0-100 (before external bonus).
     */
    public double applySignal(double likelihoodRatio, double strength,
                              long nowNanos, double halfLifeSeconds, double externalBonus) {
        decayTo(nowNanos, halfLifeSeconds);
        logOddsExcess += Math.log(likelihoodRatio) * strength;
        return publish(externalBonus);
    }

    /** Decay-only refresh, used when reading confidence without new evidence. */
    public double refresh(long nowNanos, double halfLifeSeconds, double externalBonus) {
        decayTo(nowNanos, halfLifeSeconds);
        return publish(externalBonus);
    }

    private void decayTo(long nowNanos, double halfLifeSeconds) {
        double elapsedSeconds = (nowNanos - lastUpdateNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0 && halfLifeSeconds > 0) {
            logOddsExcess *= Math.pow(0.5, elapsedSeconds / halfLifeSeconds);
        }
        lastUpdateNanos = nowNanos;
    }

    private double publish(double externalBonus) {
        double logOdds = PRIOR_LOG_ODDS + logOddsExcess;
        double odds = Math.exp(logOdds);
        double probability = odds / (1.0 + odds);
        double confidence = Math.min(100.0, probability * 100.0 + externalBonus);
        publishedConfidence = confidence;
        sessionMax = Math.max(sessionMax, confidence);
        return confidence;
    }

    public void pushSignal(Signal signal) {
        synchronized (recentSignals) {
            if (recentSignals.size() >= 32) {
                recentSignals.pollLast();
            }
            recentSignals.addFirst(signal);
        }
    }

    /** Newest-first immutable snapshot. */
    public List<Signal> signalSnapshot() {
        synchronized (recentSignals) {
            return List.copyOf(recentSignals);
        }
    }

    /** Safe from any thread; may be slightly stale (no decay applied). */
    public double confidence() {
        return publishedConfidence;
    }

    public double sessionMax() {
        return sessionMax;
    }
}
