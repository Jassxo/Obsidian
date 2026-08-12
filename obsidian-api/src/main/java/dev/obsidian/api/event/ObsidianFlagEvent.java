package dev.obsidian.api.event;

import dev.obsidian.api.Signal;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Fired asynchronously when a player's confidence crosses the flag threshold.
 *
 * <p>Immutable. Obsidian itself only alerts staff and writes to the ledger when this
 * fires; any punishment behavior is opt-in configuration.</p>
 */
public class ObsidianFlagEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final double confidence;
    private final List<Signal> signals;
    private final int pingMillis;
    private final double mspt;

    public ObsidianFlagEvent(UUID playerId, String playerName, double confidence,
                             List<Signal> signals, int pingMillis, double mspt) {
        super(true);
        this.playerId = playerId;
        this.playerName = playerName;
        this.confidence = confidence;
        this.signals = Collections.unmodifiableList(signals);
        this.pingMillis = pingMillis;
        this.mspt = mspt;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public double getConfidence() {
        return confidence;
    }

    /**
     * Signals contributing to this flag, strongest first.
     */
    public List<Signal> getSignals() {
        return signals;
    }

    public int getPingMillis() {
        return pingMillis;
    }

    public double getMspt() {
        return mspt;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
