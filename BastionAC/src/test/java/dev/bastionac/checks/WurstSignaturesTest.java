package dev.bastionac.checks;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Packet signatures of Wurst 7 (1.21.11 branch), taken from its source. */
class WurstSignaturesTest {

    @Test
    void stepSendsThePointFourTwoPointSevenFiveThreePair() {
        // StepHack: Pos(y + 0.42h), Pos(y + 0.753h), then the real move to y + h.
        assertEquals(1.0, ClientChecks.stepHeight(new double[]{0.42, 0.753, 1.0}, 3));
        assertEquals(2.0, ClientChecks.stepHeight(new double[]{0.84, 1.506, 2.0}, 3));
        // LiquidBounce's exact NCP constants.
        assertEquals(1.0, ClientChecks.stepHeight(new double[]{0.41999998688698, 0.7531999805212, 1.0}, 3));
    }

    @Test
    void jumpsAndCriticalsAreNotSteps() {
        assertEquals(0, ClientChecks.stepHeight(new double[]{0.42}, 1));                 // a real jump: one packet
        assertEquals(0, ClientChecks.stepHeight(new double[]{0.0625, 0, 1.1e-5, 0}, 4)); // Wurst Criticals
        assertEquals(0, ClientChecks.stepHeight(new double[]{0, 0, 0, 0, 22.36, 0}, 6)); // MaceDMG
    }

    @Test
    void scaffoldClicksTheExactFaceCentre() {
        // ScaffoldWalk: Vec3.atCenterOf(neighbour) + side * 0.5 → top face (0.5, 1.0, 0.5).
        assertTrue(WorldChecks.isFaceCentre(0.5, 1.0, 0.5, Direction.Axis.Y));
        assertTrue(WorldChecks.isFaceCentre(0.0, 0.5, 0.5, Direction.Axis.X));
        assertTrue(WorldChecks.isFaceCentre(0.5, 0.5, 1.0, Direction.Axis.Z));
    }

    @Test
    void aRaycastClickIsNeverTheExactCentre() {
        assertFalse(WorldChecks.isFaceCentre(0.4873f, 1.0, 0.61209f, Direction.Axis.Y));
        assertFalse(WorldChecks.isFaceCentre(0.5, 1.0, 0.50012f, Direction.Axis.Y));
    }
}
