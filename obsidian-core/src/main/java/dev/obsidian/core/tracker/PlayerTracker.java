package dev.obsidian.core.tracker;

import dev.obsidian.core.ObsidianPlugin;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the UUID -> PlayerData map and keeps the exemption-relevant flags on it
 * fresh from main-thread Bukkit events.
 */
public final class PlayerTracker implements Listener {

    private final ObsidianPlugin plugin;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

    public PlayerTracker(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    public PlayerData get(UUID uuid) {
        return players.get(uuid);
    }

    public Collection<PlayerData> all() {
        return players.values();
    }

    private void refreshExemptionFlags(Player player, PlayerData data) {
        data.bypassPermission = player.hasPermission("obsidian.bypass");
        GameMode mode = player.getGameMode();
        data.exemptGameMode = mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
        data.exemptWorld = plugin.configs().exemptWorlds().contains(player.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = new PlayerData(player.getUniqueId(), player.getName(),
                plugin.checkManager().slotCount());
        refreshExemptionFlags(player, data);
        players.put(player.getUniqueId(), data);
        plugin.ledger().loadProfileAsync(data.uuid());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerData data = players.remove(event.getPlayer().getUniqueId());
        if (data != null) {
            plugin.engine().onQuit(data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData data = players.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.lastTeleportNanos = System.nanoTime();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData data = players.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.lastRespawnNanos = System.nanoTime();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData data = players.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.lastTeleportNanos = System.nanoTime();
            refreshExemptionFlags(event.getPlayer(), data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PlayerData data = players.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.exemptGameMode = event.getNewGameMode() == GameMode.CREATIVE
                    || event.getNewGameMode() == GameMode.SPECTATOR;
        }
    }

    /** Combat state: any damage dealt or taken between players (or via crystals). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        long now = System.nanoTime();
        if (event.getEntity() instanceof Player victim) {
            PlayerData data = players.get(victim.getUniqueId());
            if (data != null) {
                data.lastCombatNanos = now;
            }
        }
        if (event.getDamager() instanceof Player attacker) {
            PlayerData data = players.get(attacker.getUniqueId());
            if (data != null) {
                data.lastCombatNanos = now;
            }
        }
    }
}
