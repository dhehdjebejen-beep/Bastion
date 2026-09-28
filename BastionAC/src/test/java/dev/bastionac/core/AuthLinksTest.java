package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anti-cheat side of the link-register bridge. BastionAuth flattens each
 * ledger row into fourteen fields joined by U+0001; this pins the decoding, so
 * a change on either side that shifts a field shows up here rather than as an
 * admin panel that silently lists nothing.
 */
class AuthLinksTest {

    private static final String SEP = "\u0001";

    private static String row(String... fields) {
        return String.join(SEP, fields);
    }

    @Test
    void decodesAFullRow() {
        AuthLinks.Link l = AuthLinks.decode(row(
                "uuid-a", "alpha", "uuid-b", "beta", "195", "CONFIRMED", "device+launcher-uuid",
                "TRUSTED", "LinkOp", "1700000000000", "мои тестовые", "1600000000000", "1700000000001", "3"));
        assertNotNull(l);
        assertEquals("alpha", l.nameA());
        assertEquals("beta", l.nameB());
        assertEquals(195, l.bestScore());
        assertEquals("TRUSTED", l.verdict());
        assertEquals("мои тестовые", l.note());
        assertEquals(3, l.times());
        assertFalse(l.unreviewed());
        assertFalse(l.manual());
    }

    @Test
    void emptyFieldsFallBackToSafeValues() {
        AuthLinks.Link l = AuthLinks.decode(row(
                "uuid-a", "", "uuid-b", "", "0", "NONE", "manual", "", "", "0", "", "1", "2", "0"));
        assertNotNull(l);
        assertEquals("?", l.nameA(), "a nameless side still renders");
        assertEquals("NEW", l.verdict(), "a row without a verdict is undecided, never trusted");
        assertTrue(l.unreviewed());
        assertTrue(l.manual());
    }

    @Test
    void malformedRowsAreDroppedNotThrown() {
        assertNull(AuthLinks.decode("garbage"));
        assertNull(AuthLinks.decode(row("a", "b", "c")));
        assertNull(AuthLinks.decode(row(
                "uuid-a", "alpha", "uuid-b", "beta", "not-a-number", "CONFIRMED", "device",
                "NEW", "", "0", "", "1", "2", "3")));
    }

    @Test
    void verdictLabelsCoverEveryStatus() {
        for (String v : List.of("NEW", "TRUSTED", "WATCH", "ALT")) {
            assertFalse(AuthLinks.verdictLabel(v).isBlank(), v);
            assertTrue(AuthLinks.verdictColour(v).startsWith("&"), v);
        }
        assertEquals("Не проверена", AuthLinks.verdictLabel(null));
    }

    @Test
    void withoutBastionAuthTheRegisterIsSimplyAbsent() {
        // No BastionAuth on the test classpath: every call must degrade, not throw.
        assertFalse(AuthLinks.available());
        assertTrue(AuthLinks.links(null, 10).isEmpty());
        assertFalse(AuthLinks.setVerdict("a", "b", "TRUSTED", "x", ""));
        assertEquals(0, AuthLinks.unreviewedCount());
        assertNull(AuthLinks.uuidOfName("anyone"));
    }

    @Test
    void subnetSightingKeepsBothUuids() {
        // Regression: the subnet path stored the second NAME in the uuidB field,
        // so the pair could never be matched to anything by uuid afterwards.
        NetworkRegistry.forgetAllForTests();
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        NetworkRegistry.noteLink(a, "alice", b, "bob", "10.0.0.0/24");
        NetworkRegistry.LinkSighting l = NetworkRegistry.links().get(0);
        assertEquals(a, l.uuidA);
        assertEquals(b, l.uuidB);
        assertEquals("bob", l.nameB);
    }
}
