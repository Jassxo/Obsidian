package dev.obsidian.core.integration;

import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.tracker.PlayerData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI expansion: %obsidian_confidence% and %obsidian_flags%.
 */
public final class PlaceholderHook extends PlaceholderExpansion {

    private final ObsidianPlugin plugin;

    public PlaceholderHook(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "obsidian";
    }

    @Override
    public String getAuthor() {
        return "Obsidian";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        PlayerData data = plugin.players().get(player.getUniqueId());
        return switch (params.toLowerCase()) {
            case "confidence" -> data == null ? "0"
                    : String.valueOf(Math.round(data.suspicion.confidence()));
            case "flags" -> data == null ? "0"
                    : String.valueOf(data.suspicion.signalSnapshot().size());
            default -> null;
        };
    }
}
