package dev.obsidian.core.alert;

import dev.obsidian.api.Signal;
import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.tracker.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff-facing output: alert broadcasts (permission + per-staff toggle),
 * the /ob debug live signal stream, and nothing else. All rendering is
 * MiniMessage from messages.yml.
 */
public final class AlertManager {

    private final ObsidianPlugin plugin;
    /** Staff who toggled alerts OFF (default is on for permission holders). */
    private final Set<UUID> alertsMuted = ConcurrentHashMap.newKeySet();
    /** staff uuid -> player name they are debugging. */
    private final ConcurrentHashMap<UUID, String> debugTargets = new ConcurrentHashMap<>();

    public AlertManager(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean toggleAlerts(UUID staff) {
        if (alertsMuted.remove(staff)) {
            return true;
        }
        alertsMuted.add(staff);
        return false;
    }

    public void setDebugTarget(UUID staff, String playerName) {
        if (playerName == null) {
            debugTargets.remove(staff);
        } else {
            debugTargets.put(staff, playerName);
        }
    }

    public void sendFlag(PlayerData data, String cheat, double confidence, List<Signal> signals) {
        StringBuilder top = new StringBuilder();
        int shown = 0;
        for (Signal signal : signals) {
            if (shown++ == 3) {
                break;
            }
            if (!top.isEmpty()) {
                top.append('\n');
            }
            top.append(signal.checkId()).append(": ").append(signal.evidence());
        }
        Component hover = plugin.messages().render("alerts.flag-hover",
                Placeholder.unparsed("cheat", cheat),
                Placeholder.unparsed("signals", top.toString()));
        Component message = plugin.messages().render("alerts.flag",
                        Placeholder.unparsed("player", data.name()),
                        Placeholder.unparsed("cheat", cheat),
                        Placeholder.unparsed("confidence", String.valueOf(Math.round(confidence))),
                        Placeholder.unparsed("ping", String.valueOf(data.ping.medianPing())),
                        Placeholder.unparsed("mspt", String.valueOf(Math.round(data.lagContext.mspt()))))
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.runCommand("/tp " + data.name()));
        broadcast(message);
    }

    public void sendSuspicious(PlayerData data, String cheat, double confidence) {
        broadcast(plugin.messages().render("alerts.suspicious",
                Placeholder.unparsed("player", data.name()),
                Placeholder.unparsed("cheat", cheat),
                Placeholder.unparsed("confidence", String.valueOf(Math.round(confidence)))));
    }

    /** Live per-signal stream for staff who ran /ob debug <player>. */
    public void sendDebug(PlayerData data, Signal signal, double confidence) {
        if (debugTargets.isEmpty()) {
            return;
        }
        Component line = null;
        for (var entry : debugTargets.entrySet()) {
            if (!entry.getValue().equalsIgnoreCase(data.name())) {
                continue;
            }
            Player staff = plugin.getServer().getPlayer(entry.getKey());
            if (staff == null) {
                continue;
            }
            if (line == null) {
                line = plugin.messages().raw(
                        "<dark_gray>[debug]</dark_gray> <gray><check> s=<strength> -> <confidence>% | <evidence></gray>",
                        Placeholder.unparsed("check", signal.checkId()),
                        Placeholder.unparsed("strength", String.valueOf(Math.round(signal.strength() * 100) / 100.0)),
                        Placeholder.unparsed("confidence", String.valueOf(Math.round(confidence))),
                        Placeholder.unparsed("evidence", signal.evidence()));
            }
            staff.sendMessage(line);
        }
    }

    private void broadcast(Component message) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("obsidian.alerts") && !alertsMuted.contains(online.getUniqueId())) {
                online.sendMessage(message);
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(message);
    }
}
