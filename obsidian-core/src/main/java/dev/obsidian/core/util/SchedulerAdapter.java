package dev.obsidian.core.util;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Thin region-aware scheduling layer. Paper 1.20+ ships the Folia scheduler
 * interfaces on both Paper and Folia, so the global-region and async schedulers
 * work identically on either — no reflection, no fork-specific classes.
 */
public final class SchedulerAdapter {

    private final Plugin plugin;

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Global region (the "main thread" equivalent on Folia). */
    public void runGlobal(Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
    }

    /** Repeating global-region task; period in ticks. */
    public void repeatGlobal(Runnable task, long initialTicks, long periodTicks) {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                t -> task.run(), Math.max(1, initialTicks), periodTicks);
    }

    public void runAsync(Runnable task) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public void repeatAsync(Runnable task, long initialSeconds, long periodSeconds) {
        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                t -> task.run(), initialSeconds, periodSeconds, TimeUnit.SECONDS);
    }
}
