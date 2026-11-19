package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A world change while vanilla still waits for the client to confirm the teleport. */
class WorldChangeTeleportTest {

    @Test
    void waitsWhileVanillaWaits() {
        PlayerData d = new PlayerData(UUID.randomUUID(), "p");
        d.beginTeleport(404.4, 54, 731.6, 100);
        d.afterWorldChange(true, 404.4, 54, 731.6, 101);
        assertTrue(d.teleportPending, "the old world's last move must not be judged against the new one");
        assertEquals(404.4, d.tpX, 1e-9);
        assertEquals(101, d.teleportPendingSince);
    }

    @Test
    void doesNotWaitWhenNothingIsPending() {
        PlayerData d = new PlayerData(UUID.randomUUID(), "p");
        d.beginTeleport(1, 2, 3, 100);
        d.afterWorldChange(false, 5, 6, 7, 101);
        assertFalse(d.teleportPending);
    }
}
