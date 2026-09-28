package dev.bastionclaims.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the claim-registry fail-closed startup policy. */
class ClaimManagerRecoveryTest {

    @AfterEach
    void detach() {
        ClaimManager.detach();
    }

    @Test
    void unreadablePrimaryRecoversOnlyFromValidBackup(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws IOException {
        Files.writeString(dir.resolve("claims.json"), "{not-json", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("claims.json.bak"), """
                [{
                  "id":"recovered-claim",
                  "owner":"Alice",
                  "name":"base",
                  "dim":"minecraft:overworld",
                  "minX":0,"minY":60,"minZ":0,
                  "maxX":10,"maxY":70,"maxZ":10
                }]
                """, StandardCharsets.UTF_8);

        ClaimManager.init(dir);

        assertFalse(ClaimManager.isReadFailed());
        assertEquals(1, ClaimManager.all().size());
        assertEquals("recovered-claim", ClaimManager.claimAt("minecraft:overworld", 5, 65, 5).id);
    }

    @Test
    void unreadablePrimaryWithoutBackupFailsClosed(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws IOException {
        Files.writeString(dir.resolve("claims.json"), "{not-json", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> ClaimManager.init(dir));

        assertTrue(ClaimManager.isReadFailed());
        assertTrue(ClaimManager.all().isEmpty());
    }

    @Test
    void invertedZoneBoundsFailClosed(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws IOException {
        Files.writeString(dir.resolve("claims.json"), """
                [{
                  "id":"bad-bounds",
                  "owner":"Alice",
                  "dim":"minecraft:overworld",
                  "minX":10,"minY":60,"minZ":0,
                  "maxX":0,"maxY":70,"maxZ":10
                }]
                """, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> ClaimManager.init(dir));

        assertTrue(ClaimManager.isReadFailed());
        assertTrue(ClaimManager.all().isEmpty());
    }

    @Test
    void structurallyInvalidClaimFailsClosed(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws IOException {
        Files.writeString(dir.resolve("claims.json"), """
                [{
                  "id":"",
                  "owner":"Alice",
                  "dim":"minecraft:overworld",
                  "minX":0,"minY":60,"minZ":0,
                  "maxX":10,"maxY":70,"maxZ":10
                }]
                """, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> ClaimManager.init(dir));

        assertTrue(ClaimManager.isReadFailed());
        assertTrue(ClaimManager.all().isEmpty());
    }
}
