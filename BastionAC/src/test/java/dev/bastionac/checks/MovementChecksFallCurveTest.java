package dev.bastionac.checks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for FastFall and NoFall on the 26.10 shaft falls: two
 * players dropping out of a water column, whose recorded speeds follow the
 * vanilla curve to the hundredth while the fall counter had been restarted
 * four ticks late.
 */
class MovementChecksFallCurveTest {

    /** A player's descent per tick from the session file, first tick out of the water onward. */
    private static final double[] SHAFT_FALL = {0.07, 0.15, 0.22, 0.30, 0.37, 0.44, 0.51, 0.58, 0.65, 0.71, 0.78, 0.84, 0.90, 0.96};

    private static double cap(int ticks) {
        return MovementChecks.maxVanillaFallSpeed(ticks) * 1.15 + 0.10;
    }

    @Test
    void vanillaFallMatchesTheRecordedShaftDrop() {
        for (int i = 0; i < SHAFT_FALL.length; i++) {
            assertEquals(MovementChecks.maxVanillaFallSpeed(i + 1), SHAFT_FALL[i], 0.011, "tick " + (i + 1));
        }
    }

    @Test
    void counterRestartedFourTicksLateConvictsAnHonestFall() {
        // What 3.5.0-beta+build.5 did: ticks 1..5 read as "resting", so the
        // twelfth tick was judged as the eighth.
        assertTrue(SHAFT_FALL[11] > cap(8));
    }

    @Test
    void counterSeededFromTheSpeedNeverConvictsIt() {
        // The fix: while "resting" without a floor the counter follows the
        // speed, then counts on from there.
        int ticks = MovementChecks.ticksForFallSpeed(SHAFT_FALL[4]);
        for (int i = 5; i < SHAFT_FALL.length; i++) {
            ticks++;
            assertTrue(SHAFT_FALL[i] <= cap(ticks), "tick " + (i + 1) + " judged as " + ticks);
        }
    }

    @Test
    void seededCounterStillCatchesADoubledDrop() {
        int ticks = MovementChecks.ticksForFallSpeed(SHAFT_FALL[4]) + 1;
        assertTrue(SHAFT_FALL[5] * 2 > cap(ticks));
    }

    @Test
    void slackKeepsTheMismeasuredLandingAtVanillasBill() {
        // 26.10: measured 7.5, vanilla's own fall distance 6.7 and 3 damage.
        assertEquals(3, MovementChecks.vanillaFallDamage(7.5 - MovementChecks.NOFALL_DESCENT_SLACK, 3.0, 1.0));
        // A real NoFall on a 20-block drop is still billed nearly in full.
        assertEquals(16, MovementChecks.vanillaFallDamage(20.0 - MovementChecks.NOFALL_DESCENT_SLACK, 3.0, 1.0));
    }
}
