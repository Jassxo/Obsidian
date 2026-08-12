package dev.obsidian.core.ml.train;

import dev.obsidian.core.ml.Features;
import dev.obsidian.core.ml.LogisticModel;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline trainer for the logistic model. Not part of the running plugin — it is
 * a standalone tool you run against the dataset the plugin collects:
 *
 * <pre>
 *   java -cp Obsidian-2.0.0.jar dev.obsidian.core.ml.train.LogisticTrainer \
 *        plugins/Obsidian/dataset.csv plugins/Obsidian/model.dat
 * </pre>
 *
 * <p>It reads the CSV (label,player,features...), keeps the rows a human labelled
 * "cheat" or "legit", standardizes each feature, fits weights by gradient descent
 * with a little L2 regularization, and writes a model.dat the plugin loads on its
 * next reload. Pure JDK, no dependencies, single language with the plugin so the
 * feature order can never drift.</p>
 */
public final class LogisticTrainer {

    private static final int EPOCHS = 400;
    private static final double LEARNING_RATE = 0.1;
    private static final double L2 = 0.001;

    private LogisticTrainer() {
    }

    public static void main(String[] args) throws IOException {
        String datasetPath = args.length > 0 ? args[0] : "dataset.csv";
        String outPath = args.length > 1 ? args[1] : "model.dat";

        List<double[]> x = new ArrayList<>();
        List<Integer> y = new ArrayList<>();
        read(datasetPath, x, y);
        if (x.isEmpty()) {
            System.err.println("No labelled rows found in " + datasetPath
                    + ". Collect data with dataset-logging + /ob label first.");
            return;
        }
        System.out.println("Loaded " + x.size() + " labelled samples ("
                + y.stream().filter(v -> v == 1).count() + " cheat, "
                + y.stream().filter(v -> v == 0).count() + " legit).");

        double[] mean = new double[Features.COUNT];
        double[] scale = new double[Features.COUNT];
        standardizeStats(x, mean, scale);

        double[][] xs = new double[x.size()][Features.COUNT];
        for (int i = 0; i < x.size(); i++) {
            for (int j = 0; j < Features.COUNT; j++) {
                xs[i][j] = (x.get(i)[j] - mean[j]) / scale[j];
            }
        }

        double[] w = new double[Features.COUNT];
        double bias = 0;
        for (int epoch = 0; epoch < EPOCHS; epoch++) {
            double[] gradW = new double[Features.COUNT];
            double gradB = 0;
            for (int i = 0; i < xs.length; i++) {
                double p = sigmoid(dot(w, xs[i]) + bias);
                double err = p - y.get(i);
                for (int j = 0; j < Features.COUNT; j++) {
                    gradW[j] += err * xs[i][j];
                }
                gradB += err;
            }
            for (int j = 0; j < Features.COUNT; j++) {
                w[j] -= LEARNING_RATE * (gradW[j] / xs.length + L2 * w[j]);
            }
            bias -= LEARNING_RATE * (gradB / xs.length);
        }

        report(xs, y, w, bias);

        LogisticModel model = new LogisticModel(1, mean, scale, w, bias);
        try (Writer out = new FileWriter(outPath)) {
            model.save(out);
        }
        System.out.println("Wrote " + outPath + ". Drop it in the plugin data folder and /ob reload.");
    }

    private static void read(String path, List<double[]> x, List<Integer> y) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (header) {
                    header = false;
                    if (line.startsWith("label,")) {
                        continue;
                    }
                }
                String[] parts = line.split(",");
                if (parts.length < 2 + Features.COUNT) {
                    continue;
                }
                int label;
                if (parts[0].equalsIgnoreCase("cheat")) {
                    label = 1;
                } else if (parts[0].equalsIgnoreCase("legit")) {
                    label = 0;
                } else {
                    continue; // unlabelled row, skip
                }
                double[] f = new double[Features.COUNT];
                for (int j = 0; j < Features.COUNT; j++) {
                    f[j] = Double.parseDouble(parts[2 + j].trim());
                }
                x.add(f);
                y.add(label);
            }
        }
    }

    private static void standardizeStats(List<double[]> x, double[] mean, double[] scale) {
        int n = x.size();
        for (double[] row : x) {
            for (int j = 0; j < Features.COUNT; j++) {
                mean[j] += row[j];
            }
        }
        for (int j = 0; j < Features.COUNT; j++) {
            mean[j] /= n;
        }
        for (double[] row : x) {
            for (int j = 0; j < Features.COUNT; j++) {
                double d = row[j] - mean[j];
                scale[j] += d * d;
            }
        }
        for (int j = 0; j < Features.COUNT; j++) {
            scale[j] = Math.sqrt(scale[j] / Math.max(1, n));
            if (scale[j] < 1e-9) {
                scale[j] = 1.0; // a constant feature: leave it unscaled
            }
        }
    }

    private static void report(double[][] xs, List<Integer> y, double[] w, double bias) {
        int correct = 0;
        double logLoss = 0;
        for (int i = 0; i < xs.length; i++) {
            double p = sigmoid(dot(w, xs[i]) + bias);
            int pred = p >= 0.5 ? 1 : 0;
            if (pred == y.get(i)) {
                correct++;
            }
            double clamped = Math.min(1 - 1e-9, Math.max(1e-9, p));
            logLoss += -(y.get(i) * Math.log(clamped) + (1 - y.get(i)) * Math.log(1 - clamped));
        }
        System.out.printf("Training accuracy %.1f%%, log-loss %.4f%n",
                100.0 * correct / xs.length, logLoss / xs.length);
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }
}
