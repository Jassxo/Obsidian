package dev.obsidian.core;

import com.github.retrooper.packetevents.PacketEvents;
import dev.obsidian.api.ObsidianApi;
import dev.obsidian.api.ObsidianApiProvider;
import dev.obsidian.core.alert.AlertManager;
import dev.obsidian.core.check.CheckManager;
import dev.obsidian.core.command.ObCommand;
import dev.obsidian.core.config.ConfigManager;
import dev.obsidian.core.config.Messages;
import dev.obsidian.core.engine.ConfidenceEngine;
import dev.obsidian.core.integration.DiscordWebhook;
import dev.obsidian.core.integration.GrimBridge;
import dev.obsidian.core.integration.PlaceholderHook;
import dev.obsidian.core.lag.TpsMonitor;
import dev.obsidian.core.ledger.Ledger;
import dev.obsidian.core.packet.PacketIngestListener;
import dev.obsidian.core.perf.SelfProfiler;
import dev.obsidian.core.tracker.CrystalTracker;
import dev.obsidian.core.tracker.ExemptionEngine;
import dev.obsidian.core.tracker.PlayerTracker;
import dev.obsidian.core.util.SchedulerAdapter;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

/**
 * Wiring only — every capability lives in its own component. Startup order
 * matters: config -> engine -> checks -> trackers -> ledger -> packets.
 */
public final class ObsidianPlugin extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 21987;

    private SchedulerAdapter scheduler;
    private ConfigManager configManager;
    private Messages messages;
    private TpsMonitor tpsMonitor;
    private SelfProfiler profiler;
    private ConfidenceEngine engine;
    private CheckManager checkManager;
    private PlayerTracker playerTracker;
    private CrystalTracker crystalTracker;
    private ExemptionEngine exemptionEngine;
    private AlertManager alertManager;
    private Ledger ledger;
    private DiscordWebhook webhook;
    private ObsidianApi api;

    @Override
    public void onEnable() {
        scheduler = new SchedulerAdapter(this);
        configManager = new ConfigManager(this);
        configManager.load();
        messages = new Messages(configManager);

        tpsMonitor = new TpsMonitor();
        profiler = new SelfProfiler(this);
        engine = new ConfidenceEngine(this);
        checkManager = new CheckManager(this);
        checkManager.loadConfigs();

        crystalTracker = new CrystalTracker();
        exemptionEngine = new ExemptionEngine(configManager);
        playerTracker = new PlayerTracker(this);
        alertManager = new AlertManager(this);
        webhook = new DiscordWebhook(this);

        ledger = new Ledger(this);
        try {
            ledger.start();
        } catch (SQLException e) {
            getLogger().severe("Ledger failed to start (" + e.getMessage()
                    + "); flags will not persist this session.");
        }

        getServer().getPluginManager().registerEvents(playerTracker, this);
        PacketEvents.getAPI().getEventManager().registerListener(new PacketIngestListener(this));

        getCommand("obsidian").setExecutor(new ObCommand(this));

        scheduler.repeatGlobal(() -> {
            tpsMonitor.tick();
            profiler.tick();
        }, 1, 1);

        api = new ObsidianApiImpl(this);
        ObsidianApiProvider.register(api);
        getServer().getServicesManager().register(ObsidianApi.class, api, this, ServicePriority.Normal);

        if (new GrimBridge(this).tryAttach()) {
            getLogger().info("GrimAC bridge attached (capped corroboration bonus).");
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(this).register();
        }
        if (configManager.bstatsEnabled()) {
            new Metrics(this, BSTATS_PLUGIN_ID);
        }

        getLogger().info("Obsidian enabled - passive crystal PvP analysis online.");
    }

    @Override
    public void onDisable() {
        ObsidianApiProvider.unregister();
        if (checkManager != null) {
            checkManager.shutdown();
        }
        if (ledger != null) {
            ledger.shutdown();
        }
    }

    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    public ConfigManager configs() {
        return configManager;
    }

    public Messages messages() {
        return messages;
    }

    public TpsMonitor tps() {
        return tpsMonitor;
    }

    public SelfProfiler profiler() {
        return profiler;
    }

    public ConfidenceEngine engine() {
        return engine;
    }

    public CheckManager checkManager() {
        return checkManager;
    }

    public PlayerTracker players() {
        return playerTracker;
    }

    public CrystalTracker crystals() {
        return crystalTracker;
    }

    public ExemptionEngine exemptions() {
        return exemptionEngine;
    }

    public AlertManager alerts() {
        return alertManager;
    }

    public Ledger ledger() {
        return ledger;
    }

    public DiscordWebhook webhook() {
        return webhook;
    }
}
