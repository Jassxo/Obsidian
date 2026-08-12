package dev.obsidian.core.ml;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticModelTest {

    @Test
    void scoreMatchesHandComputedSigmoid() {
        double[] mean = new double[Features.COUNT];
        double[] scale = new double[Features.COUNT];
        double[] weight = new double[Features.COUNT];
        java.util.Arrays.fill(scale, 1.0);
        weight[0] = 2.0;
        LogisticModel m = new LogisticModel(1, mean, scale, weight, -1.0);

        double[] x = new double[Features.COUNT];
        x[0] = 1.5; // z = -1 + 2*1.5 = 2.0
        double expected = 1.0 / (1.0 + Math.exp(-2.0));
        assertEquals(expected, m.score(x), 1e-12);
    }

    @Test
    void saveThenLoadReproducesScores() throws Exception {
        double[] mean = {0.1, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        double[] scale = {0.5, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        double[] weight = {1.2, -0.4, 0, 0, 0, 0, 0, 0, 0, 0};
        LogisticModel original = new LogisticModel(3, mean, scale, weight, -0.7);

        StringWriter sw = new StringWriter();
        original.save(sw);
        LogisticModel loaded = LogisticModel.load(
                new ByteArrayInputStream(sw.toString().getBytes(StandardCharsets.UTF_8)));

        double[] x = Features.NEUTRAL.clone();
        x[0] = 0.9;
        assertEquals(original.score(x), loaded.score(x), 1e-12);
        assertEquals("logistic v3", loaded.id());
    }

    @Test
    void bundledModelIsCalibratedConservatively() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/model.dat")) {
            assertNotNull(in, "shipped model.dat must be on the classpath");
            LogisticModel m = LogisticModel.load(in);

            // A neutral (legit) window should score well below the flag threshold.
            assertTrue(m.score(Features.NEUTRAL) < 0.3,
                    "neutral window should read as clearly legit");

            // No single feature at an extreme should cross a high threshold alone.
            double[] onlyReach = Features.NEUTRAL.clone();
            onlyReach[Features.REACH_VIOL_RATIO] = 0.5;
            assertTrue(m.score(onlyReach) < 0.85, "one signal alone must not flag");

            // Several agreeing signals should push the score high.
            double[] blatant = {0.6, 0.7, 55, 0.6, 45, 6, 0.96, 0.02, 3, 8};
            assertTrue(m.score(blatant) > 0.9, "multiple agreeing signals should score high");
        }
    }
}
