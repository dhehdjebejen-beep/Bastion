package dev.bastionclaims.core;

import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.model.Claim;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit of 16.10 (1.20): feeders, service NPCs, copper golems. */
class AuditFixesTest {

    private static Claim base() {
        return Claim.of("c1", "Bob", "base", "minecraft:overworld", 0, 0, 0, 20, 100, 20);
    }

    @Test
    void aFeederOnTheBorderFollowsTheHopperRule() {
        ClaimConfig.Protection p = new ClaimConfig.Protection();
        Claim c = base();
        // A dropper one block outside, facing a chest just inside.
        assertTrue(ProtectionService.hopperBlocked(p, c, -1, 10, 5));
        assertFalse(ProtectionService.hopperBlocked(p, c, 1, 10, 5), "the owner's own dropper inside");
        c.allowHoppers = true;
        assertFalse(ProtectionService.hopperBlocked(p, c, -1, 10, 5), "the claim lets automation in");
    }

    @Test
    void serviceNpcsAreKnownByATagNobodyCanWrite() {
        List<String> markers = List.of("bastion_service", "maxcore_npc_*");
        assertTrue(ClaimEvents.hasServiceTag(Set.of("bastion_service"), markers));
        assertTrue(ClaimEvents.hasServiceTag(Set.of("x", "maxcore_npc_bank"), markers), "a prefix entry");
        assertFalse(ClaimEvents.hasServiceTag(Set.of("maxcore_seat"), markers));
        assertFalse(ClaimEvents.hasServiceTag(Set.of(), markers));
        assertFalse(ClaimEvents.hasServiceTag(Set.of("anything"), List.of("*")), "a bare star matches nothing");
    }

    @Test
    void theDefaultsNoLongerCarryOneServersNames() {
        ClaimConfig cfg = new ClaimConfig();
        assertTrue(cfg.serviceEntityNames.isEmpty());
        assertTrue(cfg.serviceEntityTags.contains("bastion_service"));
    }

    @Test
    void aCopperGolemServesOnlyItsOwnClaim() {
        Claim c = base();
        assertFalse(ProtectionService.golemBlocked(c, Set.of(ProtectionService.GOLEM_HOME_TAG + "c1")), "built here");
        assertTrue(ProtectionService.golemBlocked(c, Set.of(ProtectionService.GOLEM_HOME_TAG + "none")), "built outside");
        assertTrue(ProtectionService.golemBlocked(c, Set.of(ProtectionService.GOLEM_HOME_TAG + "c2")), "another claim's");
        assertTrue(ProtectionService.golemBlocked(c, Set.of()), "never seen");
        assertFalse(ProtectionService.golemBlocked(null, Set.of()), "a chest in the wild is anybody's");
        c.allowHoppers = true;
        assertFalse(ProtectionService.golemBlocked(c, Set.of()), "automation let in");
    }
}
