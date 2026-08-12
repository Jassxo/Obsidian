package dev.obsidian.core.stats;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WelfordAccumulatorTest {

    @Test
    void matchesNaiveMeanAndVariance() {
        Random random = new Random(42);
        double[] samples = new double[1000];
        WelfordAccumulator acc = new WelfordAccumulator();
        for (int i = 0; i < samples.length; i++) {
            samples[i] = random.nextGaussian() * 30 + 150;
            acc.add(samples[i]);
        }

        double mean = 0;
        for (double s : samples) {
            mean += s;
        }
        mean /= samples.length;
        double var = 0;
        for (double s : samples) {
            var += (s - mean) * (s - mean);
        }
        var /= samples.length - 1;

        assertEquals(mean, acc.mean(), 1e-9);
        assertEquals(var, acc.variance(), 1e-6);
        assertEquals(samples.length, acc.count());
    }

    @Test
    void emptyAndSingleSampleAreSafe() {
        WelfordAccumulator acc = new WelfordAccumulator();
        assertEquals(0.0, acc.mean());
        assertEquals(0.0, acc.variance());
        acc.add(5.0);
        assertEquals(5.0, acc.mean());
        assertEquals(0.0, acc.variance());
    }

    @Test
    void resetClearsState() {
        WelfordAccumulator acc = new WelfordAccumulator();
        acc.add(1);
        acc.add(2);
        acc.reset();
        assertEquals(0, acc.count());
        assertEquals(0.0, acc.mean());
    }
}
