package dev.bastionac.core;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where a far sound is moved to: same direction, no coordinates (3.3.1). */
class InfoShieldTest {

    @Test
    void aMovedSoundKeepsItsLoudness() {
        // A goat horn (volume 16, heard to 256 blocks) 200 blocks away, moved to 64.
        float v = InfoShield.keptLoudness(16f, 200, 64);
        double before = 1 - 200 / (16.0 * 16);
        double after = 1 - 64 / (16.0 * Math.max(v, 1));
        assertEquals(before, after, 1e-6, "the fade at the listener is the same");
        assertEquals(5f, InfoShield.keptLoudness(5f, 50, 64), "not moved, not changed");
    }

    @Test
    void aNearSoundKeepsItsPlace() {
        assertNull(InfoShield.towards(new Vec3d(0, 64, 0), new Vec3d(20, 64, 0), InfoShield.GLOBAL_SOUND_KEEP));
    }

    @Test
    void aFarSoundKeepsOnlyItsDirection() {
        Vec3d me = new Vec3d(100, 70, -50);
        Vec3d wither = new Vec3d(12_000, 40, -5_000);
        Vec3d at = InfoShield.towards(me, wither, InfoShield.GLOBAL_SOUND_KEEP);
        assertEquals(InfoShield.GLOBAL_SOUND_KEEP, at.distanceTo(me), 1e-9);
        Vec3d real = wither.subtract(me).normalize(), shown = at.subtract(me).normalize();
        assertEquals(1.0, real.dotProduct(shown), 1e-12, "the direction is the same");
    }
}
