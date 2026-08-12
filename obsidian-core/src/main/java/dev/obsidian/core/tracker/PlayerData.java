package dev.obsidian.core.tracker;

import dev.obsidian.core.engine.SuspicionState;
import dev.obsidian.core.lag.LagContext;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Everything Obsidian knows about one online player. Created on join,
 * destroyed on quit. Owned by the player's netty thread for all hot-path
 * mutation; the few cross-thread reads (confidence display, ping) go through
 * volatile or thread-safe fields.
 */
public final class PlayerData {

    private final UUID uuid;
    private final String name;

    public final PingTracker ping = new PingTracker();
    public final RotationHistory rotations = new RotationHistory(RotationHistory.DEFAULT_CAPACITY);
    public final ActionTimeline timeline = new ActionTimeline(ActionTimeline.DEFAULT_CAPACITY);
    public final LagContext lagContext = new LagContext();
    public final SuspicionState suspicion = new SuspicionState();

    /** crystal entity id -> nanos the spawn packet was sent to THIS player. */
    public final Int2LongOpenHashMap crystalSeenNanos = new Int2LongOpenHashMap(64);

    /**
     * Placement-opportunity onsets: when a crystal this player could see was
     * destroyed, its base block becomes placeable again. Packed block pos +
     * nanos, small fixed ring, written by the ingest layer, consumed by the
     * opportunity-reaction check.
     */
    private final long[] oppPos = new long[16];
    private final long[] oppNanos = new long[16];
    private int oppHead;

    public void recordOpportunity(long packedPos, long nanos) {
        oppPos[oppHead] = packedPos;
        oppNanos[oppHead] = nanos;
        oppHead = (oppHead + 1) % oppPos.length;
    }

    /** Returns onset nanos for a block and consumes the entry, or Long.MIN_VALUE. */
    public long consumeOpportunity(long packedPos) {
        for (int i = 0; i < oppPos.length; i++) {
            if (oppPos[i] == packedPos && oppNanos[i] != 0) {
                long nanos = oppNanos[i];
                oppNanos[i] = 0;
                oppPos[i] = Long.MIN_VALUE;
                return nanos;
            }
        }
        return Long.MIN_VALUE;
    }

    /** True while the player is holding an end crystal in the active hotbar slot. */
    public volatile boolean holdingCrystal;

    /** Last block this player charged with glowstone; disambiguates anchor detonation clicks. */
    public long lastAnchorChargePos = Long.MIN_VALUE;
    public long lastAnchorChargeNanos = -1;

    // Lifecycle timestamps used by the exemption engine (nanos, -1 = never).
    public volatile long joinNanos;
    public volatile long lastTeleportNanos = -1;
    public volatile long lastRespawnNanos = -1;

    /** Last time this player dealt or took damage; drives combat gating. */
    public volatile long lastCombatNanos = -1;

    // Alert cooldowns, owned by the confidence engine. MIN_VALUE = never.
    public long lastSuspiciousAlertNanos = Long.MIN_VALUE;
    public long lastFlagNanos = Long.MIN_VALUE;

    /** Currently held hotbar slot, mirrored from HELD_ITEM_CHANGE packets. */
    public int heldSlot;

    /** Cached exemption bits, refreshed on main-thread events (join, world/mode change). */
    public volatile boolean bypassPermission;
    public volatile boolean exemptGameMode;
    public volatile boolean exemptWorld;

    /** Confidence bonus contributed by external anticheats (Grim bridge), capped in config. */
    public volatile double externalBonus;

    /** Per-check state slots; each check owns one index, allocated once per player. */
    private final Object[] checkState;

    public PlayerData(UUID uuid, String name, int checkSlots) {
        this.uuid = uuid;
        this.name = name;
        this.joinNanos = System.nanoTime();
        this.checkState = new Object[checkSlots];
        this.crystalSeenNanos.defaultReturnValue(Long.MIN_VALUE);
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    /**
     * Lazily creates the per-player state object for a check. Called from the
     * player's own packet thread only, so plain array access is fine.
     */
    @SuppressWarnings("unchecked")
    public <T> T checkState(int slot, Supplier<T> factory) {
        Object state = checkState[slot];
        if (state == null) {
            state = factory.get();
            checkState[slot] = state;
        }
        return (T) state;
    }

    public boolean inCombat(long nowNanos, long windowNanos) {
        long last = lastCombatNanos;
        return last > 0 && nowNanos - last <= windowNanos;
    }
}
