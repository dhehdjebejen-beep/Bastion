package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The aura-aim math: Minecraft's look vector, the angle to a point, and the
 * lock/snap-back verdicts, with Wurst's own rotation formula as the cheat.
 */
class AimTrackerTest {

    /** Wurst's RotationUtils.getNeededRotations, verbatim in spirit. */
    private static float[] wurstRotation(double ex, double ey, double ez, double px, double py, double pz) {
        double dx = px - ex, dz = pz - ez, dy = py - ey;
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{(float) yaw, (float) pitch};
    }

    @Test
    void lookVectorMatchesMinecraftAxes() {
        double[] south = AimTracker.look(0, 0);
        assertEquals(0, south[0], 1e-9);
        assertEquals(1, south[2], 1e-9);
        double[] west = AimTracker.look(90, 0);
        assertEquals(-1, west[0], 1e-9);
        double[] down = AimTracker.look(0, 90);
        assertEquals(-1, down[1], 1e-9);
    }

    @Test
    void wurstRotationLandsOnTheCentreToAHundredthOfADegree() {
        double ex = 10.3, ey = 65.62, ez = -4.7;   // eye
        double cx = 12.9, cy = 64.9, cz = -2.1;     // hitbox centre
        float[] r = wurstRotation(ex, ey, ez, cx, cy, cz);
        double err = AimTracker.minError(AimTracker.look(r[0], r[1]),
                List.of(new double[]{ex, ey, ez}), List.of(new double[]{cx, cy, cz}));
        assertTrue(err < 0.01, "Wurst aim error " + err);
    }

    @Test
    void aHandAtTheChestIsDegreesOff() {
        double ex = 0, ey = 65.62, ez = 0;
        // Aiming at the upper chest (1.3 above the feet) of a target 3 blocks away:
        // the centre is 0.9 above the feet, so the error is several degrees.
        float[] chest = wurstRotation(ex, ey, ez, 0, 64 + 1.3, 3);
        double err = AimTracker.minError(AimTracker.look(chest[0], chest[1]),
                List.of(new double[]{ex, ey, ez}), List.of(new double[]{0, 64.9, 3}));
        assertTrue(err > 5, "chest aim error " + err);
    }

    @Test
    void sixLockedHitsFlagAndHumanNoiseNeverDoes() {
        PlayerData cheat = new PlayerData(java.util.UUID.randomUUID(), "cheat");
        String msg = null;
        for (int i = 0; i < 6; i++) msg = AimTracker.recordLock(cheat, 0.05);
        assertNotNull(msg);

        PlayerData hand = new PlayerData(java.util.UUID.randomUUID(), "hand");
        Random rnd = new Random(7);
        for (int i = 0; i < 400; i++) {
            // One hit in fifteen happens to cross the centre within a degree.
            double err = rnd.nextInt(15) == 0 ? 0.6 : 2 + rnd.nextDouble() * 10;
            assertNull(AimTracker.recordLock(hand, err), "human flagged at hit " + i);
        }
    }

    @Test
    void snapBackIsAFlickThatReturns() {
        // Looking at 0/0, flicked 40° to the target, next packet back at 0.5/0.
        assertTrue(AimTracker.snappedBack(40, 0, 0, 0.5f, 0));
        // A human who flicks to the target and keeps tracking it does not return.
        assertFalse(AimTracker.snappedBack(40, 0, 0, 38f, 2));
        assertEquals(40, AimTracker.rotationDelta(40, 0, 0, 0), 1e-9);
        assertEquals(20, AimTracker.rotationDelta(170, 0, -170, 0), 1e-9);
    }
}
