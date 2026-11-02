package dev.bastionclaims.core;

import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.model.Claim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnZoneTest {

    private static void useConfig(ClaimConfig cfg) throws ReflectiveOperationException {
        Field f = BastionClaims.class.getDeclaredField("config");
        f.setAccessible(true);
        f.set(null, cfg);
    }

    @AfterEach
    void reset() throws ReflectiveOperationException {
        useConfig(null);
    }

    private static ClaimConfig spawnOf(int radius) {
        ClaimConfig cfg = new ClaimConfig();
        cfg.spawnZone.radius = radius;
        cfg.spawnZone.centerX = 100;
        cfg.spawnZone.centerZ = -50;
        return cfg;
    }

    @Test
    void coversTheSquareAtEveryHeightInItsDimensionOnly() throws ReflectiveOperationException {
        useConfig(spawnOf(10));
        Claim inside = ClaimManager.claimAt("minecraft:overworld", 110, -60, -40);
        assertNotNull(inside);
        assertTrue(ClaimManager.isServerZone(inside));
        assertNotNull(ClaimManager.claimAt("minecraft:overworld", 90, 300, -60));
        assertNull(ClaimManager.claimAt("minecraft:overworld", 111, 64, -50));
        assertNull(ClaimManager.claimAt("minecraft:the_nether", 100, 64, -50));
        assertFalse(inside.isOwner(java.util.UUID.randomUUID()));
    }

    @Test
    void nobodyClaimsInsideIt() throws ReflectiveOperationException {
        useConfig(spawnOf(10));
        Claim overlap = ClaimManager.firstOverlap("minecraft:overworld", 108, 0, -45, 130, 80, -30, null);
        assertTrue(ClaimManager.isServerZone(overlap));
        assertNull(ClaimManager.firstOverlap("minecraft:overworld", 111, 0, -45, 130, 80, -30, null));
    }

    @Test
    void offWhenDisabledOrWithoutConfig() throws ReflectiveOperationException {
        assertNull(ClaimManager.claimAt("minecraft:overworld", 100, 64, -50));
        ClaimConfig cfg = spawnOf(10);
        cfg.spawnZone.enabled = false;
        useConfig(cfg);
        assertNull(ClaimManager.claimAt("minecraft:overworld", 100, 64, -50));
    }

    @Test
    void reloadedConfigRebuildsTheZone() throws ReflectiveOperationException {
        useConfig(spawnOf(10));
        assertNull(ClaimManager.claimAt("minecraft:overworld", 130, 64, -50));
        useConfig(spawnOf(40));
        Claim c = ClaimManager.claimAt("minecraft:overworld", 130, 64, -50);
        assertNotNull(c);
        assertEquals(60, c.minX);
    }
}
