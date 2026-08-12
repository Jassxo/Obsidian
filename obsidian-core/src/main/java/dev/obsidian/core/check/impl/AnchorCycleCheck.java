package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * CheckD — AutoAnchor / anchor macros. Two independent tells:
 *
 * <ul>
 *   <li><b>glowstone place -> anchor detonate interval.</b> A human charges the
 *       anchor, re-aims (or at least re-confirms) and clicks again. Doing both
 *       in under ~90ms compensated, over and over with flat σ, is a macro.</li>
 *   <li><b>hotbar switch -> use timing.</b> AutoAnchor swaps to glowstone and
 *       uses it in the same or next tick, every time, with machine-identical
 *       gaps. Humans need finger travel between the key and the click, and it
 *       varies.</li>
 * </ul>
 *
 * <p>Both series are same-player packet intervals: MSPT-scaled, ping cancels.</p>
 */
public final class AnchorCycleCheck extends Check {

    private static final String METRIC_CYCLE = "anchor-interval";
    private static final String METRIC_SWITCH = "switch-use-interval";

    private int minIntervalMs;
    private int sampleWindow;
    private double sigmaFloorMs;
    private int minSwitchUseMs;

    public AnchorCycleCheck() {
        super("anchor-cycle", "autoanchor", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        minIntervalMs = section.getInt("min-interval-ms", 90);
        sampleWindow = section.getInt("sample-window", 15);
        sigmaFloorMs = section.getDouble("sigma-floor-ms", 20);
        minSwitchUseMs = section.getInt("min-switch-use-ms", 45);
    }

    private static final class State {
        SampleWindow cycles;
        SampleWindow switchUse;
        long lastGlowstoneNanos = -1;
        long lastSwitchNanos = -1;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (data.lagContext.shouldSkip()) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        if (state.cycles == null) {
            state.cycles = new SampleWindow(sampleWindow);
            state.switchUse = new SampleWindow(sampleWindow);
        }

        switch (action.type) {
            case HOTBAR_SWITCH -> state.lastSwitchNanos = action.nanoTime;
            case GLOWSTONE_PLACE -> {
                state.lastGlowstoneNanos = action.nanoTime;
                evaluateSwitchUse(data, state, action.nanoTime);
            }
            case ANCHOR_INTERACT -> {
                evaluateCycle(data, state, action.nanoTime);
                evaluateSwitchUse(data, state, action.nanoTime);
            }
            default -> {
            }
        }
    }

    private void evaluateCycle(PlayerData data, State state, long nowNanos) {
        if (state.lastGlowstoneNanos <= 0) {
            return;
        }
        double rawMs = (nowNanos - state.lastGlowstoneNanos) / 1_000_000.0;
        state.lastGlowstoneNanos = -1;
        if (rawMs > 2000) {
            return;
        }
        double comp = data.lagContext.compensateIntervalMillis(rawMs);
        state.cycles.add(comp);
        engine.recordBaseline(METRIC_CYCLE, comp, data);

        if (comp < minIntervalMs) {
            double strength = Math.min(0.5, (minIntervalMs - comp) / minIntervalMs);
            signal(data, strength, "glowstone->anchor " + Math.round(comp) + "ms comp");
        }
        if (state.cycles.isFull()) {
            double sigma = state.cycles.stdDev();
            if (sigma < sigmaFloorMs && state.cycles.mean() < minIntervalMs * 4) {
                signal(data, 0.5 + 0.4 * (1.0 - sigma / sigmaFloorMs),
                        "anchor sigma " + Math.round(sigma) + "ms over " + sampleWindow);
            }
        }
    }

    private void evaluateSwitchUse(PlayerData data, State state, long nowNanos) {
        if (state.lastSwitchNanos <= 0) {
            return;
        }
        double rawMs = (nowNanos - state.lastSwitchNanos) / 1_000_000.0;
        state.lastSwitchNanos = -1;
        if (rawMs > 1000) {
            return;
        }
        double comp = data.lagContext.compensateIntervalMillis(rawMs);
        state.switchUse.add(comp);
        engine.recordBaseline(METRIC_SWITCH, comp, data);

        // Switch and use inside ~2ms is the same client tick: no finger did that.
        if (comp < 2) {
            signal(data, 0.7, "switch+use same tick (" + Math.round(comp * 10) / 10.0 + "ms)");
            return;
        }
        if (comp < minSwitchUseMs) {
            signal(data, Math.min(0.4, (minSwitchUseMs - comp) / minSwitchUseMs),
                    "switch->use " + Math.round(comp) + "ms comp");
        }
        if (state.switchUse.isFull()) {
            double sigma = state.switchUse.stdDev();
            if (sigma < 8 && state.switchUse.mean() < minSwitchUseMs * 3) {
                signal(data, 0.6, "switch->use sigma " + Math.round(sigma * 10) / 10.0
                        + "ms over " + sampleWindow + " (macro-flat)");
            }
        }
    }
}
