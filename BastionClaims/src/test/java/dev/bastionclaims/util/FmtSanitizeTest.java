package dev.bastionclaims.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim names are rendered through {@link Fmt#color} everywhere they appear, so
 * anything that survives sanitisation with a colour marker in it becomes
 * formatted text on someone else's screen.
 */
class FmtSanitizeTest {

    @Test
    void stripsAmpersandColourMarkers() {
        // The spoofing case: this used to render as a red bold "system" line in
        // the action bar of everyone who walked into the claim.
        String cleaned = Fmt.sanitize("&c&l[SERVER] выдача");
        assertFalse(cleaned.contains("&"), cleaned);
        assertEquals("[SERVER] выдача", cleaned);
    }

    @Test
    void stripsSectionSignToo() {
        assertFalse(Fmt.sanitize("§4§lадмин").contains("§"));
    }

    @Test
    void sanitizedNameSurvivesColouring() {
        // The real invariant: whatever we store must come back out of Fmt.color
        // unchanged, i.e. carry no formatting of its own.
        String name = Fmt.sanitize("&aдом &4у &1реки");
        assertEquals(name, Fmt.color(name));
    }

    @Test
    void dropsControlCharacters() {
        String cleaned = Fmt.sanitize("дом\nдва");
        assertFalse(cleaned.contains("\n"));
        assertFalse(cleaned.contains(""));
    }

    @Test
    void keepsOrdinaryNamesIntact() {
        assertEquals("Мой дом 2", Fmt.sanitize("  Мой дом 2  "));
        assertEquals("base_01", Fmt.sanitize("base_01"));
    }

    @Test
    void nullAndBlankAreEmpty() {
        assertEquals("", Fmt.sanitize(null));
        assertTrue(Fmt.sanitize("   ").isEmpty());
        // A name made only of markers must end up empty, so creation rejects it
        // instead of storing an invisible claim name.
        assertTrue(Fmt.sanitize("&a&b&c").isEmpty());
    }
}
