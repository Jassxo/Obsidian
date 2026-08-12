package dev.obsidian.core.tracker;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.time.Duration;
import java.util.UUID;

/**
 * Lifecycle of every live end crystal: when it spawned, where, and who placed
 * it. This is the primitive behind "attacked crystal X ms after it appeared".
 *
 * <p>Spawn/destroy packets for the same entity arrive on multiple netty threads
 * (one per viewer), so mutation is guarded by a single lock. The maps are tiny
 * (live crystals only) and the operations are O(1), so contention is a
 * non-issue at 100 players.</p>
 */
public final class CrystalTracker {

    /** How close (blocks, squared) and how recent (ns) a placement packet must be to claim a crystal. */
    private static final double ATTRIBUTION_RADIUS_SQ = 2.25; // 1.5 blocks
    private static final long ATTRIBUTION_WINDOW_NANOS = 500_000_000L;
    private static final int RECENT_PLACEMENTS = 64;

    public static final class CrystalRecord {
        public int entityId;
        public long spawnNanos;
        public double x, y, z;
        public UUID placer; // null if unattributed (plugin/dispenser spawned)
    }

    private static final class RecentPlacement {
        UUID player;
        double x, y, z;
        long nanos;
    }

    private final Object lock = new Object();
    private final Int2ObjectOpenHashMap<CrystalRecord> liveCrystals = new Int2ObjectOpenHashMap<>(128);

    /**
     * Destroy packets are broadcast once per viewer; the first one removes the
     * crystal from the live map, but later viewers still need its position to
     * record their opportunity onset. Short-lived cache bridges that gap.
     */
    private final Cache<Integer, CrystalRecord> recentlyDestroyed = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(2))
            .maximumSize(1024)
            .build();
    private final RecentPlacement[] placements = new RecentPlacement[RECENT_PLACEMENTS];
    private int placementHead;

    public CrystalTracker() {
        for (int i = 0; i < RECENT_PLACEMENTS; i++) {
            placements[i] = new RecentPlacement();
        }
    }

    /** Player sent a use-item/placement with an end crystal in hand against a block. */
    public void recordPlacementAttempt(UUID player, int blockX, int blockY, int blockZ, long nanos) {
        synchronized (lock) {
            RecentPlacement slot = placements[placementHead];
            slot.player = player;
            // Crystal entity spawns centered on top of the clicked block.
            slot.x = blockX + 0.5;
            slot.y = blockY + 1.0;
            slot.z = blockZ + 0.5;
            slot.nanos = nanos;
            placementHead = (placementHead + 1) % RECENT_PLACEMENTS;
        }
    }

    /**
     * First spawn broadcast for a crystal entity. Returns the record (already
     * registered) so callers can log; subsequent broadcasts for the same id
     * return null.
     */
    public CrystalRecord onCrystalSpawn(int entityId, double x, double y, double z, long nanos) {
        synchronized (lock) {
            if (liveCrystals.containsKey(entityId)) {
                return null;
            }
            CrystalRecord rec = new CrystalRecord();
            rec.entityId = entityId;
            rec.spawnNanos = nanos;
            rec.x = x;
            rec.y = y;
            rec.z = z;
            rec.placer = attribute(x, y, z, nanos);
            liveCrystals.put(entityId, rec);
            return rec;
        }
    }

    private UUID attribute(double x, double y, double z, long nanos) {
        UUID best = null;
        long bestAge = Long.MAX_VALUE;
        for (RecentPlacement p : placements) {
            if (p.player == null) {
                continue;
            }
            long age = nanos - p.nanos;
            if (age < 0 || age > ATTRIBUTION_WINDOW_NANOS) {
                continue;
            }
            double dx = p.x - x, dy = p.y - y, dz = p.z - z;
            if (dx * dx + dy * dy + dz * dz > ATTRIBUTION_RADIUS_SQ) {
                continue;
            }
            if (age < bestAge) {
                bestAge = age;
                best = p.player;
            }
        }
        return best;
    }

    public CrystalRecord getCrystal(int entityId) {
        synchronized (lock) {
            return liveCrystals.get(entityId);
        }
    }

    public boolean isCrystal(int entityId) {
        synchronized (lock) {
            return liveCrystals.containsKey(entityId);
        }
    }

    public CrystalRecord onCrystalDestroy(int entityId) {
        CrystalRecord rec;
        synchronized (lock) {
            rec = liveCrystals.remove(entityId);
        }
        if (rec != null) {
            recentlyDestroyed.put(entityId, rec);
        }
        return rec;
    }

    /** Live or destroyed-within-2s record, for per-viewer destroy handling. */
    public CrystalRecord getCrystalOrRecentlyDestroyed(int entityId) {
        CrystalRecord rec = getCrystal(entityId);
        return rec != null ? rec : recentlyDestroyed.getIfPresent(entityId);
    }

    public int liveCount() {
        synchronized (lock) {
            return liveCrystals.size();
        }
    }
}
