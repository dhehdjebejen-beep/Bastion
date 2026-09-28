package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathUtilTest {

    @Test
    void cpsSeverityIsZeroAtOrBelowTheCeiling() {
        assertEquals(0.0, MathUtil.cpsSeverity(14, 14), 1e-9);
        assertEquals(0.0, MathUtil.cpsSeverity(8, 14), 1e-9);
    }

    @Test
    void cpsSeverityGrowsWithTheOvershoot() {
        assertEquals(0.5, MathUtil.cpsSeverity(15, 14), 1e-9);  // one over — barely evidence
        assertEquals(3.0, MathUtil.cpsSeverity(20, 14), 1e-9);  // a plain autoclicker
        assertTrue(MathUtil.cpsSeverity(25, 14) > MathUtil.cpsSeverity(20, 14));
    }

    @Test
    void cpsSeverityIsCappedSoOnePacketBurstCannotKick() {
        // Raw packet spam (hundreds per second) must not be worth unbounded VL:
        // the response is already instant at the cap.
        assertEquals(6.0, MathUtil.cpsSeverity(1000, 14), 1e-9);
    }

    @Test
    void cpsResponseIsInfiniteWhenDecayOutrunsTheFlagRate() {
        // Guards the same trap the Timer check fell into: a decay above the
        // check's own flag rate makes it silently unable to ever act.
        assertEquals(Double.POSITIVE_INFINITY,
                MathUtil.cpsSecondsToReach(15, 14, 1.0, 99.0, 15.0));
    }

    @Test
    void knockbackDisplacementGrowsWithTicksAndVelocity() {
        // More ticks of decay => more accumulated travel, approaching v/(1-drag).
        double one = MathUtil.knockbackDisplacement(1.0, 1, 0.98);
        double four = MathUtil.knockbackDisplacement(1.0, 4, 0.98);
        assertTrue(four > one, "more ticks must accumulate more displacement");
        // A stronger impulse travels further over the same window.
        assertTrue(MathUtil.knockbackDisplacement(2.0, 4, 0.98) > four);
    }

    @Test
    void knockbackDisplacementMatchesTheGeometricSeries() {
        // v=1, drag=0.98, n=4: 1 * (1 - 0.98^4) / (1 - 0.98) = (1 - 0.92236816) / 0.02
        double expected = (1.0 - Math.pow(0.98, 4)) / 0.02;
        assertEquals(expected, MathUtil.knockbackDisplacement(1.0, 4, 0.98), 1e-9);
    }

    @Test
    void knockbackDisplacementIsZeroForNonPositiveInput() {
        assertEquals(0.0, MathUtil.knockbackDisplacement(0.0, 4, 0.98), 1e-9);
        assertEquals(0.0, MathUtil.knockbackDisplacement(1.0, 0, 0.98), 1e-9);
        assertEquals(0.0, MathUtil.knockbackDisplacement(-1.0, 4, 0.98), 1e-9);
    }

    @Test
    void distanceZeroInsideBox() {
        assertEquals(0.0, MathUtil.distanceToBox(0.5, 0.5, 0.5, 0, 0, 0, 1, 1, 1), 1e-9);
    }

    @Test
    void distanceAlongSingleAxis() {
        assertEquals(2.0, MathUtil.distanceToBox(3, 0.5, 0.5, 0, 0, 0, 1, 1, 1), 1e-9);
    }

    @Test
    void distanceToCorner() {
        double d = MathUtil.distanceToBox(2, 2, 2, 0, 0, 0, 1, 1, 1);
        assertEquals(Math.sqrt(3), d, 1e-9);
    }

    @Test
    void lookingStraightAtBoxGivesHighDot() {
        double dot = MathUtil.lookDotToBox(0, 0, 0, 1, 0, 0, 5, -1, -1, 6, 1, 1);
        assertTrue(dot > 0.99, "dot=" + dot);
    }

    @Test
    void lookingAwayGivesNegativeDot() {
        double dot = MathUtil.lookDotToBox(0, 0, 0, -1, 0, 0, 5, -1, -1, 6, 1, 1);
        assertTrue(dot < -0.99, "dot=" + dot);
    }

    @Test
    void insideBoxCountsAsLookingAtIt() {
        double dot = MathUtil.lookDotToBox(0.5, 0.5, 0.5, 0, 1, 0, 0, 0, 0, 1, 1, 1);
        assertEquals(1.0, dot, 1e-9);
    }

    @Test
    void horizontalDistance() {
        assertEquals(5.0, MathUtil.horizontal(3, 4), 1e-9);
    }
}
