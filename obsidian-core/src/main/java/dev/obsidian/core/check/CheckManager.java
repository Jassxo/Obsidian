package dev.obsidian.core.check;

import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.check.impl.AimConsistencyCheck;
import dev.obsidian.core.check.impl.AimSnapCheck;
import dev.obsidian.core.check.impl.AnchorCycleCheck;
import dev.obsidian.core.check.impl.AttackConsistencyCheck;
import dev.obsidian.core.check.impl.GcdRotationCheck;
import dev.obsidian.core.check.impl.HitWhileNotLookingCheck;
import dev.obsidian.core.check.impl.MaceSmashCheck;
import dev.obsidian.core.check.impl.MultiAuraCheck;
import dev.obsidian.core.check.impl.OpportunityReactionCheck;
import dev.obsidian.core.check.impl.PlaceBreakCycleCheck;
import dev.obsidian.core.check.impl.ReachCheck;
import dev.obsidian.core.check.impl.SnapRotationCheck;
import dev.obsidian.core.check.impl.SpawnReactionCheck;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.PlayerData;

import java.util.List;

/**
 * Registry and dispatcher. Dispatch happens inline on the player's packet
 * thread right after the timeline is appended; the self-profiler wraps the
 * whole loop.
 */
public final class CheckManager {

    private final ObsidianPlugin plugin;
    private final Check[] checks;

    public CheckManager(ObsidianPlugin plugin) {
        this.plugin = plugin;
        this.checks = new Check[]{
                // crystal PvP (v1)
                new SpawnReactionCheck(),
                new PlaceBreakCycleCheck(),
                new OpportunityReactionCheck(),
                new AnchorCycleCheck(),
                new SnapRotationCheck(),
                new GcdRotationCheck(),
                new AimConsistencyCheck(),
                // general combat (v2)
                new ReachCheck(),
                new HitWhileNotLookingCheck(),
                new MultiAuraCheck(),
                new AttackConsistencyCheck(),
                new AimSnapCheck(),
                new MaceSmashCheck()
        };
        for (int i = 0; i < checks.length; i++) {
            checks[i].wire(plugin.engine(), i);
        }
    }

    public void loadConfigs() {
        for (Check check : checks) {
            check.loadConfig(plugin.configs().checkSection(check.id()));
        }
    }

    public int slotCount() {
        return checks.length;
    }

    public List<Check> all() {
        return List.of(checks);
    }

    public void dispatchAction(PlayerData data, ActionRecord action) {
        long start = System.nanoTime();
        for (Check check : checks) {
            if (check.isActive()) {
                check.onAction(data, action);
            }
        }
        plugin.profiler().record(System.nanoTime() - start);
    }

    public void dispatchRotation(PlayerData data) {
        long start = System.nanoTime();
        for (Check check : checks) {
            if (check.isActive()) {
                check.onRotation(data);
            }
        }
        plugin.profiler().record(System.nanoTime() - start);
    }

    /** Called by the self-profiler when the engine exceeds its budget. */
    public void degradeExperimental(boolean degraded) {
        for (Check check : checks) {
            if (check.isExperimental()) {
                check.setDegraded(degraded);
            }
        }
    }
}
