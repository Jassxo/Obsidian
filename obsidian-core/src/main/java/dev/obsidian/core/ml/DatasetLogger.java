package dev.obsidian.core.ml;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Appends finalized feature vectors to a CSV in the plugin data folder so the
 * server owner can build a real, server-specific training set. Each row is a
 * label (from {@code /ob label}, or "unlabeled") followed by the features. The
 * offline {@code LogisticTrainer} reads exactly this file.
 *
 * <p>Writes are serialized and always off the packet thread (the ML check hands
 * logging to the async scheduler), so this never touches the hot path.</p>
 */
public final class DatasetLogger {

    private final File file;
    private final Logger logger;
    private BufferedWriter writer;
    private boolean failed;

    public DatasetLogger(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "dataset.csv");
        this.logger = logger;
    }

    public synchronized void log(String label, String playerName, double[] features) {
        if (failed) {
            return;
        }
        try {
            if (writer == null) {
                open();
            }
            StringBuilder sb = new StringBuilder(128);
            sb.append(label == null ? "unlabeled" : label).append(',').append(playerName);
            for (double v : features) {
                sb.append(',').append(v);
            }
            writer.write(sb.toString());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            failed = true;
            logger.warning("Dataset logging disabled: " + e.getMessage());
        }
    }

    private void open() throws IOException {
        boolean fresh = !file.exists() || file.length() == 0;
        writer = new BufferedWriter(new FileWriter(file, true));
        if (fresh) {
            StringBuilder header = new StringBuilder("label,player");
            for (String name : Features.NAMES) {
                header.append(',').append(name);
            }
            writer.write(header.toString());
            writer.newLine();
            writer.flush();
        }
    }

    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // shutting down; nothing useful to do
            }
            writer = null;
        }
    }
}
