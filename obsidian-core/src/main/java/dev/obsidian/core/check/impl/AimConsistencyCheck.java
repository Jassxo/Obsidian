package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * CheckH — where on the crystal do the hits land?
 *
 * <p>The ingest layer computes the angle between the player's crosshair and the
 * crystal's center at every attack. Humans produce a spread: they click when the
 * crosshair is anywhere on the (large) hitbox, so angles are broadly distributed
 * and skewed away from zero. Cheats that target the exact center or the closest
 * hitbox point produce angles piled up near a constant — a distribution far too
 * tight for hands.</p>
 *
 * <p>Chi-square-flavored: we bin the angles and compare against the "spread out"
 * expectation; a huge statistic with the mass in one bin is the machine shape.
 * Needs 30+ samples before it emits anything, experimental, low weight.</p>
 */
public final class AimConsistencyCheck extends Check {

    private static final int BINS = 6;
    /** Angles above this (deg) mean the shot went through hitbox edge/latency slop. */
    private static final double MAX_ANGLE = 6.0;

    private int minSamples;

    public AimConsistencyCheck() {
        super("aim-consistency", "aim", true);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        minSamples = section.getInt("min-samples", 30);
    }

    private static final class State {
        SampleWindow angles;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type != ActionType.CRYSTAL_ATTACK || action.angleToTarget < 0
                || data.lagContext.shouldSkip()) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        if (state.angles == null) {
            state.angles = new SampleWindow(Math.max(minSamples, 30));
        }
        state.angles.add(Math.min(action.angleToTarget, MAX_ANGLE));
        if (!state.angles.isFull()) {
            return;
        }

        // Bin the window and compute a chi-square statistic against a uniform
        // spread. Uniform is not what humans produce either, but humans are
        // MUCH closer to it than center-lock cheats; the threshold below is
        // set from the cheat side, not the human side.
        int n = state.angles.size();
        int[] observed = new int[BINS];
        for (int i = 0; i < n; i++) {
            int bin = (int) (state.angles.get(i) / MAX_ANGLE * BINS);
            observed[Math.min(bin, BINS - 1)]++;
        }
        double expected = (double) n / BINS;
        double chiSquare = 0;
        int dominantBin = 0;
        for (int i = 0; i < BINS; i++) {
            double d = observed[i] - expected;
            chiSquare += d * d / expected;
            if (observed[i] > observed[dominantBin]) {
                dominantBin = i;
            }
        }

        // >90% of hits in one narrow angular band, chi-square through the roof:
        // that's a locked aim vector, not a hand.
        double dominance = (double) observed[dominantBin] / n;
        if (dominance > 0.9 && chiSquare > n * 3.5) {
            signal(data, Math.min(1.0, dominance),
                    "hit angles locked: " + Math.round(dominance * 100) + "% in one band, chi2 "
                            + Math.round(chiSquare));
            state.angles.clear();
        }
    }
}
