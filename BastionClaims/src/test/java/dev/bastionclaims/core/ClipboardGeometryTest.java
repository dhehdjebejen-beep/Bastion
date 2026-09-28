package dev.bastionclaims.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The clipboard's rotation maths, without a Minecraft world. A paste has to land
 * exactly where the operator saw it, so the offsets that survive a rotation are
 * the part worth pinning down.
 */
class ClipboardGeometryTest {

    /** One 90° clockwise step applied to (sizeX, sizeZ, offX, offZ). */
    private static int[] step(int[] g) {
        int sizeX = g[0], sizeZ = g[1], offX = g[2], offZ = g[3];
        return new int[]{
                sizeZ,                                  // sizes swap
                sizeX,
                Clipboard.rotatedOffX(offZ, sizeZ),
                Clipboard.rotatedOffZ(offX)
        };
    }

    @Test
    void fourRotationsReturnToTheStart() {
        int[][] cases = {
                {5, 3, 0, 0},
                {5, 3, -2, 7},
                {1, 12, 4, -9},
                {16, 16, -8, -8},
        };
        for (int[] start : cases) {
            int[] g = start;
            for (int i = 0; i < 4; i++) g = step(g);
            assertEquals(start[0], g[0], "sizeX after 360°");
            assertEquals(start[1], g[1], "sizeZ after 360°");
            assertEquals(start[2], g[2], "offX after 360°");
            assertEquals(start[3], g[3], "offZ after 360°");
        }
    }

    @Test
    void ninetyDegreesSwapsTheFootprint() {
        int[] g = step(new int[]{5, 3, 0, 0});
        assertEquals(3, g[0]);
        assertEquals(5, g[1]);
    }

    @Test
    void aBufferCopiedAtTheAnchorStaysOnTheAnchor() {
        // A 1×1 column sitting exactly on the player: rotating must not move it.
        int[] g = {1, 1, 0, 0};
        for (int i = 0; i < 4; i++) {
            g = step(g);
            assertEquals(0, g[2], "offX must stay on the anchor");
            assertEquals(0, g[3], "offZ must stay on the anchor");
        }
    }

    @Test
    void oneEightyMirrorsBothOffsets() {
        // Two steps put the far corner where the near corner was.
        int[] g = step(step(new int[]{4, 6, 1, 2}));
        assertEquals(4, g[0]);
        assertEquals(6, g[1]);
        assertEquals(-(1 + 4 - 1), g[2]);
        assertEquals(-(2 + 6 - 1), g[3]);
    }
}
