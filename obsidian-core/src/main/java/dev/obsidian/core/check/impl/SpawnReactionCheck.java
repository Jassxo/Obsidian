package dev.obsidian.core.check.impl;

import dev.obsidian.core.check.Check;
import dev.obsidian.core.stats.SampleWindow;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

/**
 * CheckA — the AutoCrystal signature. How fast does the player attack a crystal
 * after the spawn packet reached their client?
 *
 * <p>Humans cannot consistently react below ~130-180ms: retinal processing plus
 * motor response has a hard physiological floor, and — the more useful fact —
 * their reactions are NOISY. A σ under ~25ms across 15 reactions does not
 * happen with a hand on a mouse, no matter how good the player is. The σ signal
 * therefore outweighs the floor signal.</p>
 *
 * <p>False-positive guards: evaluation skips under server lag / recent
 * teleports (LagContext), sub-15ms "reactions" are discarded as coincidence
 * (the player was already mid-click when the crystal appeared — spam clicking
 * produces these legitimately), and the floor alone never carries a
 * full-strength signal.</p>
 */
public final class SpawnReactionCheck extends Check {

    private static final String METRIC = "spawn-reaction";

    private int minReactionMs;
    private int sampleWindow;
    private double sigmaFloorMs;
    private int coincidenceFloorMs;

    public SpawnReactionCheck() {
        super("spawn-reaction", "autocrystal", false);
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        minReactionMs = section.getInt("min-reaction-ms", 80);
        sampleWindow = section.getInt("sample-window", 15);
        sigmaFloorMs = section.getDouble("sigma-floor-ms", 25);
        coincidenceFloorMs = section.getInt("coincidence-floor-ms", 15);
    }

    private static final class State {
        SampleWindow reactions;
        int belowBaselineStreak;
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (action.type != ActionType.CRYSTAL_ATTACK || data.lagContext.shouldSkip()) {
            return;
        }
        long seen = data.crystalSeenNanos.get(action.targetEntityId);
        if (seen == Long.MIN_VALUE) {
            return; // never saw the spawn (e.g. crystal existed before join)
        }
        double rawMs = (action.nanoTime - seen) / 1_000_000.0;
        double comp = data.lagContext.compensateMillis(rawMs);
        if (comp < coincidenceFloorMs) {
            return;
        }
        // Reactions past ~2s aren't reactions to the spawn at all.
        if (comp > 2000) {
            return;
        }

        State state = data.checkState(slot(), State::new);
        if (state.reactions == null) {
            state.reactions = new SampleWindow(sampleWindow);
        }
        state.reactions.add(comp);
        engine.recordBaseline(METRIC, comp, data);

        // Sub-signal (a): absolute floor. Weak on its own — genuinely fast
        // players brush the threshold — so strength is proportional, capped.
        if (comp < minReactionMs) {
            double strength = Math.min(0.6, (minReactionMs - comp) / minReactionMs);
            signal(data, strength, "reaction " + fmt(comp) + "ms comp (ping "
                    + data.lagContext.pingMillis() + "ms)");
        }

        // Sub-signal (b): machine-flat variance across the full window. This is
        // the one that matters.
        if (state.reactions.isFull()) {
            double sigma = state.reactions.stdDev();
            double mean = state.reactions.mean();
            if (sigma < sigmaFloorMs && mean < minReactionMs * 2.5) {
                double strength = 0.5 + 0.5 * (1.0 - sigma / sigmaFloorMs);
                signal(data, strength, "sigma " + fmt(sigma) + "ms over "
                        + sampleWindow + " reactions, mean " + fmt(mean) + "ms");
            }
        }

        // Population signal: repeatedly faster than this server's legit P5.
        if (engine.baseline().isBelowP05(METRIC, comp)) {
            if (++state.belowBaselineStreak >= 5) {
                signal(data, 0.4, "below legit P5 " + state.belowBaselineStreak + "x in a row");
                state.belowBaselineStreak = 0;
            }
        } else {
            state.belowBaselineStreak = 0;
        }
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }
}
