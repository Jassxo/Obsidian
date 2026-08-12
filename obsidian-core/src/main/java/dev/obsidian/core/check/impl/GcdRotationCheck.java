package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.RotationGcd;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * CheckG — sensitivity-quantum violations, Grim-style, scoped to crystal actions.
 *
 * <p>Every rotation the vanilla client produces is a multiple of a constant
 * derived from the player's sensitivity. We learn that constant from ordinary
 * play (the running GCD of rotation deltas) and then ask: do rotations stop
 * being multiples of it exactly when a crystal action happens? Ordinary play
 * breaking the GCD means a sensitivity change or cinematic camera — noisy but
 * harmless, so it just resets the estimate. Breaks that cluster on crystal
 * actions are a rotation spoof.</p>
 *
 * <p>Experimental by config default, low likelihood ratio, and it needs a long
 * established history before it says anything.</p>
 */
public final class GcdRotationCheck extends Check {

    private int sampleWindow;
    private int minEstablishedSamples;

    public GcdRotationCheck() {
        super("gcd-rotation", "aim", true);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        sampleWindow = section.getInt("sample-window", 30);
        minEstablishedSamples = section.getInt("min-established-samples", 60);
    }

    private static final class State {
        final RotationGcd yawGcd = new RotationGcd();
        final RotationGcd pitchGcd = new RotationGcd();
        int actionsInWindow;
        int violationsInWindow;
        int idleBreakStreak;
    }

    @Override
    public void onRotation(PlayerData data) {
        if (data.rotations.size() < 2) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        float dYaw = data.rotations.deltaYaw(0);
        float dPitch = data.rotations.deltaPitch(0);

        // Learn only from rotations away from crystal actions: the ones a spoof
        // has no reason to fake.
        state.yawGcd.add(dYaw);
        state.pitchGcd.add(dPitch);

        // Repeated breaks during ordinary play = sensitivity change; relearn.
        if (state.yawGcd.samples() > minEstablishedSamples
                && state.yawGcd.isEstimateUsable()
                && !state.yawGcd.isConsistent(dYaw, tolerance(state.yawGcd))) {
            if (++state.idleBreakStreak >= 5) {
                state.yawGcd.reset();
                state.pitchGcd.reset();
                state.idleBreakStreak = 0;
            }
        } else {
            state.idleBreakStreak = 0;
        }
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if ((action.type != ActionType.CRYSTAL_PLACE && action.type != ActionType.CRYSTAL_ATTACK)
                || data.lagContext.shouldSkip() || data.rotations.size() < 2) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        if (state.yawGcd.samples() < minEstablishedSamples
                || !state.yawGcd.isEstimateUsable() || !state.pitchGcd.isEstimateUsable()) {
            return;
        }

        boolean yawBreaks = !state.yawGcd.isConsistent(data.rotations.deltaYaw(0), tolerance(state.yawGcd));
        boolean pitchBreaks = !state.pitchGcd.isConsistent(data.rotations.deltaPitch(0), tolerance(state.pitchGcd));

        state.actionsInWindow++;
        // Both axes off the quantum simultaneously: mouse input can't do that.
        if (yawBreaks && pitchBreaks) {
            state.violationsInWindow++;
        }

        if (state.actionsInWindow >= sampleWindow) {
            double ratio = (double) state.violationsInWindow / state.actionsInWindow;
            if (ratio > 0.5) {
                signal(data, Math.min(1.0, ratio),
                        "GCD broken on " + state.violationsInWindow + "/" + state.actionsInWindow
                                + " crystal actions (both axes)");
            }
            state.actionsInWindow = 0;
            state.violationsInWindow = 0;
        }
    }

    private static long tolerance(RotationGcd gcd) {
        // A tenth of the quantum absorbs float truncation without masking spoofs.
        return Math.max(1, gcd.gcdValue() / 10);
    }
}
