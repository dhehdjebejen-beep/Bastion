package dev.bastionclaims.core;

import dev.bastionclaims.model.Claim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimGeometryTest {

    private static Claim cube(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Claim.of("id", "Bob", "base", "minecraft:overworld", x1, y1, z1, x2, y2, z2);
    }

    @Test
    void volumeIsInclusiveProduct() {
        assertEquals(1000L, cube(0, 60, 0, 9, 69, 9).volume());   // 10*10*10
        assertEquals(1L, cube(5, 5, 5, 5, 5, 5).volume());        // single block
    }

    @Test
    void cornersAreNormalised() {
        Claim a = cube(0, 60, 0, 9, 69, 9);
        Claim b = cube(9, 69, 9, 0, 60, 0); // reversed input
        assertEquals(a.minX, b.minX);
        assertEquals(a.maxZ, b.maxZ);
        assertEquals(a.volume(), b.volume());
    }

    @Test
    void containsRespectsInclusiveBounds() {
        Claim c = cube(0, 60, 0, 9, 69, 9);
        assertTrue(c.contains(0, 60, 0));
        assertTrue(c.contains(9, 69, 9));
        assertTrue(c.contains(5, 65, 5));
        assertFalse(c.contains(10, 65, 5)); // just outside maxX
        assertFalse(c.contains(5, 59, 5));  // below minY
    }

    @Test
    void intersectionDetectsOverlapAndGaps() {
        Claim a = cube(0, 60, 0, 10, 70, 10);
        Claim overlap = cube(5, 65, 5, 15, 75, 15);
        Claim touching = cube(10, 70, 10, 20, 80, 20); // shares one corner block
        Claim gap = cube(11, 60, 11, 20, 70, 20);
        assertTrue(a.intersects(overlap));
        assertTrue(a.intersects(touching));
        assertFalse(a.intersects(gap));
    }

    @Test
    void differentDimensionsNeverIntersect() {
        Claim a = Claim.of("a", "Bob", "x", "minecraft:overworld", 0, 0, 0, 10, 10, 10);
        Claim b = Claim.of("b", "Ann", "y", "minecraft:the_nether", 0, 0, 0, 10, 10, 10);
        assertFalse(a.intersects(b));
    }

    @Test
    void costIsVolumeFor3dAndAreaForFullHeight() {
        Claim box = cube(0, 0, 0, 9, 9, 9);
        assertEquals(1000L, box.cost());   // 3D → volume
        assertEquals(100L, box.area());    // 10*10 footprint

        Claim flat = cube(0, -64, 0, 9, 319, 9);
        flat.fullHeight = true;
        assertEquals(100L, flat.cost());   // 2D → footprint area, not the huge volume
    }

    @Test
    void trustRecognisesOwnerAndMembers() {
        Claim c = cube(0, 0, 0, 5, 5, 5);
        assertTrue(c.isOwner("bob"));      // case-insensitive
        assertFalse(c.isMember("ann"));
        c.addMember("Ann");
        assertTrue(c.isMember("ann"));
        assertTrue(c.isTrusted("ANN"));
    }
}
