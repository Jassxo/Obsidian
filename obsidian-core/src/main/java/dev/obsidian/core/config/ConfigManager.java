package dev.obsidian.core.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads config.yml / checks.yml / messages.yml, merging in any keys the shipped
 * defaults have that the on-disk file lacks (that is the whole migration story:
 * additive, with the previous file backed up when its version bumps).
 */
public final class ConfigManager {

    private final JavaPlugin plugin;

    private YamlConfiguration config;
    private YamlConfiguration checks;
    private YamlConfiguration messages;

    private Set<String> exemptWorlds = new HashSet<>();
    private long joinGraceNanos;
    private long postTeleportGraceNanos;
    private long postRespawnGraceNanos;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        config = loadFile("config.yml");
        checks = loadFile("checks.yml");
        messages = loadFile("messages.yml");

        exemptWorlds = new HashSet<>(config.getStringList("exemptions.exempt-worlds"));
        joinGraceNanos = config.getLong("exemptions.join-grace-seconds", 10) * 1_000_000_000L;
        postTeleportGraceNanos = config.getLong("lag.post-teleport-grace-ms", 3000) * 1_000_000L;
        postRespawnGraceNanos = config.getLong("lag.post-respawn-grace-ms", 3000) * 1_000_000L;
    }

    private YamlConfiguration loadFile(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(plugin.getResource(name), StandardCharsets.UTF_8));

        int diskVersion = onDisk.getInt("config-version", 0);
        int currentVersion = defaults.getInt("config-version", 1);
        boolean changed = false;

        if (diskVersion < currentVersion) {
            try {
                Files.copy(file.toPath(),
                        new File(plugin.getDataFolder(), name + ".v" + diskVersion + ".bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not back up " + name + ": " + e.getMessage());
            }
            onDisk.set("config-version", currentVersion);
            changed = true;
        }

        for (String key : defaults.getKeys(true)) {
            if (!onDisk.contains(key)) {
                onDisk.set(key, defaults.get(key));
                changed = true;
            }
        }

        if (changed) {
            try {
                onDisk.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save migrated " + name + ": " + e.getMessage());
            }
        }
        return onDisk;
    }

    // --- engine ---

    public double flagThreshold() {
        return config.getDouble("engine.flag-threshold", 90.0);
    }

    public double suspiciousThreshold() {
        return config.getDouble("engine.suspicious-threshold", 70.0);
    }

    public double watchThreshold() {
        return config.getDouble("engine.watch-threshold", 40.0);
    }

    public double decayHalfLifeSeconds() {
        return config.getDouble("engine.decay-half-life-seconds", 300);
    }

    public double baselineMaxConfidence() {
        return config.getDouble("engine.baseline-max-confidence", 20.0);
    }

    public boolean adaptiveThresholds() {
        return config.getBoolean("engine.adaptive-thresholds", false);
    }

    // --- lag / exemptions ---

    public double maxMspt() {
        return config.getDouble("lag.max-mspt", 65.0);
    }

    // --- ping stability (spike / jitter gating) ---

    /** Std-dev of the RTT window, in millis, above which the link is "unstable" and checks stand down. */
    public int maxPingJitterMs() {
        return config.getInt("lag.max-ping-jitter-ms", 120);
    }

    /** A single RTT this much above the median counts as a spike. */
    public int pingSpikeThresholdMs() {
        return config.getInt("lag.ping-spike-threshold-ms", 150);
    }

    /** How long after a spike checks keep standing down. */
    public long pingSpikeGraceNanos() {
        return config.getLong("lag.ping-spike-grace-ms", 1500) * 1_000_000L;
    }

    // --- reach limits (world facts consumed by the ingest layer) ---

    public double reachSurvivalLimit() {
        return config.getDouble("reach.survival-limit", 3.0);
    }

    public double reachCreativeLimit() {
        return config.getDouble("reach.creative-limit", 5.0);
    }

    public double reachSpearLimit() {
        return config.getDouble("reach.spear-limit", 5.0);
    }

    public Set<String> exemptWorlds() {
        return exemptWorlds;
    }

    public long joinGraceNanos() {
        return joinGraceNanos;
    }

    public long postTeleportGraceNanos() {
        return postTeleportGraceNanos;
    }

    public long postRespawnGraceNanos() {
        return postRespawnGraceNanos;
    }

    // --- punishment ---

    public boolean punishmentEnabled() {
        return config.getBoolean("punishment.enabled", false);
    }

    public double punishmentThreshold() {
        return config.getDouble("punishment.threshold", 97.0);
    }

    public String punishmentCommand() {
        return config.getString("punishment.command", "");
    }

    // --- ledger ---

    public String ledgerBackend() {
        return config.getString("ledger.backend", "sqlite");
    }

    public String sqliteFile() {
        return config.getString("ledger.sqlite.file", "ledger.db");
    }

    public String mysqlJdbcUrl() {
        return "jdbc:mysql://" + config.getString("ledger.mysql.host", "localhost")
                + ":" + config.getInt("ledger.mysql.port", 3306)
                + "/" + config.getString("ledger.mysql.database", "obsidian");
    }

    public String mysqlUsername() {
        return config.getString("ledger.mysql.username", "obsidian");
    }

    public String mysqlPassword() {
        return config.getString("ledger.mysql.password", "");
    }

    public int flushIntervalSeconds() {
        return config.getInt("ledger.flush-interval-seconds", 5);
    }

    public int flushBatchSize() {
        return config.getInt("ledger.flush-batch-size", 50);
    }

    // --- integrations ---

    public boolean grimEnabled() {
        return config.getBoolean("integrations.grim.enabled", true);
    }

    public double grimBonusPerFlag() {
        return config.getDouble("integrations.grim.bonus-per-flag", 5.0);
    }

    public double grimMaxTotalBonus() {
        return config.getDouble("integrations.grim.max-total-bonus", 15.0);
    }

    public boolean totemGuardEnabled() {
        return config.getBoolean("integrations.totemguard.enabled", true);
    }

    public double totemGuardBonusPerFlag() {
        return config.getDouble("integrations.totemguard.bonus-per-flag", 5.0);
    }

    public double totemGuardMaxTotalBonus() {
        return config.getDouble("integrations.totemguard.max-total-bonus", 15.0);
    }

    public boolean webhookEnabled() {
        return config.getBoolean("integrations.discord-webhook.enabled", false);
    }

    public String webhookUrl() {
        return config.getString("integrations.discord-webhook.url", "");
    }

    public boolean bstatsEnabled() {
        return config.getBoolean("metrics.bstats", true);
    }

    public boolean debugTimeline() {
        return config.getBoolean("debug.log-timeline", false);
    }

    // --- checks / messages raw access ---

    public ConfigurationSection checkSection(String checkId) {
        ConfigurationSection section = checks.getConfigurationSection("checks." + checkId);
        return section != null ? section : checks.createSection("checks." + checkId);
    }

    public String message(String path) {
        return messages.getString(path, "<red>missing message: " + path + "</red>");
    }
}
