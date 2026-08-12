package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Multi-target aura: landing hits on several different players inside a window
 * far too short to have aimed at each. A human fighting a group still swings one
 * target at a time, bounded by the attack cooldown and the time it takes to move
 * the crosshair. A kill-aura sweeps every entity in range on the same tick.
 *
 * <p>Packet-to-packet timing, so only tick stretch is compensated (ping delays
 * the player's own packets equally and cancels).</p>
 */
public final class MultiAuraCheck extends Check {

    private static final int RECENT = 8;
    private static final long SIGNAL_COOLDOWN_NANOS = 1_000_000_000L;

    private int windowMs;
    private int minDistinct;

    public MultiAuraCheck() {
        super("multi-aura", "killaura", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        windowMs = section.getInt("window-ms", 250);
        minDistinct = section.getInt("min-distinct-targets", 3);
    }

    private static final class State {
        final int[] ids = new int[RECENT];
        final long[] nanos = new long[RECENT];
        int head;
        int size;
        long lastSignalNanos = Long.MIN_VALUE;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type != ActionType.ENTITY_ATTACK || !action.targetIsPlayer
                || data.lagContext.shouldSkip()) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        state.ids[state.head] = action.targetEntityId;
        state.nanos[state.head] = action.nanoTime;
        state.head = (state.head + 1) % RECENT;
        if (state.size < RECENT) {
            state.size++;
        }

        long windowNanos = (long) (data.lagContext.compensateIntervalMillis(windowMs) * 1_000_000.0);
        int distinct = countDistinctWithin(state, action.nanoTime, windowNanos);
        if (distinct >= minDistinct
                && action.nanoTime - state.lastSignalNanos > SIGNAL_COOLDOWN_NANOS) {
            state.lastSignalNanos = action.nanoTime;
            double strength = Math.min(1.0, 0.5 + 0.2 * (distinct - minDistinct + 1));
            signal(data, strength, distinct + " distinct players hit within "
                    + windowMs + "ms");
        }
    }

    private static int countDistinctWithin(State state, long now, long windowNanos) {
        int distinct = 0;
        for (int i = 0; i < state.size; i++) {
            if (now - state.nanos[i] > windowNanos) {
                continue;
            }
            boolean seen = false;
            for (int j = 0; j < i; j++) {
                if (state.ids[j] == state.ids[i] && now - state.nanos[j] <= windowNanos) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                distinct++;
            }
        }
        return distinct;
    }
}
