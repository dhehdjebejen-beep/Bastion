package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
}
