package dev.obsidian.core.tracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PingTrackerTest {

    private static long feed(PingTracker p, int id, long sentNanos, int rttMs) {
        p.onKeepAliveSent(id, sentNanos);
        long recv = sentNanos + rttMs * 1_000_000L;
        p.onKeepAliveReceived(id, recv);
        return recv;
    }

    @Test
    void stableLinkHasLowJitterAndNoSpike() {
        PingTracker p = new PingTracker();
        long t = 1_000_000_000L;
        for (int i = 0; i < 10; i++) {
            t = feed(p, i, t, 50) + 1_000_000_000L;
        }
        assertEquals(50, p.medianPing());
        assertTrue(p.jitterMillis() < 5, "stable link should have near-zero jitter");
        assertFalse(p.spikedWithin(t, 2_000_000_000L));
    }

    @Test
    void suddenJumpIsFlaggedAsASpike() {
        PingTracker p = new PingTracker();
        long t = 1_000_000_000L;
        for (int i = 0; i < 10; i++) {
            t = feed(p, i, t, 50) + 1_000_000_000L;
        }
        // A 300ms RTT is 250ms over the 50ms median: well past the 150ms threshold.
        p.onKeepAliveSent(100, t);
        long recv = t + 300_000_000L;
        p.onKeepAliveReceived(100, recv);
        assertTrue(p.spikedWithin(recv, 2_000_000_000L), "a 250ms jump must register as a spike");
        // Median is robust: one spike does not move it.
        assertEquals(50, p.medianPing());
    }

    @Test
    void spikeExpiresAfterTheGraceWindow() {
        PingTracker p = new PingTracker();
        long t = 1_000_000_000L;
        for (int i = 0; i < 5; i++) {
            t = feed(p, i, t, 40) + 1_000_000_000L;
        }
        long recv = feed(p, 99, t, 400); // spike
        assertTrue(p.spikedWithin(recv + 1_000_000_000L, 1_500_000_000L));
        assertFalse(p.spikedWithin(recv + 2_000_000_000L, 1_500_000_000L),
                "past the grace window the spike no longer suppresses checks");
    }

    @Test
    void mismatchedKeepAliveIdIsIgnored() {
        PingTracker p = new PingTracker();
        p.onKeepAliveSent(1, 1_000_000_000L);
        p.onKeepAliveReceived(999, 1_050_000_000L); // wrong id
        assertEquals(50, p.medianPing(), "default median stands when no valid RTT arrives");
        assertEquals(0, p.samples());
    }
}
