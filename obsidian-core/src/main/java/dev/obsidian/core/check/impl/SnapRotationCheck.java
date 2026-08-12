package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * CheckF — snap-and-return rotations around crystal actions.
 *
 * <p>The rotation-assist signature is a triangle wave: one huge delta onto the
 * target, the action fires between the two rotation packets, then a mirror-image
 * delta straight back to where the player was actually looking. Humans can flick
 * fast, but a flick decelerates into the target with overshoot and micro
 * corrections, and the return path is never a pixel-perfect inverse.</p>
 *
 * <p>Two sub-signals: the mirrored snap itself, and a sterile approach — five
 * pre-snap rotations containing zero sub-0.1° adjustments. Real mouse movement
 * at speed always carries that micro jitter; its complete absence means the
 * rotations were synthesized.</p>
 */
public final class SnapRotationCheck extends Check {

    private double minSnapDegrees;

    public SnapRotationCheck() {
        super("snap-rotation", "aim", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        minSnapDegrees = section.getDouble("min-snap-degrees", 25.0);
    }

    private static final class State {
        long lastCrystalActionNanos = -1;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type == ActionType.CRYSTAL_PLACE || action.type == ActionType.CRYSTAL_ATTACK) {
            data.<State>checkState(slot(), State::new).lastCrystalActionNanos = action.nanoTime;
        }
    }

    @Override
    public void onRotation(PlayerData data) {
        if (data.lagContext.shouldSkip() || data.rotations.size() < 8) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        if (state.lastCrystalActionNanos <= 0) {
            return;
        }
        // The action must have landed between the previous rotation and this one.
        long prevRotNanos = data.rotations.nanos(1);
        long thisRotNanos = data.rotations.nanos(0);
        if (state.lastCrystalActionNanos < prevRotNanos
                || state.lastCrystalActionNanos > thisRotNanos) {
            return;
        }
        state.lastCrystalActionNanos = -1;

        float toTargetYaw = data.rotations.deltaYaw(1);
        float toTargetPitch = data.rotations.deltaPitch(1);
        float returnYaw = data.rotations.deltaYaw(0);
        float returnPitch = data.rotations.deltaPitch(0);

        double snapMagnitude = Math.hypot(toTargetYaw, toTargetPitch);
        double returnMagnitude = Math.hypot(returnYaw, returnPitch);
        if (snapMagnitude < minSnapDegrees || returnMagnitude < minSnapDegrees * 0.6) {
            return;
        }

        // How close is the return to a perfect inverse of the snap?
        double residual = Math.hypot(toTargetYaw + returnYaw, toTargetPitch + returnPitch);
        double mirrorError = residual / snapMagnitude;
        if (mirrorError > 0.15) {
            return; // messy return = human flick-and-correct
        }

        double strength = 0.5 + 0.3 * (1.0 - mirrorError / 0.15);

        // Sterile approach: no micro-corrections in the rotations before the snap.
        int microCorrections = 0;
        for (int i = 2; i < Math.min(7, data.rotations.size()); i++) {
            double mag = Math.hypot(data.rotations.deltaYaw(i), data.rotations.deltaPitch(i));
            if (mag > 0.001 && mag < 0.1) {
                microCorrections++;
            }
        }
        if (microCorrections == 0) {
            strength = Math.min(1.0, strength + 0.2);
        }

        signal(data, strength, "snap " + Math.round(snapMagnitude) + "deg -> action -> return, mirror error "
                + Math.round(mirrorError * 100) + "%, micro-corrections " + microCorrections);
    }
}
