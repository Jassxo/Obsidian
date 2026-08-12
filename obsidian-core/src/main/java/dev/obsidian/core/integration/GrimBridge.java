package dev.obsidian.core.integration;

import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Config-driven bridge to GrimAC without a compile-time dependency: if Grim is
 * installed, we register for its FlagEvent dynamically and add a small, capped
 * confidence bonus to the same player. Corroboration, never conviction — the
 * cap (default +15) keeps external ACs from ever flagging someone on their own.
 *
 * <p>Accessors are resolved once at startup. The reflective calls that remain
 * run only when Grim flags someone — a few times an hour, nowhere near a hot
 * path.</p>
 */
public final class GrimBridge implements Listener {

    private final ObsidianPlugin plugin;
    private Method getPlayer;
    private Method getUniqueId;

    public GrimBridge(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    /** Returns true if the bridge attached. */
    public boolean tryAttach() {
        if (!plugin.configs().grimEnabled()
                || plugin.getServer().getPluginManager().getPlugin("GrimAC") == null) {
            return false;
        }
        try {
            Class<?> eventClass = Class.forName("ac.grim.grimac.api.events.FlagEvent");
            getPlayer = eventClass.getMethod("getPlayer");
            getUniqueId = getPlayer.getReturnType().getMethod("getUniqueId");

            plugin.getServer().getPluginManager().registerEvent(
                    eventClass.asSubclass(Event.class), this, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (!eventClass.isInstance(event)) {
                            return;
                        }
                        try {
                            handleGrimFlag(event);
                        } catch (ReflectiveOperationException e) {
                            plugin.getLogger().warning("Grim bridge error: " + e);
                        }
                    },
                    plugin, true);
            return true;
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().info("GrimAC found but its FlagEvent API is unavailable ("
                    + e.getMessage() + "); bridge disabled.");
            return false;
        }
    }

    private void handleGrimFlag(Object event) throws ReflectiveOperationException {
        Object grimPlayer = getPlayer.invoke(event);
        if (grimPlayer == null) {
            return;
        }
        UUID uuid = (UUID) getUniqueId.invoke(grimPlayer);
        PlayerData data = plugin.players().get(uuid);
        if (data == null) {
            return;
        }
        double cap = plugin.configs().grimMaxTotalBonus();
        data.externalBonus = Math.min(cap, data.externalBonus + plugin.configs().grimBonusPerFlag());
    }
}
