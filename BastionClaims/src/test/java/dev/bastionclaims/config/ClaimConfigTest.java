package dev.bastionclaims.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimConfigTest {

    private ClaimConfig defaults(Path dir) throws IOException {
        return ClaimConfig.loadOrCreate(dir.resolve("config.json"), w -> {});
    }

    private static String tiersFile(int version, String tiers) {
        return "{\"configVersion\": " + version + ", \"progression\": {\"tiers\": [" + tiers + "]}}";
    }

    private static final String OLD_TIERS = "{\"minHours\": 0, \"maxClaims\": 1, \"maxBlocksPerClaim\": 50000},"
            + "{\"minHours\": 10, \"maxClaims\": 3, \"maxBlocksPerClaim\": 100000},"
            + "{\"minHours\": 20, \"maxClaims\": 6, \"maxBlocksPerClaim\": 120000}";

    @Test
    void defaultTiersMatchDesign(@TempDir Path dir) throws IOException {
        ClaimConfig cfg = defaults(dir);
        // New player.
        assertEquals(2, cfg.maxClaimsFor(0));
        assertEquals(80_000, cfg.maxBlocksFor(0));
        assertEquals(2, cfg.maxClaimsFor(4)); // still tier 0 just under 5h
        // 5 hours.
        assertEquals(4, cfg.maxClaimsFor(5));
        assertEquals(150_000, cfg.maxBlocksFor(5));
        assertEquals(4, cfg.maxClaimsFor(14));
        // 15 hours.
        assertEquals(8, cfg.maxClaimsFor(15));
        assertEquals(250_000, cfg.maxBlocksFor(15));
        assertEquals(8, cfg.maxClaimsFor(500)); // stays at top tier
    }

    @Test
    void tierForPicksHighestReached(@TempDir Path dir) throws IOException {
        ClaimConfig cfg = defaults(dir);
        assertEquals(0, cfg.tierFor(0).minHours);
        assertEquals(5, cfg.tierFor(10).minHours);
        assertEquals(15, cfg.tierFor(9999).minHours);
    }

    @Test
    void emptyProgressionFallsBackToPermissiveDefault() {
        ClaimConfig cfg = new ClaimConfig();
        cfg.progression.tiers.clear();
        // tierFor must never return null even before defaults are filled.
        assertEquals(2, cfg.tierFor(50).maxClaims);
        assertEquals(80_000, cfg.tierFor(50).maxBlocksPerClaim);
    }

    @Test
    void untouchedOldDefaultsAreRaised(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("config.json"), tiersFile(2, OLD_TIERS), StandardCharsets.UTF_8);
        ClaimConfig cfg = defaults(dir);
        assertEquals(2, cfg.maxClaimsFor(0));
        assertEquals(250_000, cfg.maxBlocksFor(20));
        assertEquals(3, cfg.configVersion);
    }

    @Test
    void adminTiersSurviveTheMigration(@TempDir Path dir) throws IOException {
        String own = "{\"minHours\": 0, \"maxClaims\": 1, \"maxBlocksPerClaim\": 10000}";
        Files.writeString(dir.resolve("config.json"), tiersFile(2, own), StandardCharsets.UTF_8);
        ClaimConfig cfg = defaults(dir);
        assertEquals(1, cfg.maxClaimsFor(100));
        assertEquals(10_000, cfg.maxBlocksFor(100));
    }
}
