package dev.obsidian.core.command;

import dev.obsidian.api.Signal;
import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.check.Check;
import dev.obsidian.core.ledger.Ledger;
import dev.obsidian.core.tracker.PlayerData;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/**
 * /ob alerts|check|history|debug|reload|export|stats
 */
public final class ObCommand implements TabExecutor {

    private static final int HISTORY_PAGE_SIZE = 8;

    private final ObsidianPlugin plugin;

    public ObCommand(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "commands.usage");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "alerts" -> alerts(sender);
            case "check" -> check(sender, args);
            case "history" -> history(sender, args);
            case "debug" -> debug(sender, args);
            case "label" -> label(sender, args);
            case "ml" -> ml(sender);
            case "reload" -> reload(sender);
            case "export" -> export(sender, args);
            case "stats" -> stats(sender);
            default -> send(sender, "commands.usage");
        }
        return true;
    }

    /** /ob label <player> cheat|legit|clear — tags a player for ML dataset collection. */
    private void label(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "commands.label-usage");
            return;
        }
        PlayerData data = findData(args[1]);
        if (data == null) {
            send(sender, "commands.player-not-found");
            return;
        }
        String value = args[2].toLowerCase();
        switch (value) {
            case "cheat", "legit" -> {
                data.datasetLabel = value;
                sender.sendMessage(plugin.messages().render("commands.label-set",
                        Placeholder.unparsed("player", data.name()),
                        Placeholder.unparsed("label", value)));
            }
            case "clear", "none" -> {
                data.datasetLabel = null;
                sender.sendMessage(plugin.messages().render("commands.label-cleared",
                        Placeholder.unparsed("player", data.name())));
            }
            default -> send(sender, "commands.label-usage");
        }
    }

    /** /ob ml — reports the loaded model and whether dataset logging is on. */
    private void ml(CommandSender sender) {
        var mlCheck = plugin.checkManager().mlCheck();
        sender.sendMessage(plugin.messages().render("commands.ml-status",
                Placeholder.unparsed("model", mlCheck.modelId()),
                Placeholder.unparsed("logging", mlCheck.datasetLogging() ? "on" : "off")));
    }

    private void alerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return;
        }
        boolean on = plugin.alerts().toggleAlerts(player.getUniqueId());
        send(sender, on ? "alerts.toggled-on" : "alerts.toggled-off");
    }

    private void check(CommandSender sender, String[] args) {
        PlayerData data = args.length > 1 ? findData(args[1]) : null;
        if (data == null) {
            send(sender, "commands.player-not-found");
            return;
        }
        double confidence = plugin.engine().confidence(data);
        sender.sendMessage(plugin.messages().render("commands.check-header",
                Placeholder.unparsed("player", data.name()),
                Placeholder.unparsed("confidence", String.valueOf(Math.round(confidence)))));

        List<Signal> signals = data.suspicion.signalSnapshot();
        if (signals.isEmpty()) {
            send(sender, "commands.check-empty");
            return;
        }
        for (Check checkDef : plugin.checkManager().all()) {
            Signal latest = signals.stream()
                    .filter(s -> s.checkId().equals(checkDef.id()))
                    .findFirst().orElse(null);
            if (latest != null) {
                sender.sendMessage(plugin.messages().render("commands.check-line",
                        Placeholder.unparsed("check", checkDef.id()),
                        Placeholder.unparsed("detail", latest.evidence())));
            }
        }
    }

    private void history(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "commands.usage");
            return;
        }
        String name = args[1];
        int page = args.length > 2 ? parseIntOr(args[2], 1) : 1;
        plugin.scheduler().runAsync(() -> {
            List<Ledger.HistoryRow> rows = plugin.ledger().history(name, 200);
            if (rows.isEmpty()) {
                sender.sendMessage(plugin.messages().render("commands.history-empty",
                        Placeholder.unparsed("player", name)));
                return;
            }
            int pages = (rows.size() + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE;
            int safePage = Math.max(1, Math.min(page, pages));
            sender.sendMessage(plugin.messages().render("commands.history-header",
                    Placeholder.unparsed("player", name),
                    Placeholder.unparsed("page", String.valueOf(safePage)),
                    Placeholder.unparsed("pages", String.valueOf(pages))));
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
            rows.stream()
                    .skip((long) (safePage - 1) * HISTORY_PAGE_SIZE)
                    .limit(HISTORY_PAGE_SIZE)
                    .forEach(row -> sender.sendMessage(plugin.messages().render("commands.history-line",
                            Placeholder.unparsed("time", fmt.format(new Date(row.timestampMillis()))),
                            Placeholder.unparsed("confidence", String.valueOf(Math.round(row.confidence()))),
                            Placeholder.unparsed("ping", String.valueOf(row.ping())))));
        });
    }

    private void debug(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return;
        }
        if (args.length < 2) {
            plugin.alerts().setDebugTarget(player.getUniqueId(), null);
            send(sender, "commands.debug-off");
            return;
        }
        plugin.alerts().setDebugTarget(player.getUniqueId(), args[1]);
        sender.sendMessage(plugin.messages().render("commands.debug-on",
                Placeholder.unparsed("player", args[1])));
    }

    private void reload(CommandSender sender) {
        plugin.configs().load();
        plugin.checkManager().loadConfigs();
        send(sender, "commands.reloaded");
    }

    private void export(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "commands.usage");
            return;
        }
        String name = args[1];
        plugin.scheduler().runAsync(() -> {
            try {
                var file = plugin.ledger().exportPlayer(name);
                sender.sendMessage(plugin.messages().render("commands.export-done",
                        Placeholder.unparsed("file", file.getName())));
            } catch (Exception e) {
                sender.sendMessage(plugin.messages().render("commands.export-failed",
                        Placeholder.unparsed("reason", String.valueOf(e.getMessage()))));
            }
        });
    }

    private void stats(CommandSender sender) {
        sender.sendMessage(plugin.messages().render("commands.stats",
                Placeholder.unparsed("flags-today", String.valueOf(plugin.ledger().flagsToday())),
                Placeholder.unparsed("baseline-samples", String.valueOf(plugin.engine().baseline().totalSamples())),
                Placeholder.unparsed("overhead", String.valueOf(Math.round(plugin.profiler().avgMicrosPerTick())))));
    }

    private PlayerData findData(String name) {
        Player target = plugin.getServer().getPlayerExact(name);
        return target == null ? null : plugin.players().get(target.getUniqueId());
    }

    private void send(CommandSender sender, String path) {
        sender.sendMessage(plugin.messages().render(path));
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("alerts", "check", "history", "debug", "label", "ml", "reload", "export", "stats")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && Stream.of("check", "history", "debug", "export", "label")
                .anyMatch(s -> s.equalsIgnoreCase(args[0]))) {
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("label")) {
            return Stream.of("cheat", "legit", "clear")
                    .filter(s -> s.startsWith(args[2].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
