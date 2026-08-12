package dev.obsidian.core.ml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureExtractorTest {

    private static final double LIMIT = 3.0;
    private static final double SLOP = 0.1;
    private static final double ANGLE_VIOL = 65.0;

    @Test
    void windowAggregatesReachAngleAndAura() {
        FeatureExtractor fx = new FeatureExtractor();
        long t = 0;
        // Four attacks on players 1,2,3,1 spaced 50ms apart: all reach 3.5 (excess
        // 0.5, a violation), angles 70,70,10,10 (two over the 65 threshold).
        fx.onEntityAttack(true, 3.5, LIMIT, 70f, false, t, 1, ANGLE_VIOL, SLOP);
        fx.onEntityAttack(true, 3.5, LIMIT, 70f, false, t += 50_000_000L, 2, ANGLE_VIOL, SLOP);
        fx.onEntityAttack(true, 3.5, LIMIT, 10f, false, t += 50_000_000L, 3, ANGLE_VIOL, SLOP);
        assertFalse(fx.ready(4), "not ready until the fourth attack");
        fx.onEntityAttack(true, 3.5, LIMIT, 10f, false, t + 50_000_000L, 1, ANGLE_VIOL, SLOP);

        assertTrue(fx.ready(4));
        double[] f = fx.finalizeWindow();

        assertEquals(0.5, f[Features.REACH_MEAN_EXCESS], 1e-9);
        assertEquals(1.0, f[Features.REACH_VIOL_RATIO], 1e-9);
        assertEquals(40.0, f[Features.ANGLE_MEAN], 1e-9);
        assertEquals(0.5, f[Features.ANGLE_VIOL_RATIO], 1e-9);
        assertEquals(3.0, f[Features.MAX_DISTINCT_TARGETS], 1e-9);

        // Window resets after finalizing.
        assertEquals(0, fx.combatActions());
    }

    @Test
    void missingSignalsFallBackToNeutral() {
        FeatureExtractor fx = new FeatureExtractor();
        // One attack with no reach (target not a player) and no angle: those features
        // should stay at their neutral defaults rather than reading as suspicious.
        fx.onEntityAttack(false, -1, LIMIT, -1f, false, 0, 7, ANGLE_VIOL, SLOP);
        double[] f = fx.finalizeWindow();
        assertEquals(Features.NEUTRAL[Features.REACH_MEAN_EXCESS], f[Features.REACH_MEAN_EXCESS], 1e-9);
        assertEquals(Features.NEUTRAL[Features.ANGLE_MEAN], f[Features.ANGLE_MEAN], 1e-9);
        assertEquals(Features.NEUTRAL[Features.CLICK_MEAN_MS], f[Features.CLICK_MEAN_MS], 1e-9);
    }

    @Test
    void clickIntervalsFeedTheClickFeatures() {
        FeatureExtractor fx = new FeatureExtractor();
        // Five swings 100ms apart -> mean interval 100ms, tiny sigma.
        long t = 0;
        for (int i = 0; i < 5; i++) {
            fx.onSwingInCombat(t);
            t += 100_000_000L;
        }
        double[] f = fx.finalizeWindow();
        assertEquals(100.0, f[Features.CLICK_MEAN_MS], 1e-6);
        assertTrue(f[Features.CLICK_SIGMA_MS] < 1e-6, "even intervals should have ~zero sigma");
    }
}
