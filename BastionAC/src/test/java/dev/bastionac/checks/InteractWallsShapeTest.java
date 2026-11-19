package dev.bastionac.checks;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The faces InteractWalls looks for on a block that is not a full cube. */
class InteractWallsShapeTest {

    /**
     * An upside-down stair facing west: the top slab spans the whole cell, the step hangs
     * under its west half. The underside of the slab over the east-side notch is a face
     * pointing down half a block inside the cell — the face a player under the notch breaks.
     */
    @Test
    void undersideOfTheTopSlabIsHalfABlockInsideTheCell() {
        BlockPos pos = new BlockPos(6309, 62, -4695);
        Box topSlab = new Box(0, 0.5, 0, 1, 1, 1);
        Vec3d[] pts = WorldChecks.shapeFacePoints(pos, topSlab, Direction.DOWN);
        assertEquals(5, pts.length);
        for (Vec3d p : pts) {
            assertEquals(62.48, p.y, 1e-9, "a hair under y = 62.5, not at the cell's bottom 62.0");
            assertTrue(p.x > 6309 && p.x < 6310 && p.z > -4695 && p.z < -4694, "inside the cell's footprint");
        }
        assertEquals(6309.5, pts[0].x, 1e-9);
        assertEquals(-4694.5, pts[0].z, 1e-9);
    }

    @Test
    void cellFaceStaysWhereItWas() {
        Vec3d[] pts = WorldChecks.facePoints(new BlockPos(0, 64, 0), Direction.UP);
        assertEquals(65.0, pts[0].y, 1e-9);
        assertEquals(0.5, pts[0].x, 1e-9);
        assertEquals(0.95, pts[1].x, 1e-9, "corners reach 0.45 from face centre");
    }

    @Test
    void clientVersionLabelAndRecentlyTouchedBlockWindowWork() {
        assertTrue(dev.bastionac.core.ClientVersion.versionLabel(774).contains("1.21.11"));
        assertTrue(dev.bastionac.core.ClientVersion.versionLabel(767).contains("1.21"));
        assertTrue(dev.bastionac.core.ClientVersion.versionLabel(777).contains("26.3"));
        assertTrue(dev.bastionac.core.ClientVersion.versionLabel(-1).contains("1.21.11"));

        dev.bastionac.core.PlayerData pd = new dev.bastionac.core.PlayerData(java.util.UUID.randomUUID(), "TestPlayer");
        long p = new BlockPos(5681, 64, -10344).asLong();
        pd.noteTouchedBlock(p, 10_000L);
        assertTrue(pd.wasRecentlyTouched(p, 10_300L, 500L));
        org.junit.jupiter.api.Assertions.assertFalse(pd.wasRecentlyTouched(p, 10_700L, 500L));
    }

    @Test
    void spearUseEffectsAndServerSprintDesyncDoNotTriggerNoSlowWhileFoodDoes() {
        // 1.21.11 Spear: UseEffectsComponent(canSprint=true, interactVibrations=false, speedMultiplier=1.0f), KINETIC_WEAPON=true
        org.junit.jupiter.api.Assertions.assertFalse(MovementChecks.noSlowApplicable(1.0f, true, true));
        org.junit.jupiter.api.Assertions.assertFalse(MovementChecks.noSlowApplicable(1.0f, true, false));
        // Food / Bow / Shield: UseEffectsComponent.DEFAULT (canSprint=false, speedMultiplier=0.2f)
        assertTrue(MovementChecks.noSlowApplicable(0.2f, false, false));

        // Even if noSlowStep is evaluated after server-side knockbackTarget cleared isSprinting()
        // (dropping server speedAttr from 0.13 to 0.10 while client still holds sprint input),
        // effectiveNoSlowSpeedAttr restores 0.13 when canSprintUsing is true:
        double effSpeed = MovementChecks.effectiveNoSlowSpeedAttr(0.10, false, true, true);
        assertEquals(0.13, effSpeed, 1e-9);
        double step = MovementChecks.noSlowStep(0.2806, true, true, 0.0, true, effSpeed, 1.0f, 0.6f, false);
        double bound = MovementChecks.noSlowSlack(step, true) + 0.005;
        assertTrue(bound > 0.32 && bound > 0.2807, "Spear sprint step 0.2807 must be well below bound " + bound);

        // Conversely, eating an apple (useMul=0.2f, canSprint=false) at 0.2806 bl/tick MUST exceed bound:
        double foodEffSpeed = MovementChecks.effectiveNoSlowSpeedAttr(0.10, false, true, false);
        assertEquals(0.10, foodEffSpeed, 1e-9);
        double shadow = 0.2806;
        for (int t = 0; t < 6; t++) {
            double foodStep = MovementChecks.noSlowStep(shadow, true, true, 0.0, false, foodEffSpeed, 0.2f, 0.6f, false);
            double foodBound = MovementChecks.noSlowSlack(foodStep, true) + 0.005;
            shadow = Math.min(foodStep, 0.2806);
            if (t >= 3) {
                assertTrue(0.2806 > foodBound, "NoSlow cheat at 0.2806 with apple must exceed bound " + foodBound);
            }
        }
    }
}
