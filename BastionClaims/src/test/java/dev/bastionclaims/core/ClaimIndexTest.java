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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Exercises the chunk index, overlap detection and persistence of ClaimManager. */
class ClaimIndexTest {

    private static final String OW = "minecraft:overworld";

    @BeforeEach
    void reset(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws IOException {
        ClaimManager.init(dir); // clears in-memory state and points at a fresh file
    }

    @AfterEach
    void detach() {
        // Let go of the temp directory so JUnit can delete it on Windows.
        ClaimManager.detach();
    }

    @Test
    void claimAtFindsBlocksInsideAndMissesOutside() {
        Claim c = ClaimManager.create("Bob", "base", OW, 0, 60, 0, 15, 70, 15);
        assertSame(c, ClaimManager.claimAt(OW, 5, 65, 5));
        assertSame(c, ClaimManager.claimAt(OW, 0, 60, 0));
        assertNull(ClaimManager.claimAt(OW, 100, 65, 100));   // far away
        assertNull(ClaimManager.claimAt(OW, 5, 80, 5));       // above the box
        assertNull(ClaimManager.claimAt("minecraft:the_nether", 5, 65, 5)); // wrong dim
    }

    @Test
    void claimSpanningManyChunksIsFoundEverywhere() {
        // 300-block wide claim spans ~19 chunk columns.
        ClaimManager.create("Bob", "big", OW, 0, 60, 0, 299, 70, 299);
        assertNotNull(ClaimManager.claimAt(OW, 0, 65, 0));
        assertNotNull(ClaimManager.claimAt(OW, 150, 65, 150));
        assertNotNull(ClaimManager.claimAt(OW, 299, 65, 299));
        assertNull(ClaimManager.claimAt(OW, 300, 65, 300));
    }

    @Test
    void overlapDetectionBlocksConflictingClaims() {
        ClaimManager.create("Bob", "base", OW, 0, 60, 0, 20, 70, 20);
        assertNotNull(ClaimManager.firstOverlap(OW, 10, 65, 10, 30, 75, 30, null)); // overlaps
        assertNull(ClaimManager.firstOverlap(OW, 21, 60, 21, 40, 70, 40, null));    // just clear
        assertNull(ClaimManager.firstOverlap("minecraft:the_nether", 0, 60, 0, 20, 70, 20, null));
    }

    @Test
    void countAndRemoveAreConsistent() {
        Claim a = ClaimManager.create("Bob", "a", OW, 0, 60, 0, 5, 65, 5);
        ClaimManager.create("Bob", "b", OW, 100, 60, 100, 105, 65, 105);
        assertEquals(2, ClaimManager.countOf("BOB")); // case-insensitive owner
        ClaimManager.remove(a);
        assertEquals(1, ClaimManager.countOf("bob"));
        assertNull(ClaimManager.claimAt(OW, 2, 62, 2)); // a's area now unclaimed
    }
}
