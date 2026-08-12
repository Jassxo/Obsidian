package dev.obsidian.core.check;

import dev.obsidian.core.engine.ConfidenceEngine;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Base of every detection. A check never touches packets directly — it consumes
 * the typed action timeline and rotation history — and it never decides
 * anything: it emits weighted signals into the confidence engine.
 *
 * <p>Contract every implementation must honor:
 * <ul>
 *   <li>Every timing comparison goes through {@code data.lagContext.compensateMillis},
 *       and evaluation is skipped when {@code lagContext.shouldSkip()}.</li>
 *   <li>No allocation on the per-action path; per-player state lives in the
 *       preallocated check-state slot.</li>
 *   <li>When in doubt, stay silent. A missed signal is recoverable; a false one
 *       is not.</li>
 * </ul></p>
 */
public abstract class Check {

    private final String id;
    private final String category;
    private final boolean experimentalByDefault;

    protected ConfidenceEngine engine;
    private int slot;

    private boolean enabled = true;
    private boolean experimental;
    private double likelihoodRatio = 2.0;
    /** Runtime kill switch flipped by the self-profiler for experimental checks. */
    private volatile boolean degraded;

    protected Check(String id, String category, boolean experimentalByDefault) {
        this.id = id;
        this.category = category;
        this.experimentalByDefault = experimentalByDefault;
    }

    final void wire(ConfidenceEngine engine, int slot) {
        this.engine = engine;
        this.slot = slot;
    }

    public final void loadConfig(ConfigurationSection section) {
        enabled = section.getBoolean("enabled", true);
        experimental = section.getBoolean("experimental", experimentalByDefault);
        likelihoodRatio = section.getDouble("likelihood-ratio", 2.0);
        loadSettings(section);
    }

    /** Check-specific thresholds from its checks.yml section. */
    protected abstract void loadSettings(ConfigurationSection section);

    /** Called for every action appended to a tracked player's timeline. */
    public void onAction(PlayerData data, ActionRecord action) {
    }

    /** Called for every rotation packet from a tracked player. */
    public void onRotation(PlayerData data) {
    }

    public final boolean isActive() {
        return enabled && !degraded;
    }

    protected final void signal(PlayerData data, double strength, String evidence) {
        engine.applySignal(data, this, Math.min(1.0, Math.max(0.0, strength)), evidence);
    }

    public final String id() {
        return id;
    }

    public final String category() {
        return category;
    }

    public final boolean isExperimental() {
        return experimental;
    }

    public final double likelihoodRatio() {
        return likelihoodRatio;
    }

    public final int slot() {
        return slot;
    }

    public final void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }
}
