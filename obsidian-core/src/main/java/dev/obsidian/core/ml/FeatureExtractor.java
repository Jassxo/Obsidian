package dev.obsidian.core.ml;

import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.stats.WelfordAccumulator;

/**
 * Turns a stretch of one player's combat into a single feature vector. One of
 * these lives per player (inside the ML check's state slot) and is fed the same
 * events the deterministic checks see. When enough combat has accumulated it
 * emits a vector and resets, ready for the next window.
 *
 * <p>All accumulators are constant-memory and single-threaded (the player's
 * packet thread), matching the rest of the pipeline.</p>
 */
public final class FeatureExtractor {

    private static final int RECENT_TARGETS = 8;

    private final WelfordAccumulator reachExcess = new WelfordAccumulator();
    private int reachViolations;
    private int reachSamples;

    private final WelfordAccumulator angle = new WelfordAccumulator();
    private int angleViolations;
    private int angleSamples;

    private final SampleWindow clickIntervals = new SampleWindow(32);
    private long lastSwingNanos = -1;

    private long movingRotations;
    private long microRotations;

    private final int[] targetIds = new int[RECENT_TARGETS];
    private final long[] targetNanos = new long[RECENT_TARGETS];
    private int targetHead;
    private int targetSize;
    private int maxDistinct = 1;

    private final SampleWindow maceCombos = new SampleWindow(16);
    private long lastWindChargeNanos = -1;

    private int combatActions;

    // --- feed methods (called from the ML check's onAction/onRotation) ---

    public void onEntityAttack(boolean targetIsPlayer, double reach, double reachLimit,
                               float angleToTarget, boolean withMace, long now,
                               int targetId, double angleViolationThreshold,
                               double reachSlop) {
        combatActions++;
        if (targetIsPlayer && reach >= 0) {
            double excess = reach - (reachLimit > 0 ? reachLimit : 3.0);
            reachExcess.add(excess);
            reachSamples++;
            if (excess > reachSlop) {
                reachViolations++;
            }
        }
        if (angleToTarget >= 0) {
            angle.add(angleToTarget);
            angleSamples++;
            if (angleToTarget > angleViolationThreshold) {
                angleViolations++;
            }
        }
        if (targetIsPlayer) {
            recordTarget(targetId, now);
        }
        if (withMace && lastWindChargeNanos > 0) {
            double intervalMs = (now - lastWindChargeNanos) / 1_000_000.0;
            if (intervalMs >= 0 && intervalMs <= 1500) {
                maceCombos.add(intervalMs);
            }
            lastWindChargeNanos = -1;
        }
    }

    public void onWindCharge(long now) {
        lastWindChargeNanos = now;
    }

    public void onSwingInCombat(long now) {
        if (lastSwingNanos > 0) {
            double intervalMs = (now - lastSwingNanos) / 1_000_000.0;
            if (intervalMs > 0 && intervalMs < 1000) {
                clickIntervals.add(intervalMs);
            }
        }
        lastSwingNanos = now;
    }

    public void onRotation(double deltaMagnitudeDeg) {
        if (deltaMagnitudeDeg > 0.001) {
            movingRotations++;
            if (deltaMagnitudeDeg < 0.1) {
                microRotations++;
            }
        }
    }

    // --- windowing ---

    public boolean ready(int windowActions) {
        return combatActions >= windowActions;
    }

    public int combatActions() {
        return combatActions;
    }

    /** Builds the vector for the current window and resets for the next one. */
    public double[] finalizeWindow() {
        double[] f = Features.NEUTRAL.clone();

        if (reachSamples > 0) {
            f[Features.REACH_MEAN_EXCESS] = reachExcess.mean();
            f[Features.REACH_VIOL_RATIO] = (double) reachViolations / reachSamples;
        }
        if (angleSamples > 0) {
            f[Features.ANGLE_MEAN] = angle.mean();
            f[Features.ANGLE_VIOL_RATIO] = (double) angleViolations / angleSamples;
        }
        if (clickIntervals.size() > 0) {
            f[Features.CLICK_MEAN_MS] = clickIntervals.mean();
        }
        if (clickIntervals.size() > 1) {
            f[Features.CLICK_SIGMA_MS] = clickIntervals.stdDev();
        }
        if (clickIntervals.size() > 2) {
            f[Features.CLICK_AUTOCORR] = clickIntervals.lag1Autocorrelation();
        }
        if (movingRotations > 0) {
            f[Features.AIM_MICRO_FRACTION] = (double) microRotations / movingRotations;
        }
        f[Features.MAX_DISTINCT_TARGETS] = maxDistinct;
        if (maceCombos.size() >= 3) {
            f[Features.MACE_COMBO_SIGMA_MS] = maceCombos.stdDev();
        }

        reset();
        return f;
    }

    private void recordTarget(int id, long now) {
        targetIds[targetHead] = id;
        targetNanos[targetHead] = now;
        targetHead = (targetHead + 1) % RECENT_TARGETS;
        if (targetSize < RECENT_TARGETS) {
            targetSize++;
        }
        long windowNanos = 250_000_000L;
        int distinct = 0;
        for (int i = 0; i < targetSize; i++) {
            if (now - targetNanos[i] > windowNanos) {
                continue;
            }
            boolean seen = false;
            for (int j = 0; j < i; j++) {
                if (targetIds[j] == targetIds[i] && now - targetNanos[j] <= windowNanos) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                distinct++;
            }
        }
        maxDistinct = Math.max(maxDistinct, distinct);
    }

    private void reset() {
        reachExcess.reset();
        reachViolations = 0;
        reachSamples = 0;
        angle.reset();
        angleViolations = 0;
        angleSamples = 0;
        clickIntervals.clear();
        lastSwingNanos = -1;
        movingRotations = 0;
        microRotations = 0;
        targetHead = 0;
        targetSize = 0;
        maxDistinct = 1;
        maceCombos.clear();
        lastWindChargeNanos = -1;
        combatActions = 0;
    }
}
