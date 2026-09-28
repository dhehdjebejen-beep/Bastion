package dev.bastionac.core;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.regex.Pattern;

/** Colour-code translation and placeholder substitution for config strings. */
public final class TextFmt {

    // Compiled once: String.replaceAll re-compiles the pattern on every call.
    private static final Pattern COLOR = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Pattern STRIP = Pattern.compile("(?i)&[0-9a-fk-or]");

    /** Translates {@code &a}-style colour codes to §-codes. */
    public static String color(String s) {
        return s == null ? "" : COLOR.matcher(s).replaceAll("§$1");
    }

    /** Removes {@code &a}-style colour codes, leaving plain text. */
    public static String strip(String s) {
        return s == null ? "" : STRIP.matcher(s).replaceAll("");
    }

    /** Replaces {@code %key%} placeholders, then colourises. */
    public static String apply(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("%" + e.getKey() + "%", e.getValue() == null ? "" : e.getValue());
        }
        return color(out);
    }

    public static MutableText literal(String s) {
        return Text.literal(color(s));
    }

    public static MutableText applied(String template, Map<String, String> vars) {
        return Text.literal(apply(template, vars));
    }

    private TextFmt() {}
}
