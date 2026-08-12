package dev.obsidian.core.ml;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Appends finalized feature vectors to a readable CSV in the plugin data folder,
 * so a server owner can build a real, server-specific training set and — if they
 * choose — submit it back to improve the shared model. Each row is a timestamp,
 * a label (from {@code /ob label}, or "unlabeled"), the player, then the
 * features, under a self-describing header. The offline {@code LogisticTrainer}
 * reads this file by column name.
 *
 * <p>Writes are serialized and always off the packet thread (the ML check hands
 * logging to the async scheduler), so this never touches the hot path. UTF-8 and
 * the data folder is created if missing.</p>
 */
public final class DatasetLogger {

    public static final String FILE_NAME = "dataset.csv";

    private final File file;
    private final Logger logger;
    private BufferedWriter writer;
    private boolean failed;

    public DatasetLogger(File dataFolder, Logger logger) {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.file = new File(dataFolder, FILE_NAME);
        this.logger = logger;
    }

    public File file() {
        return file;
    }

    public synchronized void log(String label, String playerName, double[] features) {
        if (failed) {
            return;
        }
        try {
            if (writer == null) {
                open();
            }
            StringBuilder sb = new StringBuilder(160);
            sb.append(Instant.now()).append(',')
                    .append(label == null ? "unlabeled" : label).append(',')
                    .append(sanitize(playerName));
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
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8));
        if (fresh) {
            StringBuilder header = new StringBuilder("timestamp,label,player");
            for (String name : Features.NAMES) {
                header.append(',').append(name);
            }
            writer.write(header.toString());
            writer.newLine();
            writer.flush();
        }
    }

    /** Commas/newlines in a name would corrupt a row; player names never contain them, but be safe. */
    private static String sanitize(String s) {
        if (s == null) {
            return "?";
        }
        return s.replace(',', '_').replace('\n', '_').replace('\r', '_');
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

    /** Row counts for {@code /ob dataset}, read fresh from disk. */
    public static Stats stats(File file) {
        long total = 0;
        long cheat = 0;
        long legit = 0;
        if (file.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (first) {
                        first = false;
                        if (line.startsWith("timestamp,") || line.startsWith("label,")) {
                            continue; // header
                        }
                    }
                    total++;
                    String[] parts = line.split(",", 4);
                    String label = parts.length > 1 ? parts[1].trim().toLowerCase() : "";
                    if (label.equals("cheat")) {
                        cheat++;
                    } else if (label.equals("legit")) {
                        legit++;
                    }
                }
            } catch (IOException ignored) {
                // report whatever we counted before the error
            }
        }
        return new Stats(total, cheat, legit);
    }

    public record Stats(long total, long cheat, long legit) {
        public long labelled() {
            return cheat + legit;
        }
    }
}
