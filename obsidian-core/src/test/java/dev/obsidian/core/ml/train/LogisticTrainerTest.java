package dev.obsidian.core.ml.train;

import dev.obsidian.core.ml.Features;
import dev.obsidian.core.ml.LogisticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticTrainerTest {

    @Test
    void trainsASeparatingModelFromLabelledRows(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("dataset.csv");
        Path model = dir.resolve("model.dat");

        try (BufferedWriter w = new BufferedWriter(new FileWriter(csv.toFile()))) {
            w.write("label,player");
            for (String n : Features.NAMES) {
                w.write("," + n);
            }
            w.newLine();
            // Legit: low reach/angle/aura, high click sigma and micro-jitter.
            // Cheat: high reach/angle/aura, low click sigma and micro-jitter.
            for (int i = 0; i < 40; i++) {
                writeRow(w, "legit", -0.3, 0.0, 15, 0.0, 170, 50, 0.0, 0.4, 1, 200);
                writeRow(w, "cheat", 0.6, 0.6, 55, 0.6, 45, 5, 0.95, 0.02, 3, 10);
            }
        }

        LogisticTrainer.main(new String[]{csv.toString(), model.toString()});

        try (FileInputStream in = new FileInputStream(model.toFile())) {
            LogisticModel m = LogisticModel.load(in);
            double legit = m.score(new double[]{-0.3, 0.0, 15, 0.0, 170, 50, 0.0, 0.4, 1, 200});
            double cheat = m.score(new double[]{0.6, 0.6, 55, 0.6, 45, 5, 0.95, 0.02, 3, 10});
            assertTrue(legit < 0.2, "legit sample should score low, was " + legit);
            assertTrue(cheat > 0.8, "cheat sample should score high, was " + cheat);
        }
    }

    private static void writeRow(BufferedWriter w, String label, double... f) throws java.io.IOException {
        w.write(label + ",tester");
        for (double v : f) {
            w.write("," + v);
        }
        w.newLine();
    }
}
