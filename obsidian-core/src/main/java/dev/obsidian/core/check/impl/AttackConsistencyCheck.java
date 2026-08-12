package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Autoclicker signature on the click stream while a player is in combat. Three
 * independent tells, the same family the crystal cycle check uses:
 *
 * <ul>
 *   <li>machine-flat variance — a spread of click intervals too tight for a hand,</li>
 *   <li>fixed-delay periodicity — lag-1 autocorrelation near 1,</li>
 *   <li>an impossible sustained rate — a mean interval below what a hand reaches.</li>
 * </ul>
 *
 * <p>Only swings during combat are counted (a recent attack gates it), so normal
 * mining and building do not feed the window. Intervals are packet-to-packet, so
 * only tick stretch is compensated. Experimental and low-weight: fast legit
 * players (jitter/butterfly) are the obvious false-positive source, so this
 * corroborates, it does not convict.</p>
 */
public final class AttackConsistencyCheck extends Check {

    private int sampleWindow;
    private double sigmaFloorMs;
    private double autocorrThreshold;
    private double maxMeanMs;
    private double minIntervalMs;
    private long combatWindowNanos;

    public AttackConsistencyCheck() {
        super("attack-consistency", "autoclicker", true);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        sampleWindow = section.getInt("sample-window", 30);
        sigmaFloorMs = section.getDouble("sigma-floor-ms", 12);
        autocorrThreshold = section.getDouble("autocorrelation-threshold", 0.9);
        maxMeanMs = section.getDouble("max-mean-ms", 300);
        minIntervalMs = section.getDouble("min-interval-ms", 40);
        combatWindowNanos = section.getLong("combat-window-seconds", 5) * 1_000_000_000L;
    }

    private static final class State {
        SampleWindow intervals;
        long lastSwingNanos = -1;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type != ActionType.SWING || data.lagContext.shouldSkip()
                || !data.inCombat(action.nanoTime, combatWindowNanos)) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        if (state.intervals == null) {
            state.intervals = new SampleWindow(sampleWindow);
        }
        if (state.lastSwingNanos > 0) {
            double rawMs = (action.nanoTime - state.lastSwingNanos) / 1_000_000.0;
            if (rawMs < 1000) { // part of a continuous click stream, not a fresh burst
                double comp = data.lagContext.compensateIntervalMillis(rawMs);
                state.intervals.add(comp);
                engine.recordBaseline("click-interval", comp, data);
                evaluate(data, state);
            }
        }
        state.lastSwingNanos = action.nanoTime;
    }

    private void evaluate(PlayerData data, State state) {
        if (!state.intervals.isFull()) {
            return;
        }
        double mean = state.intervals.mean();
        double sigma = state.intervals.stdDev();

        if (mean < minIntervalMs) {
            double strength = Math.min(0.8, 0.5 + (minIntervalMs - mean) / minIntervalMs);
            signal(data, strength, "sustained " + Math.round(1000.0 / Math.max(1, mean))
                    + " cps (mean interval " + Math.round(mean) + "ms)");
        }
        if (sigma < sigmaFloorMs && mean < maxMeanMs) {
            double strength = 0.5 + 0.3 * (1.0 - sigma / sigmaFloorMs);
            signal(data, strength, "click sigma " + Math.round(sigma) + "ms over "
                    + sampleWindow + ", mean " + Math.round(mean) + "ms");
        }
        double autocorr = state.intervals.lag1Autocorrelation();
        if (autocorr > autocorrThreshold && mean < maxMeanMs) {
            signal(data, 0.7, "click autocorrelation " + Math.round(autocorr * 100) / 100.0
                    + " (fixed-delay signature)");
        }
    }
}
