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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A claim made of several cuboids: protection, cost and the chunk index. */
class ClaimZonesTest {

    private static final String OW = "minecraft:overworld";

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
    void extraZoneIsProtectedAndIndexed() {
        Claim c = ClaimManager.create("Bob", "base", OW, 0, 60, 0, 15, 70, 15);
        assertNull(ClaimManager.claimAt(OW, 100, 65, 100), "far block starts unclaimed");

        ClaimManager.addZone(c, Claim.Box.of(96, 60, 96, 111, 70, 111));

        assertSame(c, ClaimManager.claimAt(OW, 100, 65, 100), "new zone is protected");
        assertSame(c, ClaimManager.claimAt(OW, 5, 65, 5), "original zone still protected");
        assertNull(ClaimManager.claimAt(OW, 50, 65, 50), "the gap between zones stays free");
        assertEquals(2, c.zoneCount());
    }

    @Test
    void costCountsEveryZone() {
        Claim c = ClaimManager.create("Bob", "base", OW, 0, 60, 0, 9, 69, 9); // 10*10*10
        assertEquals(1000, c.cost());
        ClaimManager.addZone(c, Claim.Box.of(20, 60, 20, 29, 69, 29));        // another 1000
        assertEquals(2000, c.cost());
    }

    @Test
    void aSecondClaimCannotOverlapAnExtraZone() {
        Claim c = ClaimManager.create("Bob", "base", OW, 0, 60, 0, 15, 70, 15);
        ClaimManager.addZone(c, Claim.Box.of(96, 60, 96, 111, 70, 111));

        assertNotNull(ClaimManager.firstOverlap(OW, 100, 60, 100, 120, 70, 120, null),
                "overlapping the added zone must be refused");
        assertNull(ClaimManager.firstOverlap(OW, 40, 60, 40, 60, 70, 60, null),
                "the gap between zones is still claimable");
    }

    @Test
    void removingAZoneFreesTheLandButKeepsTheClaim() {
        Claim c = ClaimManager.create("Bob", "base", OW, 0, 60, 0, 15, 70, 15);
        ClaimManager.addZone(c, Claim.Box.of(96, 60, 96, 111, 70, 111));

        assertTrue(ClaimManager.removeZone(c, 2));
        assertNull(ClaimManager.claimAt(OW, 100, 65, 100), "removed zone is unclaimed again");
        assertSame(c, ClaimManager.claimAt(OW, 5, 65, 5), "the claim itself survives");
        assertEquals(1, c.zoneCount());
        assertFalse(ClaimManager.removeZone(c, 1), "the primary zone cannot be removed");
    }

    @Test
    void zonesSharingAChunkDoNotLeaveStaleIndexEntries() {
        // Two zones in the same chunk column: removing the claim must clear both.
        Claim c = ClaimManager.create("Bob", "base", OW, 0, 60, 0, 3, 62, 3);
        ClaimManager.addZone(c, Claim.Box.of(8, 60, 8, 11, 62, 11));
        assertSame(c, ClaimManager.claimAt(OW, 9, 61, 9));

        ClaimManager.remove(c);
        assertNull(ClaimManager.claimAt(OW, 1, 61, 1));
        assertNull(ClaimManager.claimAt(OW, 9, 61, 9));
    }

    @Test
    void distanceToNearestZoneIsZeroWhenTouching() {
        Claim c = ClaimManager.create("Bob", "base", OW, 0, 60, 0, 9, 69, 9);
        assertEquals(0, c.distanceToNearestZone(Claim.Box.of(10, 60, 0, 19, 69, 9)),
                "a zone sharing a face counts as adjacent");
        assertEquals(40, c.distanceToNearestZone(Claim.Box.of(50, 60, 0, 59, 69, 9)));
    }
}
