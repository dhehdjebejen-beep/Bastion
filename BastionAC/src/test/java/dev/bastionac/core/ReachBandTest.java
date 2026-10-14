package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reach window is a band around one round trip back, not everything up to the ping. */
class ReachBandTest {

    @Test
    void lowPingKeepsTheRecentTicks() {
        assertArrayEquals(new int[]{0, 3}, HitboxHistory.reachBand(40, 3));
        assertArrayEquals(new int[]{0, 2}, HitboxHistory.reachBand(0, 0), "never fewer than three ticks");
    }

    @Test
    void highPingIsABandNotTheWholeHistory() {
        // 500 ms round trip: ticks 7..13, not 0..12 as before — the target's
        // newest positions (which a lagging client could not have seen) are out.
        assertArrayEquals(new int[]{7, 13}, HitboxHistory.reachBand(500, 3));
        // A full second of delay: the band sits at the end of the history.
        assertArrayEquals(new int[]{17, 19}, HitboxHistory.reachBand(1000, 3));
    }

    @Test
    void jitteryPingSpansItsRecentRange() {
        // Round trips of 100..350 ms: ticks 0..10 — the box 100 ms back is inside,
        // which a band around the 350 ms figure alone (4..10) left out.
        assertArrayEquals(new int[]{0, 10}, HitboxHistory.reachBand(100, 350, 3));
        assertArrayEquals(HitboxHistory.reachBand(400, 3), HitboxHistory.reachBand(400, 400, 3), "steady ping as before");
    }

    @Test
    void jitterCannotReopenTheWholeHistory() {
        int[] b = HitboxHistory.reachBand(0, 1000, 3);
        assertTrue(b[1] - b[0] <= HitboxHistory.MAX_BAND_TICKS, "capped: " + b[0] + ".." + b[1]);
        assertTrue(b[0] > 0, "the newest positions of a lagging client's target stay out");
    }
}
