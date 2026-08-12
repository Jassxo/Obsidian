package dev.obsidian.core.integration;

import dev.obsidian.api.Signal;
import dev.obsidian.core.ObsidianPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Optional Discord embed on FLAG. Fire-and-forget over the JDK async HTTP
 * client; a dead webhook must never back-pressure anything.
 */
public final class DiscordWebhook {

    private final ObsidianPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public DiscordWebhook(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    public void sendFlagAsync(String playerName, double confidence, List<Signal> signals,
                              int ping, double mspt) {
        if (!plugin.configs().webhookEnabled()) {
            return;
        }
        String url = plugin.configs().webhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }

        StringBuilder fields = new StringBuilder();
        int shown = 0;
        for (Signal signal : signals) {
            if (shown++ == 3) {
                break;
            }
            if (!fields.isEmpty()) {
                fields.append(',');
            }
            fields.append("{\"name\":\"").append(escape(signal.checkId()))
                    .append("\",\"value\":\"").append(escape(signal.evidence()))
                    .append("\",\"inline\":false}");
        }
        String json = "{\"embeds\":[{"
                + "\"title\":\"Obsidian flag: " + escape(playerName) + "\","
                + "\"color\":11815152,"
                + "\"description\":\"Confidence **" + Math.round(confidence) + "%** | ping "
                + ping + "ms | mspt " + Math.round(mspt) + "\","
                + "\"fields\":[" + fields + "]}]}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(t -> {
                    plugin.getLogger().warning("Discord webhook failed: " + t.getMessage());
                    return null;
                });
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
