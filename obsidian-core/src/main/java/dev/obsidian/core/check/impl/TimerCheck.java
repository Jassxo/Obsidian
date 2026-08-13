package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Timer / game-speed. A vanilla client ticks 20 times a second, so its movement
 * packets arrive about every 50 ms; a timer cheat runs the client fast and sends
 * them closer together. We keep a running "balance" of how far ahead of the 50 ms
 * cadence the packets are: legit jitter cancels out around zero, a sustained
 * surplus is a client running too fast.
 *
 * <p>Packet-to-packet from the same player, so ping cancels and does not enter the
 * measurement. Stands down under lag/instability (bunched packets during a spike
 * would otherwise look fast), and only ever accuses of going too <i>fast</i> —
 * running slow is not a cheat.</p>
 */
public final class TimerCheck extends Check {

    private static final double TICK_MILLIS = 50.0;

    private double balanceLimitMs;
    private double clampMs;

    public TimerCheck() {
        super("timer", "timer", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        balanceLimitMs = section.getDouble("balance-limit-ms", 160.0);
        clampMs = section.getDouble("balance-clamp-ms", 1000.0);
    }

    private static final class State {
        double balance;
        long lastNanos = -1;
    }

    @Override
    public void onRotation(PlayerData data) {
        State state = data.checkState(slot(), State::new);
        if (data.lagContext.shouldSkip()) {
            // Don't judge timing while the link/server is unreliable; start clean after.
            state.balance = 0;
            state.lastNanos = -1;
            return;
        }
        long now = data.rotations.nanos(0);
        if (state.lastNanos > 0) {
            double deltaMs = (now - state.lastNanos) / 1_000_000.0;
            // Packets faster than a tick (delta < 50ms) push the balance up.
            state.balance += TICK_MILLIS - deltaMs;
            if (state.balance < 0) {
                state.balance = 0; // running slow is fine
            } else if (state.balance > clampMs) {
                state.balance = clampMs;
            }
            if (state.balance > balanceLimitMs) {
                double over = state.balance / balanceLimitMs;
                double strength = Math.min(0.9, 0.5 + 0.4 * (over - 1.0));
                signal(data, strength, "timer balance " + Math.round(state.balance)
                        + "ms over " + Math.round(balanceLimitMs) + "ms (client running fast)");
                state.balance = balanceLimitMs * 0.5; // bleed off so it doesn't spam every packet
            }
        }
        state.lastNanos = now;
    }
}
