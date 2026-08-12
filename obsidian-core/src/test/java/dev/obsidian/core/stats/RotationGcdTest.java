package dev.obsidian.core.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationGcdTest {

    /** The vanilla quantum for a typical sensitivity. */
    private static final double QUANTUM = 0.140625;

    @Test
    void longGcd() {
        assertEquals(6, RotationGcd.gcd(12, 18));
        assertEquals(1, RotationGcd.gcd(17, 13));
        assertEquals(5, RotationGcd.gcd(5, 0));
    }

    @Test
    void learnsQuantumFromMultiples() {
        RotationGcd gcd = new RotationGcd();
        int[] steps = {3, 7, 2, 12, 5, 9, 4, 20, 1, 6};
        for (int step : steps) {
            gcd.add(step * QUANTUM);
        }
        assertTrue(gcd.isEstimateUsable());
        // The learned quantum should divide every legit rotation.
        assertTrue(gcd.isConsistent(8 * QUANTUM, gcd.gcdValue() / 10));
        assertTrue(gcd.isConsistent(15 * QUANTUM, gcd.gcdValue() / 10));
    }

    @Test
    void flagsNonMultipleDeltas() {
        RotationGcd gcd = new RotationGcd();
        for (int i = 1; i <= 30; i++) {
            gcd.add((i % 13 + 1) * QUANTUM);
        }
        // A spoofed rotation landing between quanta is inconsistent.
        assertFalse(gcd.isConsistent(7.5 * QUANTUM, gcd.gcdValue() / 10));
    }

    @Test
    void ignoresNoiseFloor() {
        RotationGcd gcd = new RotationGcd();
        assertFalse(gcd.add(0.00001));
        assertEquals(0, gcd.samples());
    }
}
