package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * CheckC — reaction to a placement opportunity opening up.
 *
 * <p>The dominant opportunity in crystal PvP is packet-derived and needs zero
 * world access: the moment a crystal is destroyed, its base block is placeable
 * again. We record that onset per viewer (when the destroy packet was sent to
 * them) and measure onset -> that player's place packet on the same block.
 * A human has to see the crystal pop, re-confirm aim and click; a module fires
 * the place the instant its placement scan succeeds.</p>
 *
 * <p>Gated behind combat state: outside a fight nobody spam-cycles crystals,
 * and the gate keeps this check off the hot path for 95% of online players.
 * Sub-signals mirror CheckA: floor violations are weak evidence, flat σ across
 * a window is strong evidence.</p>
 */
public final class OpportunityReactionCheck extends Check {

    private static final String METRIC = "opportunity-reaction";

    private int minReactionMs;
    private int sampleWindow;
    private double sigmaFloorMs;
    private long combatWindowNanos;

    public OpportunityReactionCheck() {
        super("opportunity-reaction", "autocrystal", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        minReactionMs = section.getInt("min-reaction-ms", 100);
        sampleWindow = section.getInt("sample-window", 12);
        sigmaFloorMs = section.getDouble("sigma-floor-ms", 30);
        combatWindowNanos = section.getLong("combat-window-seconds", 10) * 1_000_000_000L;
    }

    private static final class State {
        SampleWindow reactions;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type != ActionType.CRYSTAL_PLACE
                || action.targetBlockPos == Long.MIN_VALUE
                || data.lagContext.shouldSkip()
                || !data.inCombat(action.nanoTime, combatWindowNanos)) {
            return;
        }
        long onset = data.consumeOpportunity(action.targetBlockPos);
        if (onset == Long.MIN_VALUE) {
            return;
        }
        double rawMs = (action.nanoTime - onset) / 1_000_000.0;
        double comp = data.lagContext.compensateMillis(rawMs);
        // Below ~10ms the place was already in flight before the pop reached
        // the client — pre-spamming placements is legit and common.
        if (comp < 10 || comp > 1500) {
            return;
        }

        State state = data.checkState(slot(), State::new);
        if (state.reactions == null) {
            state.reactions = new SampleWindow(sampleWindow);
        }
        state.reactions.add(comp);
        engine.recordBaseline(METRIC, comp, data);

        if (comp < minReactionMs) {
            double strength = Math.min(0.5, (minReactionMs - comp) / minReactionMs);
            signal(data, strength, "opportunity reaction " + Math.round(comp) + "ms comp");
        }
        if (state.reactions.isFull()) {
            double sigma = state.reactions.stdDev();
            double mean = state.reactions.mean();
            if (sigma < sigmaFloorMs && mean < minReactionMs * 2) {
                double strength = 0.5 + 0.4 * (1.0 - sigma / sigmaFloorMs);
                signal(data, strength, "opportunity sigma " + Math.round(sigma)
                        + "ms over " + sampleWindow + ", mean " + Math.round(mean) + "ms");
            }
        }
    }
}
