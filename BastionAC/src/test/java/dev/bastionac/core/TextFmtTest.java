package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextFmtTest {

    @Test
    void colorTranslatesAmpersandCodes() {
        assertEquals("§aHello", TextFmt.color("&aHello"));
        assertEquals("§cRed §lBold", TextFmt.color("&cRed &lBold"));
    }

    @Test
    void colorIsCaseInsensitiveAndLeavesText() {
        assertEquals("§Enope", TextFmt.color("&Enope"));
        assertEquals("no codes here", TextFmt.color("no codes here"));
        assertEquals("", TextFmt.color(null));
    }

    @Test
    void colorIgnoresInvalidCodes() {
        // &z is not a valid colour code and must stay literal.
        assertEquals("&zstuff", TextFmt.color("&zstuff"));
    }

    @Test
    void applyReplacesPlaceholdersThenColors() {
        String out = TextFmt.apply("%prefix%%name%&7 joined", Map.of("prefix", "&c[A] ", "name", "Steve"));
        assertEquals("§c[A] Steve§7 joined", out);
    }

    @Test
    void applyLeavesUnknownPlaceholders() {
        assertEquals("hi %missing%", TextFmt.apply("hi %missing%", Map.of("name", "x")));
    }
}
