package dev.obsidian.core.tracker;

import dev.obsidian.core.config.ConfigManager;

/**
 * Central "should we even look at this player right now" decision. Everything
 * here errs toward exempting: a skipped evaluation costs nothing, a false
 * positive costs trust.
 */
public final class ExemptionEngine {

    private final ConfigManager config;

    public ExemptionEngine(ConfigManager config) {
        this.config = config;
    }

    public boolean isExempt(PlayerData data, long nowNanos) {
        if (data.bypassPermission || data.exemptGameMode || data.exemptWorld) {
            return true;
        }
        if (nowNanos - data.joinNanos < config.joinGraceNanos()) {
            return true;
        }
        long teleport = data.lastTeleportNanos;
        if (teleport > 0 && nowNanos - teleport < config.postTeleportGraceNanos()) {
            return true;
        }
        long respawn = data.lastRespawnNanos;
        return respawn > 0 && nowNanos - respawn < config.postRespawnGraceNanos();
    }
}
