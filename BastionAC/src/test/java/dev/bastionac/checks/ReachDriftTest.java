package dev.bastionac.checks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reach on mobs, which have no position history (3.5 build 2). */
class ReachDriftTest {

    @Test
    void aStandingMobGetsNoAllowance() {
        assertEquals(0.0, CombatChecks.mobDriftAllowance(0, false, 60, 3), 1e-9);
    }

    @Test
    void aKnockedBackSheepCoversTheReachOf19October() {
        // 19.10 13:20:10: 4.96 blocks at 59 ms on a sheep doing 0.92 blocks a
        // tick — the soft limit was 3.9, now well above what it could have flown.
        double drift = CombatChecks.mobDriftAllowance(0.92, true, 59, 3);
        assertTrue(3.0 + 0.9 + drift >= 4.96, "drift " + drift);
    }

    @Test
    void theAllowanceIsCapped() {
        assertEquals(CombatChecks.MOB_DRIFT_CAP, CombatChecks.mobDriftAllowance(2.0, true, 200, 3), 1e-9);
    }

    @Test
    void aMultiSecondViewDelayIsNotJudged() {
        // 18.10: 9.8 blocks at a 2949 ms round trip, the link drowning in a
        // flock's entity updates. Nothing the server holds shows what that
        // client saw.
        assertTrue(Double.isNaN(CombatChecks.mobDriftAllowance(0.1, false, 2949, 3)));
        assertTrue(Double.isNaN(CombatChecks.mobDriftAllowance(0.0, false, 800, 3)));
        assertTrue(!Double.isNaN(CombatChecks.mobDriftAllowance(0.0, false, 300, 3)));
    }
}
