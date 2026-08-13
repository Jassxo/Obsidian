package dev.obsidian.core.engine;

import dev.obsidian.api.FlagListener;
import dev.obsidian.api.Signal;
import dev.obsidian.api.event.ObsidianFlagEvent;
import dev.obsidian.api.event.ObsidianSignalEvent;
import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.check.Check;
import dev.obsidian.core.tracker.PlayerData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Turns individual check signals into a per-player confidence score and walks
 * the verdict ladder: watch -> suspicious alert -> flag (+ ledger) ->
 * optional punishment. All thresholds and the decay half-life come from
 * config.yml.
 */
public final class ConfidenceEngine {

    /** Minimum gap between repeated staff alerts for the same player. */
    private static final long SUSPICIOUS_ALERT_COOLDOWN_NANOS = 60_000_000_000L;
    private static final long FLAG_COOLDOWN_NANOS = 120_000_000_000L;

    private final ObsidianPlugin plugin;
    private final BaselineProfile baseline = new BaselineProfile();
    private final Set<FlagListener> flagListeners = new CopyOnWriteArraySet<>();

    public ConfidenceEngine(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    public BaselineProfile baseline() {
        return baseline;
    }

    /**
     * Entry point for every signal a check emits. Runs on the player's packet
     * thread; everything heavier than arithmetic is handed off async.
     */
    public void applySignal(PlayerData data, Check check, double strength, String evidence) {
        long now = System.nanoTime();
        Signal signal = new Signal(check.id(), strength, evidence, System.currentTimeMillis());
        data.suspicion.pushSignal(signal);

        double confidence = data.suspicion.applySignal(
                check.likelihoodRatio(), strength, now,
                plugin.configs().decayHalfLifeSeconds(), data.externalBonus);

        plugin.alerts().sendDebug(data, signal, confidence);
        fireSignalEvent(data, signal, confidence);

        if (confidence >= plugin.configs().flagThreshold()) {
            maybeFlag(data, confidence, now);
        } else if (confidence >= plugin.configs().suspiciousThreshold()) {
            maybeSuspicious(data, confidence, now);
        }
    }

    /** Record a metric into the server-wide legit baseline. */
    public void recordBaseline(String metricId, double sample, PlayerData data) {
        baseline.record(metricId, sample, data.suspicion.confidence(),
                plugin.configs().baselineMaxConfidence());
    }

    public double confidence(PlayerData data) {
        return data.suspicion.refresh(System.nanoTime(),
                plugin.configs().decayHalfLifeSeconds(), data.externalBonus);
    }

    /** True once the player is past the watch threshold (denser sampling). */
    public boolean isWatched(PlayerData data) {
        return data.suspicion.confidence() >= plugin.configs().watchThreshold();
    }

    // --- verdict ladder ---

    private void maybeSuspicious(PlayerData data, double confidence, long now) {
        if (data.lastSuspiciousAlertNanos != Long.MIN_VALUE
                && now - data.lastSuspiciousAlertNanos < SUSPICIOUS_ALERT_COOLDOWN_NANOS) {
            return;
        }
        data.lastSuspiciousAlertNanos = now;
        String cheat = predictCheat(data.suspicion.signalSnapshot());
        plugin.alerts().sendSuspicious(data, cheat, confidence);
    }

    private void maybeFlag(PlayerData data, double confidence, long now) {
        if (data.lastFlagNanos != Long.MIN_VALUE
                && now - data.lastFlagNanos < FLAG_COOLDOWN_NANOS) {
            return;
        }

        List<Signal> signals = data.suspicion.signalSnapshot();

        // Corroboration gate: a flag needs evidence from several independent
        // detection families, not one check firing repeatedly. This is what keeps
        // a single timing/speed measurement from ever convicting on its own. Below
        // the bar we hold the player at "suspicious" and keep watching.
        Set<String> categories = recentCategories(signals);
        if (categories.size() < plugin.configs().flagMinCategories()) {
            maybeSuspicious(data, confidence, now);
            return;
        }

        data.lastFlagNanos = now;
        String cheat = predictCheat(signals);
        int ping = data.ping.medianPing();
        double mspt = data.lagContext.mspt();

        plugin.alerts().sendFlag(data, cheat, confidence, signals);
        plugin.ledger().writeFlagAsync(data, confidence, signals, ping, mspt);
        plugin.webhook().sendFlagAsync(data.name(), cheat, confidence, signals, ping, mspt);

        for (FlagListener listener : flagListeners) {
            try {
                listener.onFlag(data.uuid(), confidence, signals);
            } catch (Throwable t) {
                plugin.getLogger().warning("Flag listener threw: " + t);
            }
        }
        fireFlagEvent(data, confidence, signals, ping, mspt);

        if (plugin.configs().punishmentEnabled()
                && confidence >= plugin.configs().punishmentThreshold()) {
            plugin.scheduler().runGlobal(() -> plugin.getServer().dispatchCommand(
                    plugin.getServer().getConsoleSender(),
                    plugin.configs().punishmentCommand().replace("%player%", data.name())));
        }
    }

    /** Distinct check categories that produced a signal inside the corroboration window. */
    private Set<String> recentCategories(List<Signal> signals) {
        long cutoff = System.currentTimeMillis() - plugin.configs().flagCorroborationWindowMillis();
        Set<String> categories = new HashSet<>();
        for (Signal signal : signals) {
            if (signal.timestampMillis() < cutoff) {
                continue;
            }
            String category = plugin.checkManager().category(signal.checkId());
            if (category != null) {
                categories.add(category);
            }
        }
        return categories;
    }

    /**
     * Names the most likely cheat from the recent signals: the category carrying
     * the most evidence. A concrete deterministic family is preferred over the
     * generic ML anomaly, so a flag reads "Killaura", not "Anomaly", when a real
     * check drove it.
     */
    private String predictCheat(List<Signal> signals) {
        long cutoff = System.currentTimeMillis() - plugin.configs().flagCorroborationWindowMillis();
        Map<String, Double> weight = new HashMap<>();
        for (Signal signal : signals) {
            if (signal.timestampMillis() < cutoff) {
                continue;
            }
            String category = plugin.checkManager().category(signal.checkId());
            if (category != null) {
                weight.merge(category, signal.strength(), Double::sum);
            }
        }
        String best = null;
        double bestWeight = -1;
        String bestDeterministic = null;
        double bestDeterministicWeight = -1;
        for (Map.Entry<String, Double> entry : weight.entrySet()) {
            if (entry.getValue() > bestWeight) {
                bestWeight = entry.getValue();
                best = entry.getKey();
            }
            if (!CheatNames.isCorroborationOnly(entry.getKey())
                    && entry.getValue() > bestDeterministicWeight) {
                bestDeterministicWeight = entry.getValue();
                bestDeterministic = entry.getKey();
            }
        }
        String chosen = best != null && CheatNames.isCorroborationOnly(best) && bestDeterministic != null
                ? bestDeterministic : best;
        return CheatNames.displayFor(chosen);
    }

    private void fireSignalEvent(PlayerData data, Signal signal, double confidence) {
        plugin.scheduler().runAsync(() -> plugin.getServer().getPluginManager()
                .callEvent(new ObsidianSignalEvent(data.uuid(), signal, confidence)));
    }

    private void fireFlagEvent(PlayerData data, double confidence, List<Signal> signals,
                               int ping, double mspt) {
        plugin.scheduler().runAsync(() -> plugin.getServer().getPluginManager()
                .callEvent(new ObsidianFlagEvent(data.uuid(), data.name(), confidence,
                        signals, ping, mspt)));
    }

    // --- API surface ---

    public void registerFlagListener(FlagListener listener) {
        flagListeners.add(listener);
    }

    public void unregisterFlagListener(FlagListener listener) {
        flagListeners.remove(listener);
    }

    public void onQuit(PlayerData data) {
        plugin.ledger().saveProfileAsync(data);
    }
}
