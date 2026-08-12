package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Attack reach on players. The ingest layer already measured reach the lenient
 * way — the minimum eye-to-hitbox distance across the whole plausible latency
 * window — so a value that still exceeds the allowed limit is not explained by
 * lag or interpolation.
 *
 * <p>The allowed limit is per-attack, computed at ingest from the held weapon
 * and gamemode: a spear or creative mode raises it, so those never false-flag.
 * Ping is handled two ways at once — the latency window widens with the player's
 * ping, and an unstable/spiking connection makes the whole check stand down.</p>
 *
 * <p>Never fires on one hit. A single reach over a hard ceiling contributes a
 * small nudge; the real signal is a sustained fraction of a player's recent hits
 * landing beyond the limit, which no legitimate client produces.</p>
 */
public final class ReachCheck extends Check {

    private double defaultLimit;
    private double slop;
    private double hardSlop;
    private int sampleWindow;
    private int minViolations;
    private double violationRatio;

    public ReachCheck() {
        super("reach", "reach", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        defaultLimit = section.getDouble("default-limit", 3.0);
        slop = section.getDouble("slop", 0.1);
        hardSlop = section.getDouble("hard-slop", 0.6);
        sampleWindow = section.getInt("sample-window", 20);
        minViolations = section.getInt("min-violations", 4);
        violationRatio = section.getDouble("violation-ratio", 0.45);
    }

    private static final class State {
        SampleWindow excesses;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type != ActionType.ENTITY_ATTACK || !action.targetIsPlayer
                || action.reachDistance < 0 || data.lagContext.shouldSkip()) {
            return;
        }
        double limit = action.reachLimit > 0 ? action.reachLimit : defaultLimit;
        double reach = action.reachDistance;

        State state = data.checkState(slot(), State::new);
        if (state.excesses == null) {
            state.excesses = new SampleWindow(sampleWindow);
        }
        // Store reach as excess over this attack's own limit, so a window that
        // mixes spear and normal hits is still compared apples-to-apples.
        state.excesses.add(reach - limit);
        engine.recordBaseline("reach", reach, data);

        // A hit far past the ceiling is worth a small nudge on its own; even here
        // we stay well under a full-strength signal so one weird sample can't convict.
        if (reach > limit + hardSlop) {
            double strength = Math.min(0.4, (reach - (limit + hardSlop)));
            signal(data, strength, "reach " + round(reach) + " past ceiling " + round(limit + hardSlop));
        }

        if (!state.excesses.isFull()) {
            return;
        }
        int violations = 0;
        double excessSum = 0;
        for (int i = 0; i < state.excesses.size(); i++) {
            double excess = state.excesses.get(i);
            if (excess > slop) {
                violations++;
                excessSum += excess - slop;
            }
        }
        double ratio = (double) violations / state.excesses.size();
        if (violations >= minViolations && ratio >= violationRatio) {
            double meanExcess = excessSum / violations;
            double strength = Math.min(1.0, 0.55 + ratio * 0.3 + meanExcess);
            signal(data, strength, violations + "/" + state.excesses.size()
                    + " hits beyond limit+" + round(slop) + " (mean excess " + round(meanExcess) + ")");
            state.excesses.clear();
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
