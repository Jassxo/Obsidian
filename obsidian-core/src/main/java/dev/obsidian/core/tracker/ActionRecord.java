package dev.obsidian.core.tracker;

/**
 * One slot in the action timeline ring buffer. Slots are preallocated and
 * overwritten in place — the packet path never allocates one of these.
 */
public final class ActionRecord {

    public ActionType type;
    public long nanoTime;
    /** Player's compensated ping at the moment of the action, millis. */
    public int pingSnapshot;
    /** Entity id of the target crystal or attacked entity, or -1. */
    public int targetEntityId = -1;
    /** Packed block position of the target block, or Long.MIN_VALUE. */
    public long targetBlockPos = Long.MIN_VALUE;
    /** Angle between crosshair and target center at action time, degrees, or -1. */
    public float angleToTarget = -1f;
    /** Hotbar slot for HOTBAR_SWITCH events, else -1. */
    public int hotbarSlot = -1;

    // --- v2: general-combat fields (default to "not applicable") ---
    /**
     * Lenient reach distance eye→target-hitbox for ENTITY_ATTACK, in blocks, or
     * -1. "Lenient" means the minimum over the plausible latency window, so a
     * laggy legit hit never reads as long-reach.
     */
    public double reachDistance = -1;
    /**
     * Effective allowed reach for this attack, in blocks: the vanilla limit
     * raised for a high-reach weapon (spear) or creative mode, computed at ingest
     * where the held item and gamemode are known. -1 when not applicable.
     */
    public double reachLimit = -1;
    /** True when the attacked entity was a player (PvP), for reach/aim gating. */
    public boolean targetIsPlayer;
    /** True when the attack was made with a mace in hand. */
    public boolean withMace;
    /** Player's own vertical velocity at action time, blocks/tick (negative = falling). */
    public double verticalVelocity;

    void reset() {
        type = null;
        nanoTime = 0L;
        pingSnapshot = 0;
        targetEntityId = -1;
        targetBlockPos = Long.MIN_VALUE;
        angleToTarget = -1f;
        hotbarSlot = -1;
        reachDistance = -1;
        targetIsPlayer = false;
        withMace = false;
        verticalVelocity = 0;
    }

    public static long packBlockPos(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
