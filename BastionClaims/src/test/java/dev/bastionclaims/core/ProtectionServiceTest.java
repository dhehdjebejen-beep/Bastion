package dev.bastionclaims.core;

import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.model.Claim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure (Minecraft-free) protection predicates: fluid inflow,
 * hopper transfer and piston reach across a claim border.
 */
class ProtectionServiceTest {

    private static Claim cube(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Claim.of("id", "Bob", "base", "minecraft:overworld", x1, y1, z1, x2, y2, z2);
    }

    private static ClaimConfig.Protection allOn() {
        return new ClaimConfig.Protection(); // every master switch defaults to true
    }

    // ------------------------------------------------------------------ fluids

    @Test
    void fluidFromOutsideIsBlockedButInternalFlowIsAllowed() {
        Claim c = cube(0, 60, 0, 10, 70, 10);
        // Destination (5,65,5) is inside; source (11,65,5) is just outside → block.
        assertTrue(ProtectionService.fluidFlowBlocked(allOn(), c, 11, 65, 5));
        // Source (4,65,5) is inside the same claim → the owner's liquid may spread.
        assertFalse(ProtectionService.fluidFlowBlocked(allOn(), c, 4, 65, 5));
    }

    @Test
    void fluidProtectionRespectsSwitchesAndUnclaimedLand() {
        Claim c = cube(0, 60, 0, 10, 70, 10);
        assertFalse(ProtectionService.fluidFlowBlocked(allOn(), null, 11, 65, 5)); // unclaimed dest
        ClaimConfig.Protection off = allOn();
        off.fluidProtection = false;
        assertFalse(ProtectionService.fluidFlowBlocked(off, c, 11, 65, 5));         // master off
        c.allowFluidFlow = true;
        assertFalse(ProtectionService.fluidFlowBlocked(allOn(), c, 11, 65, 5));     // per-claim opt-in
    }

    // ------------------------------------------------------------------ hoppers

    @Test
    void hopperOutsideCannotTouchClaimContainer() {
        Claim c = cube(0, 60, 0, 10, 70, 10);
        // Hopper below the claim floor (5,59,5) → blocked; hopper inside (5,61,5) → allowed.
        assertTrue(ProtectionService.hopperBlocked(allOn(), c, 5, 59, 5));
        assertFalse(ProtectionService.hopperBlocked(allOn(), c, 5, 61, 5));
    }

    @Test
    void hopperProtectionRespectsSwitchesAndUnclaimedContainer() {
        Claim c = cube(0, 60, 0, 10, 70, 10);
        assertFalse(ProtectionService.hopperBlocked(allOn(), null, 5, 59, 5));
        ClaimConfig.Protection off = allOn();
        off.hopperProtection = false;
        assertFalse(ProtectionService.hopperBlocked(off, c, 5, 59, 5));
        c.allowHoppers = true;
        assertFalse(ProtectionService.hopperBlocked(allOn(), c, 5, 59, 5));
    }

    // ------------------------------------------------------------------ pistons

    @Test
    void pistonOutsideCannotMoveClaimBlocks() {
        Claim c = cube(0, 60, 0, 10, 70, 10);
        // A cell inside the claim, piston based just outside → blocked.
        assertTrue(ProtectionService.pistonCellBlocked(allOn(), c, 11, 65, 5));
        // Piston based inside the same claim → its own machinery is allowed.
        assertFalse(ProtectionService.pistonCellBlocked(allOn(), c, 5, 65, 5));
    }

    @Test
    void pistonProtectionRespectsSwitchesAndUnclaimedCells() {
        Claim c = cube(0, 60, 0, 10, 70, 10);
        assertFalse(ProtectionService.pistonCellBlocked(allOn(), null, 11, 65, 5)); // unclaimed cell
        ClaimConfig.Protection off = allOn();
        off.pistonProtection = false;
        assertFalse(ProtectionService.pistonCellBlocked(off, c, 11, 65, 5));
        c.allowPistons = true;
        assertFalse(ProtectionService.pistonCellBlocked(allOn(), c, 11, 65, 5));
    }
}
