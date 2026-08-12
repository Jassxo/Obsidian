package dev.obsidian.core.stats;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleWindowTest {

    @Test
    void ringOverwritesOldest() {
        SampleWindow w = new SampleWindow(3);
        w.add(1);
        w.add(2);
        w.add(3);
        w.add(4); // evicts 1
        assertEquals(3, w.size());
        assertEquals(2, w.get(0));
        assertEquals(4, w.get(2));
        assertEquals(3.0, w.mean(), 1e-9);
    }

    @Test
    void flatSeriesHasZeroSigmaAndMaxAutocorrelation() {
        SampleWindow w = new SampleWindow(20);
        for (int i = 0; i < 20; i++) {
            w.add(45.0);
        }
        assertEquals(0.0, w.stdDev(), 1e-9);
        // The fixed-delay cheat signature: flat series pins autocorrelation at 1.
        assertEquals(1.0, w.lag1Autocorrelation(), 1e-9);
    }

    @Test
    void noisyHumanSeriesHasLowAutocorrelation() {
        Random random = new Random(7);
        SampleWindow w = new SampleWindow(20);
        for (int i = 0; i < 20; i++) {
            w.add(190 + random.nextGaussian() * 45);
        }
        assertTrue(Math.abs(w.lag1Autocorrelation()) < 0.6,
                "independent gaussian samples should not look periodic");
        assertTrue(w.stdDev() > 20, "human-like series keeps its variance");
    }

    @Test
    void alternatingSeriesHasNegativeAutocorrelation() {
        SampleWindow w = new SampleWindow(20);
        for (int i = 0; i < 20; i++) {
            w.add(i % 2 == 0 ? 100 : 200);
        }
        assertTrue(w.lag1Autocorrelation() < -0.8);
    }
}
