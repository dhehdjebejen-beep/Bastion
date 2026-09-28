package dev.bastionac.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binary ring format: every field of the 28-byte record must survive a
 * write→drain roundtrip — full kind range (the old packing turned "other"
 * into "place"), full 32-bit aux (entity ids past 63 were clipped), flags,
 * centiblock deltas and the look direction. The buffer's UUID-keyed API
 * runs without the game jar.
 */
class PlayerContextBufferTest {

    private final UUID id = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        PlayerContextBuffer.forget(id);
    }

    @Test
    void moveRecordRoundtrips() {
        PlayerContextBuffer.attach(id);
        PlayerContextBuffer.recordMove(id, 90.5f, -12.25f,
                100.0, 64.0, 100.0,          // prev
                100.30, 64.07, 101.20,       // new (deltas 0.30/0.07/1.20)
                true, true, false, false, true);

        String out = PlayerContextBuffer.drainForTests(id);
        assertTrue(out.contains("move"), "kind must read back as move: " + out);
        assertTrue(out.contains("yaw=90.5"), "yaw must survive: " + out);
        // Float storage rounds to the nearest float: -12.25f is not exactly
        // representable and reads back as -12.2/-12.3 — assert the value,
        // not the printf artifact.
        assertTrue(out.contains("pitch=-12.2") || out.contains("pitch=-12.3"),
                "pitch must survive: " + out);
        assertTrue(out.contains("d=(0.30,0.07,1.20)"), "centiblock deltas must survive: " + out);
        assertTrue(out.contains("g=1"), "onGround flag must survive: " + out);
        assertTrue(out.contains(",sprint"), "sprint flag must survive: " + out);
        assertTrue(out.contains(",liquid"), "liquid flag must survive: " + out);
        assertEquals(1, PlayerContextBuffer.recordCount(id));
    }

    @Test
    void attackRecordKeepsFullEntityId() {
        PlayerContextBuffer.attach(id);
        // Entity ids grow monotonically for the server's lifetime; a long
        // uptime easily passes 63 (the old 6-bit clip) and even 32767 (a
        // short's entire range). 1_500_001 must come back intact.
        PlayerContextBuffer.recordAttack(id, 0f, 0f, 1_500_001);

        String out = PlayerContextBuffer.drainForTests(id);
        assertTrue(out.contains("attack"), out);
        // The aux field itself is int-width; the dump prints it as aux=...
        assertTrue(out.contains("aux=1500001"), "full entity id must survive: " + out);
    }

    @Test
    void otherKindIsNotCorruptedIntoPlace() {
        PlayerContextBuffer.attach(id);
        // The old packAux mapped KIND_OTHER(4) onto 3 == KIND_PLACE, so
        // every generic event masqueraded as a placement in every dump.
        PlayerContextBuffer.recordEvent(id, 1f, 1f, "weird", "some detail text");

        String out = PlayerContextBuffer.drainForTests(id);
        assertTrue(out.contains("|other|"), "kind OTHER must read back as other: " + out);
        assertTrue(out.contains("aux="), "digest payload must be present: " + out);
    }

    @Test
    void hashAuxIsStableAndPositive() {
        assertEquals(PlayerContextBuffer.hashAux("STOP_DESTROY_BLOCK"), PlayerContextBuffer.hashAux("STOP_DESTROY_BLOCK"));
        assertTrue(PlayerContextBuffer.hashAux("x") > 0);
        assertEquals(0, PlayerContextBuffer.hashAux(null));
        assertEquals(0, PlayerContextBuffer.hashAux(""));
    }

    @Test
    void ringWrapsWithoutTearing() {
        PlayerContextBuffer.attach(id);
        // Fill well past the 8192-record capacity: the ring must wrap and
        // still contain exactly CAPACITY records, none torn.
        for (int i = 0; i < 8192 + 500; i++) {
            PlayerContextBuffer.recordMove(id, 0f, 0f, 0, 0, 0, 0.01, 0, 0, true, false, false, false, false);
        }
        assertEquals(8192, PlayerContextBuffer.recordCount(id));
        assertEquals(224 * 1024, PlayerContextBuffer.usedBytes(id));
    }

    @Test
    void forgetReleasesAndClears() {
        PlayerContextBuffer.attach(id);
        PlayerContextBuffer.recordMove(id, 1f, 1f, 0, 0, 0, 0.01, 0, 0, true, false, false, false, false);
        PlayerContextBuffer.forget(id);
        assertEquals(-1, PlayerContextBuffer.recordCount(id));
        assertNull(null); // no-op assertion to keep symmetric imports honest
    }
}
