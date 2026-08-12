package dev.obsidian.api;

import java.util.List;
import java.util.UUID;

/**
 * Callback invoked when a player crosses the flag threshold (default 90% confidence).
 *
 * <p>Listeners are invoked asynchronously; never touch the Bukkit API from the callback
 * without scheduling back onto the appropriate thread.</p>
 */
@FunctionalInterface
public interface FlagListener {

    /**
     * @param playerId   flagged player
     * @param confidence confidence score at flag time, 0&ndash;100
     * @param signals    snapshot of the signals that contributed to the flag,
     *                   strongest first
     */
    void onFlag(UUID playerId, double confidence, List<Signal> signals);
}
