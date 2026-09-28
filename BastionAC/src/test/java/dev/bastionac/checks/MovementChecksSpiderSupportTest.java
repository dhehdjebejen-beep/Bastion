package dev.bastionac.checks;

import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for Spider footing. The question is not whether a shape
 * touches the player's horizontal footprint, but whether it provides a top
 * surface at foot height. A side wall must never reset climbBaseY.
 */
class MovementChecksSpiderSupportTest {

    @Test
    void fullBlockFloorSupportsFeetAtItsTopSurface() {
        assertTrue(MovementChecks.supportsFeetFromTop(new Box(0, 63, 0, 1, 64, 1), 64.0));
    }

    @Test
    void halfSlabAndCarpetHeightSurfacesRemainValidFooting() {
        assertTrue(MovementChecks.supportsFeetFromTop(new Box(0, 63, 0, 1, 63.5, 1), 63.5));
        assertTrue(MovementChecks.supportsFeetFromTop(new Box(0, 63, 0, 1, 63.0625, 1), 63.0625));
    }

    @Test
    void fullConcreteWallBesidePlayerIsNotFloor() {
        // A full block can overlap a foot footprint from the side while its top
        // is a block above the player's feet. That is a wall, not support.
        assertFalse(MovementChecks.supportsFeetFromTop(new Box(0.72, 64, 0, 1.72, 65, 1), 64.0));
    }

    @Test
    void fenceAndIronBarsBesidePlayerAreNotFloor() {
        assertFalse(MovementChecks.supportsFeetFromTop(new Box(0.375, 64, 0.375, 0.625, 65.5, 0.625), 64.0));
        assertFalse(MovementChecks.supportsFeetFromTop(new Box(0.4375, 64, 0, 0.5625, 65, 1), 64.0));
    }

    @Test
    void normalVanillaGroundRoundingIsTolerated() {
        Box floor = new Box(0, 63, 0, 1, 64, 1);
        assertTrue(MovementChecks.supportsFeetFromTop(floor, 64.04));
        assertFalse(MovementChecks.supportsFeetFromTop(floor, 64.08));
    }
}
