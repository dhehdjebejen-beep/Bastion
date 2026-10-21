package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProactiveTest {

    @Test
    void withoutArgusEveryCheckMitigatesAsBefore() {
        // No Argus in a unit test: the mode can never leave a server without sanctions.
        assertFalse(Proactive.enabled());
        assertTrue(Proactive.mayMitigate(UUID.randomUUID(), CheckType.REACH));
    }

    @Test
    void enforcementRunsOutAndCanBeCleared() {
        UUID p = UUID.randomUUID();
        long now = 1_000_000L;
        assertTrue(Proactive.enforce(p, 30, now), "first switch-on is news");
        assertFalse(Proactive.enforce(p, 30, now + 1000), "a renewal is not");
        assertTrue(Proactive.isEnforced(p, now + 29 * 60_000L));
        assertEquals(30, Proactive.minutesLeft(p, now + 1000));
        assertFalse(Proactive.isEnforced(p, now + 31 * 60_000L + 1000));
        Proactive.enforce(p, 30, now);
        Proactive.clear(p);
        assertFalse(Proactive.isEnforced(p, now));
    }
}
