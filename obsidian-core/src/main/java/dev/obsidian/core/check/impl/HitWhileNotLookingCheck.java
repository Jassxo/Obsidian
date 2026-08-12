package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Killaura and silent-aim tell: hits that land on a player while the attacker's
 * crosshair points well away from that player. A human has to face what they
 * hit, within the slack the hitbox and latency allow. A rotation-independent
 * aura sends the attack whatever direction the camera happens to face.
 *
 * <p>The angle is measured at attack time between the crosshair and the target
 * center. Close-range hits on a wide hitbox can read fairly high for a moment,
 * so this only speaks up when a sustained fraction of a player's hits land far
 * off-target — not on any single swing.</p>
 */
public final class HitWhileNotLookingCheck extends Check {

    private double maxAngle;
    private int sampleWindow;
    private int minViolations;
    private double violationRatio;

    public HitWhileNotLookingCheck() {
        super("hit-while-not-looking", "killaura", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        maxAngle = section.getDouble("max-angle", 65.0);
        sampleWindow = section.getInt("sample-window", 20);
        minViolations = section.getInt("min-violations", 6);
        violationRatio = section.getDouble("violation-ratio", 0.5);
    }

    private static final class State {
        SampleWindow angles;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type != ActionType.ENTITY_ATTACK || !action.targetIsPlayer
                || action.angleToTarget < 0 || data.lagContext.shouldSkip()) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        if (state.angles == null) {
            state.angles = new SampleWindow(sampleWindow);
        }
        state.angles.add(action.angleToTarget);
        if (!state.angles.isFull()) {
            return;
        }

        int violations = 0;
        double overSum = 0;
        for (int i = 0; i < state.angles.size(); i++) {
            double a = state.angles.get(i);
            if (a > maxAngle) {
                violations++;
                overSum += a - maxAngle;
            }
        }
        double ratio = (double) violations / state.angles.size();
        if (violations >= minViolations && ratio >= violationRatio) {
            double meanOver = overSum / violations;
            double strength = Math.min(1.0, 0.5 + ratio * 0.3 + meanOver / 90.0);
            signal(data, strength, violations + "/" + state.angles.size() + " hits over "
                    + Math.round(maxAngle) + "deg off-target (mean " + Math.round(meanOver + maxAngle) + "deg)");
            state.angles.clear();
        }
    }
}
