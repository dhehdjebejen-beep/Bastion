package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * X-ray statistics: the verdicts are pure functions of session counters,
 * so the thresholds are verified exactly — a cave explorer must never
 * qualify, a tracer must not escape, and every verdict only becomes
 * meaningful once its evidence window has actually been filled. The live
 * tester's session (500+ mixed ores with an insta-mine pickaxe, zero
 * verdicts) is represented by the mixed-family cases: the old code only
 * counted diamond/emerald, so a mixed session never even reached the
 * numerator.
 */
class XrayTrackerTest {

    @Test
    void pureOreVerdictBoundaries() {
        // One below on any axis: no verdict.
        assertFalse(XrayTracker.isPureOreSession(4, 19, 3), "one ore short");
        assertFalse(XrayTracker.isPureOreSession(5, 20, 3), "stone at cap (needs < 20)");
        assertFalse(XrayTracker.isPureOreSession(5, 19, 2), "one hidden short");
        // Exactly on all axes: verdict.
        assertTrue(XrayTracker.isPureOreSession(5, 19, 3));
        assertTrue(XrayTracker.isPureOreSession(12, 3, 8), "a blatant tracer");
    }

    @Test
    void highRatioBoundaries() {
        // Natural mixed mining (iron+gold+coal hoovered alongside diamond)
        // sits around 0.15-0.2 and must never trip the 0.35 line.
        assertFalse(XrayTracker.isHighRatio(20, 100), "0.20 mixed natural");
        assertFalse(XrayTracker.isHighRatio(34, 100), "0.34 is not above the line");
        assertTrue(XrayTracker.isHighRatio(36, 100), "0.36 trips");
        // Before the stone baseline the ratio is meaningless either way.
        assertFalse(XrayTracker.isHighRatio(99, 99), "no baseline, no verdict");
        assertFalse(XrayTracker.isHighRatio(0, 99), "no baseline even with zero ore");
        // The tester's session shape: ~500 ores over ~300 stone would be
        // 1.67 — far past the line; the point of the case is that the
        // numerator now contains iron/gold/copper too.
        assertTrue(XrayTracker.isHighRatio(500, 300));
    }

    @Test
    void ratioOnlyCountsStoneBaseline() {
        // A tunneling xray removes the cover first: 100 stone, 36 ores.
        assertTrue(XrayTracker.isHighRatio(36, 100));
        // The same ratio with half the stone is not yet evidence.
        assertFalse(XrayTracker.isHighRatio(18, 50));
    }

    @Test
    void purityDropBoundaries() {
        // The honest-prefix-then-vein-surfing shape: 6+ ores inside a
        // 40-stone window.
        assertFalse(XrayTracker.isPurityDrop(5, 40), "one ore short");
        assertFalse(XrayTracker.isPurityDrop(6, 41), "window one stone too wide");
        assertTrue(XrayTracker.isPurityDrop(6, 40));
        assertTrue(XrayTracker.isPurityDrop(15, 10), "blatant vein surfing");
    }

    @Test
    void oreFamiliesClassify() {
        assertEquals(XrayTracker.OreFamily.DIAMOND, XrayTracker.classify(true, false));
        assertEquals(XrayTracker.OreFamily.EMERALD, XrayTracker.classify(false, true));
        assertEquals(XrayTracker.OreFamily.OTHER, XrayTracker.classify(false, false));
        // The extended family: iron, gold, copper, lapis, redstone, coal.
        assertEquals(XrayTracker.OreFamily.OTHER_VALUABLE, XrayTracker.classify(false, false, true));
        assertEquals(XrayTracker.OreFamily.DIAMOND, XrayTracker.classify(true, false, true));
        assertEquals(XrayTracker.OreFamily.OTHER, XrayTracker.classify(false, false, false));
    }
}
