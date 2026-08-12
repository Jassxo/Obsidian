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
    /** Entity id of the target crystal, or -1. */
    public int targetEntityId = -1;
    /** Packed block position of the target block, or Long.MIN_VALUE. */
    public long targetBlockPos = Long.MIN_VALUE;
    /** Angle between crosshair and target center at action time, degrees, or -1. */
    public float angleToTarget = -1f;
    /** Hotbar slot for HOTBAR_SWITCH events, else -1. */
    public int hotbarSlot = -1;

    void reset() {
        type = null;
        nanoTime = 0L;
        pingSnapshot = 0;
        targetEntityId = -1;
        targetBlockPos = Long.MIN_VALUE;
        angleToTarget = -1f;
        hotbarSlot = -1;
    }

    public static long packBlockPos(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
