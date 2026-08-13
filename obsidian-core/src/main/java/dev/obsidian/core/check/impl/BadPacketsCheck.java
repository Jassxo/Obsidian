package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Protocol validity: rotations a vanilla client can never send. The client
 * clamps pitch to [-90, 90] and never emits NaN; a value outside that came from
 * a modified client, not a fast hand. This is a hard, near-zero-false-positive
 * tell and does not depend on any timing — so it stands apart from the
 * speed-based checks and gives the corroboration gate an independent voice.
 *
 * <p>Independent of lag by nature (an invalid value is invalid whether the
 * server is lagging or not), so it does not skip under lag. A per-second cooldown
 * keeps a stuck client from flooding identical signals.</p>
 */
public final class BadPacketsCheck extends Check {

    private static final long SIGNAL_COOLDOWN_NANOS = 1_000_000_000L;

    private double strength;

    public BadPacketsCheck() {
        super("bad-packets", "badpackets", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        strength = section.getDouble("strength", 0.85);
    }

    private static final class State {
        long lastSignalNanos = Long.MIN_VALUE;
    }

    @Override
    public void onRotation(PlayerData data) {
        if (data.rotations.size() == 0) {
            return;
        }
        float pitch = data.rotations.pitch(0);
        float yaw = data.rotations.yaw(0);
        boolean invalid = Float.isNaN(pitch) || Float.isNaN(yaw)
                || Float.isInfinite(pitch) || Float.isInfinite(yaw)
                || Math.abs(pitch) > 90.0001f;
        if (!invalid) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        long now = data.rotations.nanos(0);
        if (now - state.lastSignalNanos < SIGNAL_COOLDOWN_NANOS) {
            return;
        }
        state.lastSignalNanos = now;
        signal(data, strength, "invalid rotation pitch=" + round(pitch) + " yaw=" + round(yaw));
    }

    private static double round(float v) {
        return Math.round(v * 100) / 100.0;
    }
}
