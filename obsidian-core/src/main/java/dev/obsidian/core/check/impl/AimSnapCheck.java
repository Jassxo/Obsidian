package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Flick-aimbot signature on player attacks: a huge rotation delta onto the
 * target with the hit landing between two rotation packets, then a near-perfect
 * mirror-image delta straight back to where the camera was already pointing.
 * Humans flick too, but a real flick decelerates into the target with overshoot
 * and micro-corrections, and the return is never a pixel-perfect inverse.
 *
 * <p>This is the entity-attack counterpart of the crystal snap-rotation check.
 * A second tell strengthens it: a sterile approach — several pre-snap rotations
 * with no sub-0.1 degree jitter, which real mouse movement always carries.</p>
 */
public final class AimSnapCheck extends Check {

    private double minSnapDegrees;

    public AimSnapCheck() {
        super("aim-snap", "aim", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        minSnapDegrees = section.getDouble("min-snap-degrees", 30.0);
    }

    private static final class State {
        long lastAttackNanos = -1;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type == ActionType.ENTITY_ATTACK && action.targetIsPlayer) {
            data.<State>checkState(slot(), State::new).lastAttackNanos = action.nanoTime;
        }
    }

    @Override
    public void onRotation(PlayerData data) {
        if (data.lagContext.shouldSkip() || data.rotations.size() < 8) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        if (state.lastAttackNanos <= 0) {
            return;
        }
        long prevRotNanos = data.rotations.nanos(1);
        long thisRotNanos = data.rotations.nanos(0);
        if (state.lastAttackNanos < prevRotNanos || state.lastAttackNanos > thisRotNanos) {
            return;
        }
        state.lastAttackNanos = -1;

        float toTargetYaw = data.rotations.deltaYaw(1);
        float toTargetPitch = data.rotations.deltaPitch(1);
        float returnYaw = data.rotations.deltaYaw(0);
        float returnPitch = data.rotations.deltaPitch(0);

        double snapMagnitude = Math.hypot(toTargetYaw, toTargetPitch);
        double returnMagnitude = Math.hypot(returnYaw, returnPitch);
        if (snapMagnitude < minSnapDegrees || returnMagnitude < minSnapDegrees * 0.6) {
            return;
        }

        double residual = Math.hypot(toTargetYaw + returnYaw, toTargetPitch + returnPitch);
        double mirrorError = residual / snapMagnitude;
        if (mirrorError > 0.15) {
            return; // messy return = human flick-and-correct
        }
        double strength = 0.5 + 0.3 * (1.0 - mirrorError / 0.15);

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

        signal(data, strength, "snap " + Math.round(snapMagnitude) + "deg -> hit -> return, mirror error "
                + Math.round(mirrorError * 100) + "%, micro-corrections " + microCorrections);
    }
}
