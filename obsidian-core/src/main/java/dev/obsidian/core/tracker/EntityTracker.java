package dev.obsidian.core.tracker;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * The set of entities one player currently sees, with their positions as that
 * client received them. Lives inside {@link PlayerData}, one per player.
 *
 * <p>A player's channel handles both its inbound and outbound packets on a
 * single netty event-loop thread, so entity spawns/moves (server→client) and
 * attack reads (client→server) all touch this map from the same thread. No
 * locking is needed, matching the ownership model of the rest of PlayerData.</p>
 */
public final class EntityTracker {

    /** Hard cap so a nether-portal spam or entity flood can never grow this without bound. */
    private static final int MAX_ENTITIES = 512;

    // Vanilla player hitbox; the only kind whose exact size we rely on for reach.
    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;

    private final Int2ObjectOpenHashMap<TrackedEntity> entities = new Int2ObjectOpenHashMap<>(128);

    public void spawnPlayer(int entityId, double x, double y, double z, long now) {
        spawn(entityId, TrackedEntity.Kind.PLAYER, PLAYER_WIDTH, PLAYER_HEIGHT, x, y, z, now);
    }

    public void spawnOther(int entityId, double width, double height,
                           double x, double y, double z, long now) {
        spawn(entityId, TrackedEntity.Kind.OTHER, width, height, x, y, z, now);
    }

    private void spawn(int entityId, TrackedEntity.Kind kind, double width, double height,
                       double x, double y, double z, long now) {
        if (entities.size() >= MAX_ENTITIES && !entities.containsKey(entityId)) {
            return; // full; drop the newcomer rather than evict a live target
        }
        entities.put(entityId, new TrackedEntity(entityId, kind, width, height, x, y, z, now));
    }

    public void move(int entityId, double dx, double dy, double dz, long now) {
        TrackedEntity e = entities.get(entityId);
        if (e != null) {
            e.move(dx, dy, dz, now);
        }
    }

    public void teleport(int entityId, double x, double y, double z, long now) {
        TrackedEntity e = entities.get(entityId);
        if (e != null) {
            e.teleport(x, y, z, now);
        }
    }

    public void remove(int entityId) {
        entities.remove(entityId);
    }

    public TrackedEntity get(int entityId) {
        return entities.get(entityId);
    }

    public int size() {
        return entities.size();
    }

    public void clear() {
        entities.clear();
    }
}
