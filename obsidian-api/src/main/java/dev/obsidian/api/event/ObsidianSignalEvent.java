package dev.obsidian.api.event;

import dev.obsidian.api.Signal;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired asynchronously every time a check emits a signal against a player.
 *
 * <p>This event is informational and immutable; Obsidian never acts on a single
 * signal, and neither should consumers.</p>
 */
public class ObsidianSignalEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final Signal signal;
    private final double confidenceAfter;

    public ObsidianSignalEvent(UUID playerId, Signal signal, double confidenceAfter) {
        super(true);
        this.playerId = playerId;
        this.signal = signal;
        this.confidenceAfter = confidenceAfter;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public Signal getSignal() {
        return signal;
    }

    /**
     * @return the player's confidence score after this signal was applied, 0&ndash;100
     */
    public double getConfidenceAfter() {
        return confidenceAfter;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
