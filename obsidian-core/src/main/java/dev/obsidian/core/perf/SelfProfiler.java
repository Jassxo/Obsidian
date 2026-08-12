package dev.obsidian.core.perf;

import dev.obsidian.core.ObsidianPlugin;

import java.util.concurrent.atomic.LongAdder;

/**
 * The engine measures itself. Check dispatch time is accumulated per tick;
 * if the average cost blows past the budget for a sustained stretch,
 * experimental checks are switched off until it recovers.
 *
 * <p>Budget: 0.5% of a 50ms tick = 250µs across ALL players.</p>
 */
public final class SelfProfiler {

    private static final long BUDGET_NANOS_PER_TICK = 250_000; // 250µs
    private static final int OVER_BUDGET_TICKS_BEFORE_DEGRADE = 100;
    private static final int UNDER_BUDGET_TICKS_BEFORE_RESTORE = 1200; // ~1 min

    private final ObsidianPlugin plugin;
    private final LongAdder nanosThisTick = new LongAdder();

    private volatile double avgMicrosPerTick;
    private int overBudgetStreak;
    private int underBudgetStreak;
    private boolean degraded;

    public SelfProfiler(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called from packet threads around check dispatch. */
    public void record(long nanos) {
        nanosThisTick.add(nanos);
    }

    /** Called once per tick on the global scheduler. */
    public void tick() {
        long nanos = nanosThisTick.sumThenReset();
        // EMA so /ob stats shows something stable.
        avgMicrosPerTick = avgMicrosPerTick * 0.95 + (nanos / 1000.0) * 0.05;

        if (nanos > BUDGET_NANOS_PER_TICK) {
            overBudgetStreak++;
            underBudgetStreak = 0;
            if (!degraded && overBudgetStreak >= OVER_BUDGET_TICKS_BEFORE_DEGRADE) {
                degraded = true;
                plugin.checkManager().degradeExperimental(true);
                plugin.getLogger().warning("Engine over budget (" + Math.round(avgMicrosPerTick)
                        + "µs/tick avg); experimental checks disabled until load recovers.");
            }
        } else {
            underBudgetStreak++;
            overBudgetStreak = 0;
            if (degraded && underBudgetStreak >= UNDER_BUDGET_TICKS_BEFORE_RESTORE) {
                degraded = false;
                plugin.checkManager().degradeExperimental(false);
                plugin.getLogger().info("Engine back under budget; experimental checks re-enabled.");
            }
        }
    }

    public double avgMicrosPerTick() {
        return avgMicrosPerTick;
    }

    public boolean isDegraded() {
        return degraded;
    }
}
