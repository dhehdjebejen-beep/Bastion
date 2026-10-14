package dev.bastionclaims.core;

import dev.bastionclaims.model.Claim;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Roles inside a claim: what owners, full members, role holders and guests may do. */
class ClaimRolesTest {

    private static Claim claim() {
        Claim c = Claim.of("id", "Bob", "base", "minecraft:overworld", 0, 60, 0, 10, 70, 10);
        c.setOwnerUuid(Claim.offlineUuid("Bob"));
        return c;
    }

    private static UUID member(Claim c, String name) {
        c.addMember(name);
        c.addMemberUuid(Claim.offlineUuid(name));
        return Claim.offlineUuid(name);
    }

    @Test
    void ownerAndFullMembersMayDoEverything() {
        Claim c = claim();
        UUID alice = member(c, "Alice");
        for (Claim.Perm p : Claim.Perm.values()) {
            assertTrue(c.can(Claim.offlineUuid("Bob"), p), "owner " + p);
            assertTrue(c.can(alice, p), "member without a role " + p);
        }
        assertFalse(c.can(Claim.offlineUuid("Mallory"), Claim.Perm.DOORS), "a stranger is no member");
    }

    @Test
    void aRoleGrantsOnlyItsPermissions() {
        Claim c = claim();
        UUID guest = member(c, "Guest");
        c.roles.put("гость", new ArrayList<>(List.of("doors", "containers")));
        c.memberRoles.put(guest.toString(), "гость");
        assertEquals("гость", c.roleOf(guest));
        assertTrue(c.can(guest, Claim.Perm.DOORS));
        assertTrue(c.can(guest, Claim.Perm.CONTAINERS));
        assertFalse(c.can(guest, Claim.Perm.BUILD), "no building for this role");
        assertFalse(c.can(guest, Claim.Perm.ENTITIES));
    }

    @Test
    void aRoleThatNoLongerExistsFallsBackToFullMember() {
        Claim c = claim();
        UUID x = member(c, "X");
        c.memberRoles.put(x.toString(), "deleted");
        assertNull(c.roleOf(x));
        assertTrue(c.can(x, Claim.Perm.BUILD));
    }

    @Test
    void removingAMemberDropsTheirRole() {
        Claim c = claim();
        UUID y = member(c, "Y");
        c.roles.put("r", new ArrayList<>());
        c.memberRoles.put(y.toString(), "r");
        c.removeMemberUuid(y);
        assertFalse(c.memberRoles.containsKey(y.toString()));
    }

    @Test
    void legacyClaimWithoutRoleFieldsBehavesAsBefore() {
        Claim c = claim();
        UUID z = member(c, "Z");
        c.roles = null;
        c.memberRoles = null;     // an old claims.json without these keys
        assertTrue(c.can(z, Claim.Perm.BUILD));
        assertNull(c.findRole("anything"));
    }

    @Test
    void permissionNamesParseInBothLanguages() {
        assertEquals(Claim.Perm.BUILD, Claim.Perm.parse("build"));
        assertEquals(Claim.Perm.CONTAINERS, Claim.Perm.parse("сундуки"));
        assertEquals(Claim.Perm.DOORS, Claim.Perm.parse("DOORS"));
        assertNull(Claim.Perm.parse("fly"));
    }

    @Test
    void interactionCategoriesMapToPermissions() {
        assertEquals(Claim.Perm.CONTAINERS, ProtectionService.permFor(ProtectionService.Interaction.CONTAINER));
        assertEquals(Claim.Perm.DOORS, ProtectionService.permFor(ProtectionService.Interaction.DOOR));
        assertEquals(Claim.Perm.ENTITIES, ProtectionService.permFor(ProtectionService.Interaction.ENTITY));
        assertEquals(Claim.Perm.BUILD, ProtectionService.permFor(ProtectionService.Interaction.OTHER));
    }
}
