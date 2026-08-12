package dev.obsidian.core.tracker;

/**
 * Ring buffer of the last {@code capacity} typed actions for one player.
 * All slots are preallocated; {@link #begin(ActionType, long, int)} recycles
 * the oldest slot instead of allocating.
 *
 * <p>Single-writer (the player's netty thread); checks run inline on that same
 * thread right after ingestion, so no synchronization is needed.</p>
 */
public final class ActionTimeline {

    public static final int DEFAULT_CAPACITY = 200;

    private final ActionRecord[] ring;
    private int head;
    private int size;

    public ActionTimeline(int capacity) {
        ring = new ActionRecord[capacity];
        for (int i = 0; i < capacity; i++) {
            ring[i] = new ActionRecord();
        }
    }

    /**
     * Claims the next slot, stamps the common fields and returns it for the
     * caller to fill in type-specific fields.
     */
    public ActionRecord begin(ActionType type, long nanoTime, int pingSnapshot) {
        ActionRecord slot = ring[head];
        slot.reset();
        slot.type = type;
        slot.nanoTime = nanoTime;
        slot.pingSnapshot = pingSnapshot;
        head = (head + 1) % ring.length;
        if (size < ring.length) {
            size++;
        }
        return slot;
    }

    public int size() {
        return size;
    }

    /** index 0 = most recent action, increasing = older. */
    public ActionRecord latest(int index) {
        if (index >= size) {
            return null;
        }
        return ring[(head - 1 - index + ring.length * 2) % ring.length];
    }

    /**
     * Most recent action of a given type at or before the given index depth,
     * or null if none is retained.
     */
    public ActionRecord latestOfType(ActionType type, int startIndex) {
        for (int i = startIndex; i < size; i++) {
            ActionRecord rec = latest(i);
            if (rec.type == type) {
                return rec;
            }
        }
        return null;
    }
}
