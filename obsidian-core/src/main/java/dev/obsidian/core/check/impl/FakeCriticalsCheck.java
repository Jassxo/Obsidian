package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Forced criticals. A real critical needs the attacker to be falling through the
 * air (downward velocity, off the ground); a "Criticals" cheat fakes that by
 * micro-hopping — a tiny upward blip, far smaller than a real jump — synced to
 * every hit. Individually a hit at any velocity is fine; the tell is a sustained
 * majority of a player's hits landing during that tiny artificial hop, which a
 * hand fighting normally never produces.
 *
 * <p>Uses the vertical velocity already captured at attack time. Experimental and
 * distribution-based: it corroborates, and the engine's multi-family gate means
 * it can never flag on its own.</p>
 */
public final class FakeCriticalsCheck extends Check {

    // A real jump launches at ~0.42 blocks/tick; a forced-crit hop is a fraction of that.
    private double minHopVelocity;
    private double maxHopVelocity;
    private int sampleWindow;
    private double hopRatioThreshold;

    public FakeCriticalsCheck() {
        super("fake-criticals", "criticals", true);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        minHopVelocity = section.getDouble("min-hop-velocity", 0.02);
        maxHopVelocity = section.getDouble("max-hop-velocity", 0.20);
        sampleWindow = section.getInt("sample-window", 25);
        hopRatioThreshold = section.getDouble("hop-ratio-threshold", 0.65);
    }

    private static final class State {
        SampleWindow hops;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type != ActionType.ENTITY_ATTACK || !action.targetIsPlayer
                || data.lagContext.shouldSkip()) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        if (state.hops == null) {
            state.hops = new SampleWindow(sampleWindow);
        }
        double vy = action.verticalVelocity;
        boolean fakeHop = vy > minHopVelocity && vy < maxHopVelocity;
        state.hops.add(fakeHop ? 1.0 : 0.0);
        if (!state.hops.isFull()) {
            return;
        }
        double ratio = state.hops.mean();
        if (ratio >= hopRatioThreshold) {
            double strength = Math.min(0.8, 0.4 + (ratio - hopRatioThreshold));
            signal(data, strength, Math.round(ratio * 100) + "% of hits during a fake-crit hop over "
                    + sampleWindow);
            state.hops.clear();
        }
    }
}
