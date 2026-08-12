package dev.obsidian.api;

import java.util.List;
import java.util.UUID;

/**
 * Public entry point into Obsidian's detection state.
 *
 * <p>Obtain the instance via {@link ObsidianApiProvider#get()} once Obsidian has enabled,
 * or through Bukkit's ServicesManager.</p>
 *
 * <p>All methods are safe to call from any thread and never block.</p>
 */
public interface ObsidianApi {

    /**
     * Current suspicion confidence for a player.
     *
     * @return confidence in {@code [0.0, 100.0]}, or {@code 0.0} for unknown/offline players
     */
    double getConfidence(UUID playerId);

    /**
     * Recent signals recorded against a player, newest first. The returned list is an
     * immutable snapshot.
     */
    List<Signal> getSignals(UUID playerId);

    /**
     * Registers a listener fired whenever a player reaches the flag threshold.
     */
    void registerFlagListener(FlagListener listener);

    /**
     * Unregisters a previously registered flag listener.
     */
    void unregisterFlagListener(FlagListener listener);
}
