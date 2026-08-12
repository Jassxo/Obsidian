package dev.obsidian.core.ledger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.obsidian.api.Signal;
import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.tracker.PlayerData;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Persistent detection ledger. SQLite in WAL mode by default, MySQL optional.
 * Every write goes through an in-memory queue flushed asynchronously in
 * batches — the packet path and the main thread never see a JDBC call.
 */
public final class Ledger {

    public record FlagEntry(UUID uuid, String name, long timestampMillis, double confidence,
                            int ping, double mspt, List<Signal> signals) {
    }

    public record HistoryRow(long timestampMillis, double confidence, int ping) {
    }

    private final ObsidianPlugin plugin;
    private HikariDataSource pool;
    private final ConcurrentLinkedQueue<FlagEntry> writeQueue = new ConcurrentLinkedQueue<>();
    private volatile long flagsToday;

    public Ledger(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() throws SQLException {
        HikariConfig config = new HikariConfig();
        if ("mysql".equalsIgnoreCase(plugin.configs().ledgerBackend())) {
            config.setJdbcUrl(plugin.configs().mysqlJdbcUrl());
            config.setUsername(plugin.configs().mysqlUsername());
            config.setPassword(plugin.configs().mysqlPassword());
            config.setMaximumPoolSize(4);
        } else {
            File db = new File(plugin.getDataFolder(), plugin.configs().sqliteFile());
            config.setJdbcUrl("jdbc:sqlite:" + db.getAbsolutePath());
            config.setMaximumPoolSize(1); // sqlite: single writer
            config.setConnectionInitSql("PRAGMA journal_mode=WAL");
        }
        config.setPoolName("obsidian-ledger");
        pool = new HikariDataSource(config);
        createSchema();

        int interval = plugin.configs().flushIntervalSeconds();
        plugin.scheduler().repeatAsync(this::flush, interval, interval);
    }

    private void createSchema() throws SQLException {
        try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS flags (
                        id INTEGER PRIMARY KEY %s,
                        uuid VARCHAR(36) NOT NULL,
                        name VARCHAR(16) NOT NULL,
                        timestamp BIGINT NOT NULL,
                        confidence DOUBLE PRECISION NOT NULL,
                        ping INTEGER NOT NULL,
                        mspt DOUBLE PRECISION NOT NULL
                    )""".formatted(isSqlite() ? "AUTOINCREMENT" : "AUTO_INCREMENT"));
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS flag_signals (
                        flag_id INTEGER NOT NULL,
                        check_id VARCHAR(32) NOT NULL,
                        strength DOUBLE PRECISION NOT NULL,
                        evidence VARCHAR(255) NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_profiles (
                        uuid VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(16) NOT NULL,
                        sessions INTEGER NOT NULL DEFAULT 0,
                        max_confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
                        last_seen BIGINT NOT NULL DEFAULT 0
                    )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_flags_uuid ON flags (uuid)");
        }
    }

    private boolean isSqlite() {
        return !"mysql".equalsIgnoreCase(plugin.configs().ledgerBackend());
    }

    // ------------------------------------------------------------------
    // writes (all async)
    // ------------------------------------------------------------------

    public void writeFlagAsync(PlayerData data, double confidence, List<Signal> signals,
                               int ping, double mspt) {
        writeQueue.add(new FlagEntry(data.uuid(), data.name(), System.currentTimeMillis(),
                confidence, ping, mspt, signals));
        flagsToday++;
        if (writeQueue.size() >= plugin.configs().flushBatchSize()) {
            plugin.scheduler().runAsync(this::flush);
        }
    }

    private synchronized void flush() {
        if (writeQueue.isEmpty() || pool == null) {
            return;
        }
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            FlagEntry entry;
            while ((entry = writeQueue.poll()) != null) {
                long flagId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO flags (uuid, name, timestamp, confidence, ping, mspt) VALUES (?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, entry.uuid().toString());
                    ps.setString(2, entry.name());
                    ps.setLong(3, entry.timestampMillis());
                    ps.setDouble(4, entry.confidence());
                    ps.setInt(5, entry.ping());
                    ps.setDouble(6, entry.mspt());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        flagId = keys.next() ? keys.getLong(1) : -1;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO flag_signals (flag_id, check_id, strength, evidence) VALUES (?,?,?,?)")) {
                    for (Signal signal : entry.signals()) {
                        ps.setLong(1, flagId);
                        ps.setString(2, signal.checkId());
                        ps.setDouble(3, signal.strength());
                        ps.setString(4, truncate(signal.evidence(), 255));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ledger flush failed: " + e.getMessage());
        }
    }

    public void saveProfileAsync(PlayerData data) {
        double sessionMax = data.suspicion.sessionMax();
        plugin.scheduler().runAsync(() -> {
            String sql = isSqlite()
                    ? """
                    INSERT INTO player_profiles (uuid, name, sessions, max_confidence, last_seen)
                    VALUES (?,?,1,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET sessions = sessions + 1,
                        max_confidence = MAX(max_confidence, excluded.max_confidence),
                        name = excluded.name, last_seen = excluded.last_seen"""
                    : """
                    INSERT INTO player_profiles (uuid, name, sessions, max_confidence, last_seen)
                    VALUES (?,?,1,?,?)
                    ON DUPLICATE KEY UPDATE sessions = sessions + 1,
                        max_confidence = GREATEST(max_confidence, VALUES(max_confidence)),
                        name = VALUES(name), last_seen = VALUES(last_seen)""";
            try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, data.uuid().toString());
                ps.setString(2, data.name());
                ps.setDouble(3, sessionMax);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Profile save failed: " + e.getMessage());
            }
        });
    }

    /** Repeat offenders start with an informed prior: log it for staff context. */
    public void loadProfileAsync(UUID uuid) {
        plugin.scheduler().runAsync(() -> {
            try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "SELECT max_confidence FROM player_profiles WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getDouble(1) >= plugin.configs().flagThreshold()) {
                        plugin.getLogger().info("Player " + uuid + " has flagged before (historical max "
                                + Math.round(rs.getDouble(1)) + "%).");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Profile load failed: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // reads (called from async command handlers)
    // ------------------------------------------------------------------

    public List<HistoryRow> history(String playerName, int limit) {
        List<HistoryRow> rows = new ArrayList<>();
        try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT timestamp, confidence, ping FROM flags WHERE name = ? ORDER BY timestamp DESC LIMIT ?")) {
            ps.setString(1, playerName);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HistoryRow(rs.getLong(1), rs.getDouble(2), rs.getInt(3)));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("History query failed: " + e.getMessage());
        }
        return rows;
    }

    /** Human-readable YAML export of everything the ledger has on a player. */
    public File exportPlayer(String playerName) throws Exception {
        File dir = new File(plugin.getDataFolder(), "exports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("could not create exports directory");
        }
        File out = new File(dir, playerName + "-" + java.time.LocalDate.now() + ".yml");
        StringBuilder yaml = new StringBuilder();
        yaml.append("player: ").append(playerName).append('\n');
        yaml.append("exported: ").append(java.time.Instant.now()).append('\n');
        yaml.append("flags:\n");
        try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id, timestamp, confidence, ping, mspt FROM flags WHERE name = ? ORDER BY timestamp DESC LIMIT 100")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    yaml.append("  - time: ").append(java.time.Instant.ofEpochMilli(rs.getLong(2))).append('\n');
                    yaml.append("    confidence: ").append(rs.getDouble(3)).append('\n');
                    yaml.append("    ping: ").append(rs.getInt(4)).append('\n');
                    yaml.append("    mspt: ").append(rs.getDouble(5)).append('\n');
                    yaml.append("    signals:\n");
                    try (PreparedStatement sps = conn.prepareStatement(
                            "SELECT check_id, strength, evidence FROM flag_signals WHERE flag_id = ?")) {
                        sps.setLong(1, id);
                        try (ResultSet srs = sps.executeQuery()) {
                            while (srs.next()) {
                                yaml.append("      - check: ").append(srs.getString(1)).append('\n');
                                yaml.append("        strength: ").append(srs.getDouble(2)).append('\n');
                                yaml.append("        evidence: \"").append(srs.getString(3).replace("\"", "'")).append("\"\n");
                            }
                        }
                    }
                }
            }
        }
        java.nio.file.Files.writeString(out.toPath(), yaml.toString());
        return out;
    }

    public long flagsToday() {
        return flagsToday;
    }

    public void shutdown() {
        flush();
        if (pool != null) {
            pool.close();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
