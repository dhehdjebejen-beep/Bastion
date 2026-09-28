package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Blink check reads the inter-arrival-time (IAT) variance of a movement
 * burst: a synthetic client-side flush arrives in one or two TCP frames, so
 * its IAT standard deviation collapses toward zero, while a genuine bufferbloat
 * burst keeps measurable jitter from the network stack.
 */
class BlinkBurstTest {

    private static PlayerData newData() {
        return new PlayerData(java.util.UUID.randomUUID(), "test");
    }

    @Test
    void syntheticFlushHasNearZeroVariance() {
        // A Blink flush: every packet lands within the same millisecond.
        PlayerData d = newData();
        long t = 1_000_000L;
        for (int i = 0; i < 10; i++) {
            d.recordBurstPacket(t); // identical timestamps — zero gaps
        }
        assertEquals(0.0, d.burstIatStd(), 1e-9,
                "identical arrival times must give zero IAT std");
    }

    @Test
    void realBufferbloatKeepsJitter() {
        // A genuine network burst: packets arrive close together but with
        // measurable spread from the OS/driver path (0.5–4 ms gaps).
        PlayerData d = newData();
        long t = 1_000_000L;
        long[] gaps = {1, 3, 1, 4, 2, 1, 3, 2, 4};
        for (long g : gaps) {
            t += g;
            d.recordBurstPacket(t);
        }
        double std = d.burstIatStd();
        assertTrue(std > 1.0, "real jitter must exceed the 1ms Blink floor, got " + std);
    }

    @Test
    void tooFewPacketsIsNotJudged() {
        // Fewer than 3 packets cannot produce a meaningful variance — the check
        // must abstain (return the "do not flag" sentinel), not convict on noise.
        PlayerData d = newData();
        d.recordBurstPacket(1_000_000L);
        d.recordBurstPacket(1_000_001L);
        assertEquals(Double.MAX_VALUE, d.burstIatStd(), 1e-9);
    }

    /**
     * The window has to travel with the player. The buffer used to fill once and
     * stop recording at 64 arrivals, so a flush that came minutes into a session
     * was judged by the variance of that session's FIRST three seconds — the
     * check read a window it was not judging. A rolling window must see the
     * flush no matter how long the player was online first.
     */
    @Test
    void aFlushAfterALongCleanSessionIsStillJudged() {
        PlayerData d = newData();
        long t = 1_000_000L;
        // Two hundred normally-paced arrivals: far past the 64-entry buffer.
        for (int i = 0; i < 200; i++) {
            t += 47 + (i % 7); // ~50ms with ordinary network jitter
            d.recordBurstPacket(t);
        }
        assertTrue(d.burstIatStd(12) > 1.0, "steady play must not look like a flush");

        // Now the Blink flush: withheld packets land in one frame.
        for (int i = 0; i < 12; i++) d.recordBurstPacket(t);
        assertEquals(0.0, d.burstIatStd(12), 1e-9,
                "the flush must be visible even after a long clean session");
    }

    /** The bounded read must ignore older, normally-paced arrivals. */
    @Test
    void onlyTheRequestedTailIsMeasured() {
        PlayerData d = newData();
        long t = 1_000_000L;
        for (int i = 0; i < 30; i++) {
            t += 50;
            d.recordBurstPacket(t);
        }
        for (int i = 0; i < 10; i++) d.recordBurstPacket(t); // flush, zero gaps
        assertEquals(0.0, d.burstIatStd(10), 1e-9);
        // Widening the window drags the 50ms gaps back in and the signal dilutes.
        assertTrue(d.burstIatStd(40) > 1.0);
    }

    @Test
    void resetClearsTheBurst() {
        PlayerData d = newData();
        for (int i = 0; i < 8; i++) d.recordBurstPacket(1_000_000L + i);
        d.resetBurst();
        // After a reset the window is empty again — back to the abstain sentinel.
        assertEquals(Double.MAX_VALUE, d.burstIatStd(), 1e-9);
    }
}
