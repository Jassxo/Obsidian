package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Auto-mace macros: a wind charge launches the player, and the tool lands the
 * mace smash automatically at the bottom of the fall. The give-away is not the
 * smash itself (skilled players do it by hand) but its <b>timing</b> — the delay
 * from the wind-charge use to the mace hit is machine-regular across repeats,
 * where a human's varies with aim and target movement.
 *
 * <p>Only wind-charge -> mace-hit pairs inside a plausible combo window are
 * measured, both endpoints being the player's own packets, so ping cancels and
 * only tick stretch is compensated. Needs a full window of combos before the
 * consistency tells speak.</p>
 */
public final class MaceSmashCheck extends Check {

    private int sampleWindow;
    private double sigmaFloorMs;
    private double autocorrThreshold;
    private double maxIntervalMs;

    public MaceSmashCheck() {
        super("mace-smash", "mace", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        sampleWindow = section.getInt("sample-window", 10);
        sigmaFloorMs = section.getDouble("sigma-floor-ms", 30);
        autocorrThreshold = section.getDouble("autocorrelation-threshold", 0.9);
        maxIntervalMs = section.getDouble("max-interval-ms", 1500);
    }

    private static final class State {
        SampleWindow intervals;
        long lastWindChargeNanos = -1;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        State state = data.checkState(slot(), State::new);
        if (action.type == ActionType.WIND_CHARGE_USE) {
            state.lastWindChargeNanos = action.nanoTime;
            return;
        }
        if (action.type != ActionType.ENTITY_ATTACK || !action.withMace
                || data.lagContext.shouldSkip()) {
            return;
        }
        if (state.lastWindChargeNanos <= 0) {
            return;
        }
        double rawMs = (action.nanoTime - state.lastWindChargeNanos) / 1_000_000.0;
        state.lastWindChargeNanos = -1; // consume the pairing
        if (rawMs < 0 || rawMs > maxIntervalMs) {
            return; // not part of a wind-charge -> smash combo
        }
        if (state.intervals == null) {
            state.intervals = new SampleWindow(sampleWindow);
        }
        double comp = data.lagContext.compensateIntervalMillis(rawMs);
        state.intervals.add(comp);
        engine.recordBaseline("mace-combo-interval", comp, data);
        if (!state.intervals.isFull()) {
            return;
        }

        double sigma = state.intervals.stdDev();
        double mean = state.intervals.mean();
        if (sigma < sigmaFloorMs) {
            double strength = 0.55 + 0.35 * (1.0 - sigma / sigmaFloorMs);
            signal(data, strength, "wind-charge->mace combo sigma " + Math.round(sigma)
                    + "ms over " + sampleWindow + ", mean " + Math.round(mean) + "ms");
        }
        double autocorr = state.intervals.lag1Autocorrelation();
        if (autocorr > autocorrThreshold) {
            signal(data, 0.75, "mace combo autocorrelation " + Math.round(autocorr * 100) / 100.0
                    + " (fixed-delay macro)");
        }
    }
}
