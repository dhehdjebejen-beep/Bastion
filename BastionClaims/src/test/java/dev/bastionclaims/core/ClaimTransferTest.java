package dev.bastionclaims.core;

import dev.bastionclaims.model.Claim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handing a claim to another player. Claim names are unique per owner, so the
 * interesting part is what happens when the new owner already has one by that
 * name — the transfer must still go through rather than fail halfway.
 */
class ClaimTransferTest {

    private static final String OW = "minecraft:overworld";
    private static final int MAX_NAME = 24;

    @BeforeEach
    void reset(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws IOException {
        ClaimManager.init(dir);
    }

    @AfterEach
    void detach() {
        // Let go of the temp directory so JUnit can delete it on Windows.
        ClaimManager.detach();
    }

    @Test
    void ownerChangesAndTheClaimFollows() {
        Claim c = ClaimManager.create("Bob", "база", OW, 0, 60, 0, 10, 70, 10);
        ClaimManager.setOwner(c, "Alice", MAX_NAME);

        assertTrue(c.isOwner("Alice"));
        assertFalse(c.isOwner("Bob"));
        assertEquals(1, ClaimManager.countOf("Alice"));
        assertEquals(0, ClaimManager.countOf("Bob"));
    }

    @Test
    void freeNameIsKeptAsIs() {
        Claim c = ClaimManager.create("Bob", "база", OW, 0, 60, 0, 10, 70, 10);
        assertEquals("база", ClaimManager.setOwner(c, "Alice", MAX_NAME));
    }

    @Test
    void collidingNameGetsANumber() {
        ClaimManager.create("Alice", "база", OW, 100, 60, 100, 110, 70, 110);
        Claim c = ClaimManager.create("Bob", "база", OW, 0, 60, 0, 10, 70, 10);

        assertEquals("база 2", ClaimManager.setOwner(c, "Alice", MAX_NAME));
        assertEquals(2, ClaimManager.countOf("Alice"));
    }

    @Test
    void collisionsKeepCountingUp() {
        ClaimManager.create("Alice", "база", OW, 100, 60, 100, 110, 70, 110);
        ClaimManager.create("Alice", "база 2", OW, 200, 60, 200, 210, 70, 210);
        Claim c = ClaimManager.create("Bob", "база", OW, 0, 60, 0, 10, 70, 10);

        assertEquals("база 3", ClaimManager.setOwner(c, "Alice", MAX_NAME));
    }

    @Test
    void longNamesAreTruncatedToFitTheSuffix() {
        String long24 = "аааааааааааааааааааааааа"; // exactly MAX_NAME chars
        assertEquals(MAX_NAME, long24.length());
        ClaimManager.create("Alice", long24, OW, 100, 60, 100, 110, 70, 110);
        Claim c = ClaimManager.create("Bob", long24, OW, 0, 60, 0, 10, 70, 10);

        String result = ClaimManager.setOwner(c, "Alice", MAX_NAME);
        assertTrue(result.length() <= MAX_NAME, "name must stay within the limit: " + result);
        assertTrue(result.endsWith(" 2"), "expected a numeric suffix, got: " + result);
    }

    @Test
    void newOwnerIsNoLongerListedAsAGuest() {
        Claim c = ClaimManager.create("Bob", "база", OW, 0, 60, 0, 10, 70, 10);
        ClaimManager.addMember(c, "Alice");
        assertTrue(c.isMember("Alice"));

        ClaimManager.setOwner(c, "Alice", MAX_NAME);
        assertFalse(c.isMember("Alice"), "the owner must not also sit in the guest list");
        assertTrue(c.isTrusted("Alice"));
    }
}
