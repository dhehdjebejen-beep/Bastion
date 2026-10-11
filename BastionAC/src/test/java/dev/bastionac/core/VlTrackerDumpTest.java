package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2.30.1: the context dump must not eat the first staff alert of an episode. */
class VlTrackerDumpTest {

    @Test
    void dumpAndAlertHaveSeparateClocks() {
        VlTracker t = new VlTracker();
        CheckType type = CheckType.values()[0];
        long now = 1_000_000L;
        assertTrue(t.tryDump(type, now, 60_000L), "first dump of the episode");
        assertTrue(t.tryAlert(type, now, 5_000L), "the alert in the same instant must still go out");
    }
}
