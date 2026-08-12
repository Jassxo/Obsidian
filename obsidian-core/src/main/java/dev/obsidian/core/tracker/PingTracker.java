package dev.obsidian.core.tracker;

import java.util.Arrays;

/**
 * Rolling ping estimate from keepalive/transaction round-trips.
 *
 * <p>The median of the last 20 RTTs is what every check subtracts from its
 * timing measurements. Median, not mean: a single ping spike must not drag the
 * compensation value around, and a single fast RTT must not shrink it.</p>
 *
 * <p>It also exposes how <i>unstable</i> the connection is right now — the jitter
 * of the window and whether a fresh spike just landed. A spiking or jittery
 * connection under-compensates timing checks, so the lag context uses these to
 * make every check stand down until the link settles: stable high ping stays
 * checkable (it compensates cleanly), unstable ping does not.</p>
 */
public final class PingTracker {

    private static final int WINDOW = 20;

    private final int[] rtts = new int[WINDOW];
    private final int[] scratch = new int[WINDOW];
    private int head;
    private int size;
    private volatile int cachedMedian = 50; // sane default until first sample
    private volatile int cachedJitter;      // std-dev of the window, millis
    private volatile long lastSpikeNanos = Long.MIN_VALUE;

    /** A single RTT this much over the current median counts as a spike. */
    private int spikeThresholdMs = 150;

    /** Outstanding keepalive: id -> nanos sent. Single pending id in practice. */
    private long pendingId = Long.MIN_VALUE;
    private long pendingSentNanos;

    public void onKeepAliveSent(long id, long nanos) {
        pendingId = id;
        pendingSentNanos = nanos;
    }

    public void onKeepAliveReceived(long id, long nanos) {
        if (id != pendingId) {
            return;
        }
        pendingId = Long.MIN_VALUE;
        int rtt = (int) Math.min(Integer.MAX_VALUE, (nanos - pendingSentNanos) / 1_000_000L);
        if (rtt < 0 || rtt > 10_000) {
            return; // clock weirdness or a dead connection, not a ping sample
        }
        // Spike detection against the pre-existing median, before this sample
        // shifts it. A sudden jump means the median lags reality, so timing
        // measurements taken now would be under-compensated.
        if (size > 0 && rtt - cachedMedian > spikeThresholdMs) {
            lastSpikeNanos = nanos;
        }
        rtts[head] = rtt;
        head = (head + 1) % WINDOW;
        if (size < WINDOW) {
            size++;
        }
        recompute();
    }

    private void recompute() {
        System.arraycopy(rtts, 0, scratch, 0, size);
        Arrays.sort(scratch, 0, size);
        cachedMedian = size % 2 == 1
                ? scratch[size / 2]
                : (scratch[size / 2 - 1] + scratch[size / 2]) / 2;

        double mean = 0;
        for (int i = 0; i < size; i++) {
            mean += rtts[i];
        }
        mean /= size;
        double sq = 0;
        for (int i = 0; i < size; i++) {
            double d = rtts[i] - mean;
            sq += d * d;
        }
        cachedJitter = (int) Math.sqrt(sq / Math.max(1, size));
    }

    /** Median RTT in millis. Safe to read from any thread. */
    public int medianPing() {
        return cachedMedian;
    }

    /** Standard deviation of the RTT window in millis: how jittery the link is. */
    public int jitterMillis() {
        return cachedJitter;
    }

    /** True if a spike landed within the given grace window ending now. */
    public boolean spikedWithin(long nowNanos, long graceNanos) {
        long last = lastSpikeNanos;
        return last != Long.MIN_VALUE && nowNanos - last < graceNanos;
    }

    public void setSpikeThresholdMs(int spikeThresholdMs) {
        this.spikeThresholdMs = spikeThresholdMs;
    }

    public int samples() {
        return size;
    }
}
