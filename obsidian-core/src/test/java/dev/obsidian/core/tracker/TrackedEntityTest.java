package dev.obsidian.core.tracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackedEntityTest {

    private static final double W = 0.6;
    private static final double H = 1.8;

    @Test
    void reachIsGapToHitboxNotToCenter() {
        // Player-sized box centered at x=0; eye 5 blocks away on +x, at hip height.
        TrackedEntity e = new TrackedEntity(1, TrackedEntity.Kind.PLAYER, W, H, 0, 0, 0, 1000);
        // Nearest hitbox face is at x = +0.3, so the gap is 5 - 0.3 = 4.7.
        assertEquals(4.7, e.minReachDistance(5, 1, 0, 0), 1e-9);
    }

    @Test
    void eyeInsideColumnMeasuresVerticalGapOnly() {
        TrackedEntity e = new TrackedEntity(1, TrackedEntity.Kind.PLAYER, W, H, 0, 0, 0, 1000);
        // Directly above the box: x,z inside footprint, y above the 1.8 top by 0.5.
        assertEquals(0.5, e.minReachDistance(0, 2.3, 0, 0), 1e-9);
    }

    @Test
    void reachTakesTheMinimumAcrossTheWindow() {
        TrackedEntity e = new TrackedEntity(1, TrackedEntity.Kind.PLAYER, W, H, 0, 0, 0, 1000);
        e.teleport(4, 0, 0, 2000); // now the near face is at x=4.3, gap 0.7
        // Both positions are inside the window: the lenient reach is the closer one.
        assertEquals(0.7, e.minReachDistance(5, 1, 0, 0), 1e-9);
    }

    @Test
    void positionsOlderThanTheWindowAreExcluded() {
        // Old sample is near (gap 0.7), newest is far (gap 4.7).
        TrackedEntity e = new TrackedEntity(1, TrackedEntity.Kind.PLAYER, W, H, 4, 0, 0, 1000);
        e.teleport(0, 0, 0, 2000);
        // Window covers both -> min is the near old sample.
        assertEquals(0.7, e.minReachDistance(5, 1, 0, 0), 1e-9);
        // Window starts after the old sample -> only the far newest remains.
        assertEquals(4.7, e.minReachDistance(5, 1, 0, 1500), 1e-9);
    }

    @Test
    void relativeMoveAccumulatesOntoLastPosition() {
        TrackedEntity e = new TrackedEntity(1, TrackedEntity.Kind.PLAYER, W, H, 0, 0, 0, 1000);
        e.move(4, 0, 0, 2000); // 0 + 4 = 4
        assertEquals(4.0, e.centerX(), 1e-9);
        assertEquals(0.7, e.minReachDistance(5, 1, 0, 1500), 1e-9);
    }
}
