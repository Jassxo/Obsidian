package dev.obsidian.core;

import dev.obsidian.api.FlagListener;
import dev.obsidian.api.ObsidianApi;
import dev.obsidian.api.Signal;
import dev.obsidian.core.tracker.PlayerData;

import java.util.List;
import java.util.UUID;

/**
 * The published API surface, backed directly by the live tracker/engine.
 */
final class ObsidianApiImpl implements ObsidianApi {

    private final ObsidianPlugin plugin;

    ObsidianApiImpl(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public double getConfidence(UUID playerId) {
        PlayerData data = plugin.players().get(playerId);
        return data == null ? 0.0 : data.suspicion.confidence();
    }

    @Override
    public List<Signal> getSignals(UUID playerId) {
        PlayerData data = plugin.players().get(playerId);
        return data == null ? List.of() : data.suspicion.signalSnapshot();
    }

    @Override
    public void registerFlagListener(FlagListener listener) {
        plugin.engine().registerFlagListener(listener);
    }

    @Override
    public void unregisterFlagListener(FlagListener listener) {
        plugin.engine().unregisterFlagListener(listener);
    }
}
