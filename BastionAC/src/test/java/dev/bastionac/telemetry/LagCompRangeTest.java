package dev.bastionac.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The round-trip range the reach check may compensate for (3.1.2). */
class LagCompRangeTest {

    @Test
    void noTransactionsYetMeansVanillaLatency() {
        assertArrayEquals(new int[]{120, 120}, Telemetry.lagCompRange(120, new int[]{-1, -1, -1}));
    }

    @Test
    void heldBackKeepAlivesDoNotStretchIt() {
        // PingSpoof: keep-alives claim 900 ms, the game packets come back in 40.
        assertArrayEquals(new int[]{40, 90}, Telemetry.lagCompRange(900, new int[]{40, 40, 40}));
    }

    @Test
    void aRealSpikeIsNotCutOffBySmoothedKeepAlives() {
        // A mobile spike to 400 ms that the 15-second keep-alive average (80) has not caught up with.
        assertArrayEquals(new int[]{80, 400}, Telemetry.lagCompRange(80, new int[]{400, 90, 85}));
    }

    @Test
    void jitterGivesTheWholeRecentRange() {
        assertArrayEquals(new int[]{150, 380}, Telemetry.lagCompRange(380, new int[]{150, 350, 260}));
    }
}
