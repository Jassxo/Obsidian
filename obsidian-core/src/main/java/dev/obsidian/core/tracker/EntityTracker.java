package dev.obsidian.core.tracker;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * The players one player currently sees, with their positions as that client
 * received them. Lives inside {@link PlayerData}, one per player.
 *
 * <p>Only players are tracked: they are the only targets the combat checks use,
 * so tracking mobs, items, projectiles and the like would be pure overhead. A
 * move packet for an untracked entity is a single map miss and nothing else.</p>
 *
 * <p>A player's channel handles both its inbound and outbound packets on a
 * single netty event-loop thread, so player spawns/moves (server→client) and
 * attack reads (client→server) all touch this map from the same thread. No
 * locking is needed, matching the ownership model of the rest of PlayerData.</p>
 */
public final class EntityTracker {

    /** Hard cap so nothing can grow this without bound; far above any real view. */
    private static final int MAX_ENTITIES = 256;

    private final Int2ObjectOpenHashMap<TrackedEntity> players = new Int2ObjectOpenHashMap<>(64);

    public void spawnPlayer(int entityId, double x, double y, double z, long now) {
        if (players.size() >= MAX_ENTITIES && !players.containsKey(entityId)) {
            return; // full; keep the ones we already track rather than evict a live target
        }
        players.put(entityId, new TrackedEntity(entityId, x, y, z, now));
    }

    public void move(int entityId, double dx, double dy, double dz, long now) {
        TrackedEntity e = players.get(entityId);
        if (e != null) {
            e.move(dx, dy, dz, now);
        }
    }

    public void teleport(int entityId, double x, double y, double z, long now) {
        TrackedEntity e = players.get(entityId);
        if (e != null) {
            e.teleport(x, y, z, now);
        }
    }

    public void remove(int entityId) {
        players.remove(entityId);
    }

    /** The tracked player with this id, or null if it is not a tracked player. */
    public TrackedEntity get(int entityId) {
        return players.get(entityId);
    }

    public int size() {
        return players.size();
    }

    public void clear() {
        players.clear();
    }
}
