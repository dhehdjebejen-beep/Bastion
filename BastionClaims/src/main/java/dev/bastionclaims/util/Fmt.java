package dev.bastionclaims.util;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.regex.Pattern;

/** Colour-code translation for config/command strings ({@code &a} → §a). */
public final class Fmt {

    // Compiled once: String.replaceAll re-compiles the pattern on every call.
    private static final Pattern COLOR = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Pattern STRIP = Pattern.compile("(?i)&[0-9a-fk-or]");
    private static final Pattern FORMATTING = Pattern.compile("[&§]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    public static String color(String s) {
        return s == null ? "" : COLOR.matcher(s).replaceAll("§$1");
    }

    /** Strips {@code &}-colour codes, leaving only visible text. */
    public static String strip(String s) {
        return s == null ? "" : STRIP.matcher(s).replaceAll("");
    }

    /**
     * Cleans player-supplied text (claim names) before it is stored.
     *
     * <p>Every display path runs names through {@link #color}, so a name of
     * {@code &c&l[SERVER]} rendered as a formatted red system line in the action
     * bar of anyone who walked into the claim. Both marker characters go, along
     * with control characters that could break the line layout. Done at the
     * point of storage rather than at display: a name is written once and shown
     * in a dozen places, and one of them will always be forgotten.
     */
    public static String sanitize(String s) {
        if (s == null) return "";
        // Whole colour codes go first ("&c&lдом" -> "дом", not "clдом"), then any
        // stray marker left over, then control characters that break line layout.
        String out = STRIP.matcher(s).replaceAll("");
        out = FORMATTING.matcher(out).replaceAll("");
        out = CONTROL.matcher(out).replaceAll("");
        return out.trim();
    }

    public static MutableText text(String s) {
        return Text.literal(color(s));
    }

    /** An item label with italics disabled (vanilla italicises custom names). */
    public static Text label(String s) {
        return text(s).styled(st -> st.withItalic(false));
    }

    /**
     * A one-click chat button that runs {@code command} immediately and shows
     * {@code hover} on mouse-over.
     */
    public static MutableText button(String labelText, String command, String hover) {
        return text(labelText).styled(s -> s
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(text(hover))));
    }

    private Fmt() {}
}
