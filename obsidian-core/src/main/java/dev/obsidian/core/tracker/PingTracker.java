package dev.obsidian.core.tracker;

import java.util.Arrays;

/**
 * Rolling ping estimate from keepalive/transaction round-trips.
 *
 * <p>The median of the last 20 RTTs is what every check subtracts from its
 * timing measurements. Median, not mean: a single ping spike must not drag the
 * compensation value around, and a single fast RTT must not shrink it.</p>
 */
public final class PingTracker {

    private static final int WINDOW = 20;

    private final int[] rtts = new int[WINDOW];
    private final int[] scratch = new int[WINDOW];
    private int head;
    private int size;
    private volatile int cachedMedian = 50; // sane default until first sample

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
        rtts[head] = rtt;
        head = (head + 1) % WINDOW;
        if (size < WINDOW) {
            size++;
        }
        recomputeMedian();
    }

    private void recomputeMedian() {
        System.arraycopy(rtts, 0, scratch, 0, size);
        Arrays.sort(scratch, 0, size);
        cachedMedian = size % 2 == 1
                ? scratch[size / 2]
                : (scratch[size / 2 - 1] + scratch[size / 2]) / 2;
    }

    /** Median RTT in millis. Safe to read from any thread. */
    public int medianPing() {
        return cachedMedian;
    }

    public int samples() {
        return size;
    }
}
