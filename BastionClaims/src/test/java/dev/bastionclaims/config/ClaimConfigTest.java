package dev.bastionclaims.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimConfigTest {

    private ClaimConfig defaults(Path dir) throws IOException {
        return ClaimConfig.loadOrCreate(dir.resolve("config.json"), w -> {});
    }

    @Test
    void defaultTiersMatchDesign(@TempDir Path dir) throws IOException {
        ClaimConfig cfg = defaults(dir);
        // New player.
        assertEquals(1, cfg.maxClaimsFor(0));
        assertEquals(50_000, cfg.maxBlocksFor(0));
        assertEquals(1, cfg.maxClaimsFor(9)); // still tier 0 just under 10h
        // 10 hours.
        assertEquals(3, cfg.maxClaimsFor(10));
        assertEquals(100_000, cfg.maxBlocksFor(10));
        assertEquals(3, cfg.maxClaimsFor(19));
        // 20 hours.
        assertEquals(6, cfg.maxClaimsFor(20));
        assertEquals(120_000, cfg.maxBlocksFor(20));
        assertEquals(6, cfg.maxClaimsFor(500)); // stays at top tier
    }

    @Test
    void tierForPicksHighestReached(@TempDir Path dir) throws IOException {
        ClaimConfig cfg = defaults(dir);
        assertEquals(0, cfg.tierFor(0).minHours);
        assertEquals(10, cfg.tierFor(15).minHours);
        assertEquals(20, cfg.tierFor(9999).minHours);
    }

    @Test
    void emptyProgressionFallsBackToPermissiveDefault() {
        ClaimConfig cfg = new ClaimConfig();
        cfg.progression.tiers.clear();
        // tierFor must never return null even before defaults are filled.
        assertEquals(1, cfg.tierFor(50).maxClaims);
        assertEquals(50_000, cfg.tierFor(50).maxBlocksPerClaim);
    }
}
