package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * CheckB — the full crystal cycle: attack -> place -> attack. Three things give
 * a machine away here:
 *
 * <ul>
 *   <li>a cycle floor no hand can sustain (two aimed clicks plus a placement
 *       under ~120ms, repeatedly),</li>
 *   <li>machine-flat σ across cycles,</li>
 *   <li>perfect periodicity: a module on a fixed delay produces near-identical
 *       intervals, which shows up as lag-1 autocorrelation near 1. Human cycle
 *       times wander with target movement, totem pops and repositioning.</li>
 * </ul>
 *
 * <p>Cycle intervals are packet-to-packet from the same player, so ping cancels
 * and only MSPT stretch is compensated (see LagContext.compensateIntervalMillis).</p>
 */
public final class PlaceBreakCycleCheck extends Check {

    private static final String METRIC = "cycle-interval";

    private int minCycleMs;
    private int sampleWindow;
    private double sigmaFloorMs;
    private double autocorrThreshold;

    public PlaceBreakCycleCheck() {
        super("place-break-cycle", "autocrystal", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        minCycleMs = section.getInt("min-cycle-ms", 120);
        sampleWindow = section.getInt("sample-window", 20);
        sigmaFloorMs = section.getDouble("sigma-floor-ms", 20);
        autocorrThreshold = section.getDouble("autocorrelation-threshold", 0.95);
    }

    private static final class State {
        SampleWindow cycles;
        long lastCycleAttackNanos = -1;
        boolean placedSinceAttack;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (data.lagContext.shouldSkip()) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        if (state.cycles == null) {
            state.cycles = new SampleWindow(sampleWindow);
        }

        if (action.type == ActionType.CRYSTAL_PLACE) {
            state.placedSinceAttack = state.lastCycleAttackNanos > 0;
            return;
        }
        if (action.type != ActionType.CRYSTAL_ATTACK) {
            return;
        }

        if (state.placedSinceAttack && state.lastCycleAttackNanos > 0) {
            double rawMs = (action.nanoTime - state.lastCycleAttackNanos) / 1_000_000.0;
            // A "cycle" that spans seconds is two separate fights, not a cycle.
            if (rawMs < 3000) {
                double comp = data.lagContext.compensateIntervalMillis(rawMs);
                state.cycles.add(comp);
                engine.recordBaseline(METRIC, comp, data);
                evaluate(data, state, comp);
            }
        }
        state.lastCycleAttackNanos = action.nanoTime;
        state.placedSinceAttack = false;
    }

    private void evaluate(PlayerData data, State state, double latest) {
        if (latest < minCycleMs) {
            double strength = Math.min(0.5, (minCycleMs - latest) / minCycleMs);
            signal(data, strength, "cycle " + Math.round(latest) + "ms comp");
        }
        if (!state.cycles.isFull()) {
            return;
        }
        double sigma = state.cycles.stdDev();
        double mean = state.cycles.mean();
        if (sigma < sigmaFloorMs && mean < minCycleMs * 4) {
            double strength = 0.5 + 0.4 * (1.0 - sigma / sigmaFloorMs);
            signal(data, strength, "cycle sigma " + Math.round(sigma) + "ms over "
                    + sampleWindow + ", mean " + Math.round(mean) + "ms");
        }
        double autocorr = state.cycles.lag1Autocorrelation();
        if (autocorr > autocorrThreshold) {
            signal(data, 0.8, "cycle autocorrelation " + Math.round(autocorr * 100) / 100.0
                    + " over " + sampleWindow + " (fixed-delay signature)");
        }
    }
}
