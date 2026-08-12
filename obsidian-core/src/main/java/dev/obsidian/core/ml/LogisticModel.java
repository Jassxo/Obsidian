package dev.obsidian.core.ml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Standardized logistic regression: {@code p = sigmoid(bias + Σ w_i·(x_i - mean_i)/scale_i)}.
 *
 * <p>Standardization keeps the weights readable and comparable across features
 * that live on wildly different scales (a reach excess of 0.3 vs a 160 ms click
 * interval). Inference is a single dot product — nanoseconds, no allocation, no
 * native library. The model is loaded from a small text resource and the offline
 * trainer writes that same format, so training and serving never disagree.</p>
 */
public final class LogisticModel implements Model {

    private final int version;
    private final double[] mean;
    private final double[] scale;
    private final double[] weight;
    private final double bias;

    public LogisticModel(int version, double[] mean, double[] scale, double[] weight, double bias) {
        if (mean.length != Features.COUNT || scale.length != Features.COUNT
                || weight.length != Features.COUNT) {
            throw new IllegalArgumentException("model arity != " + Features.COUNT);
        }
        this.version = version;
        this.mean = mean.clone();
        this.scale = scale.clone();
        this.weight = weight.clone();
        this.bias = bias;
    }

    @Override
    public double score(double[] features) {
        double z = bias;
        for (int i = 0; i < Features.COUNT; i++) {
            double s = scale[i] == 0 ? 1.0 : scale[i];
            z += weight[i] * ((features[i] - mean[i]) / s);
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }

    @Override
    public String id() {
        return "logistic v" + version;
    }

    // ------------------------------------------------------------------
    // Text format (shared with LogisticTrainer). One directive per line:
    //   obsidian-model <version>
    //   mean   c0,c1,...
    //   scale  c0,c1,...
    //   weight c0,c1,...
    //   bias   b
    // Lines starting with '#' and blank lines are ignored.
    // ------------------------------------------------------------------

    public static LogisticModel load(InputStream in) throws IOException {
        int version = 1;
        double[] mean = null;
        double[] scale = null;
        double[] weight = null;
        double bias = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int sp = line.indexOf(' ');
                if (sp < 0) {
                    continue;
                }
                String key = line.substring(0, sp).trim();
                String val = line.substring(sp + 1).trim();
                switch (key) {
                    case "obsidian-model" -> version = Integer.parseInt(val);
                    case "mean" -> mean = parseRow(val);
                    case "scale" -> scale = parseRow(val);
                    case "weight" -> weight = parseRow(val);
                    case "bias" -> bias = Double.parseDouble(val);
                    default -> { /* forward-compatible: ignore unknown directives */ }
                }
            }
        }
        if (mean == null || scale == null || weight == null) {
            throw new IOException("model is missing mean/scale/weight rows");
        }
        return new LogisticModel(version, mean, scale, weight, bias);
    }

    public void save(Writer out) throws IOException {
        out.write("# Obsidian logistic model. Retrain with ml/train/LogisticTrainer.\n");
        out.write("obsidian-model " + version + "\n");
        out.write("mean " + row(mean) + "\n");
        out.write("scale " + row(scale) + "\n");
        out.write("weight " + row(weight) + "\n");
        out.write("bias " + bias + "\n");
    }

    private static double[] parseRow(String csv) {
        String[] parts = csv.split(",");
        if (parts.length != Features.COUNT) {
            throw new IllegalArgumentException("row has " + parts.length + " values, expected " + Features.COUNT);
        }
        double[] out = new double[Features.COUNT];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    private static String row(double[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.toString();
    }
}
