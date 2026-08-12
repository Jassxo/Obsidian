package dev.obsidian.core.lag;

/**
 * Rolling MSPT from tick-to-tick nano deltas. Fed by a global repeating task;
 * read from netty threads, hence the volatile.
 */
public final class TpsMonitor {

    /** Exponential moving average smoothing; ~last 20 ticks dominate. */
    private static final double ALPHA = 0.1;

    private long lastTickNanos = -1;
    private volatile double mspt = 50.0;

    /** Called once per tick on the global scheduler. */
    public void tick() {
        long now = System.nanoTime();
        if (lastTickNanos > 0) {
            double elapsed = (now - lastTickNanos) / 1_000_000.0;
            // A tick can't take less than 50ms of wall time on a healthy server;
            // faster deltas mean catch-up ticks, which we treat as 50.
            double sample = Math.max(50.0, elapsed);
            mspt = mspt + ALPHA * (sample - mspt);
        }
        lastTickNanos = now;
    }

    public double mspt() {
        return mspt;
    }
}
