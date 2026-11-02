package dev.bastionac.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The language of every line the mod writes, one for the whole server.
 *
 * <p>The Russian text in the code is its own key. {@link #t} looks it up in the
 * table of the chosen language and hands it back unchanged when the language is
 * Russian or the line has no translation, so a missing line shows up in Russian
 * instead of as a bare key. A table is the bundled
 * {@code /bastionac/lang/<code>.json} with
 * {@code config/bastionac/lang/<code>.json} laid over it: an admin can fix a
 * single line, or write a whole new language, without rebuilding the mod.
 *
 * <p>Switching is immediate for everything built after the switch. Command
 * argument names ({@link #arg}) are the one exception: the client learns them
 * once, when the command tree is sent, so they stay in the language the server
 * started with.
 */
public final class Lang {

    public static final String DEFAULT = "ru";
    private static final String RESOURCE = "/bastionac/lang/";
    private static final Type TABLE = new TypeToken<Map<String, String>>() {}.getType();

    private static volatile String code = DEFAULT;
    private static volatile Map<String, String> table = Map.of();
    private static volatile Path dir;
    private static final Map<String, String> ARGS = new ConcurrentHashMap<>();

    /** The line in the current language. */
    public static String t(String ru) {
        if (ru == null) return null;
        String s = table.get(ru);
        return s != null ? s : ru;
    }

    /**
     * Marks a default that is stored as written (a config template) and
     * translated where it is shown, through {@link #t}. Returns it unchanged.
     */
    public static String k(String ru) {
        return ru;
    }

    /** A command argument name: translated once and then fixed until restart. */
    public static String arg(String ru) {
        return ARGS.computeIfAbsent(ru, Lang::t);
    }

    public static String code() {
        return code;
    }

    public static boolean isRussian() {
        return DEFAULT.equals(code);
    }

    /** Where admin tables live: {@code config/bastionac/lang}. */
    public static void setDir(Path configDir) {
        dir = configDir == null ? null : configDir.resolve("lang");
    }

    /** "en_US", " EN " -> "en". */
    public static String normalize(String wanted) {
        if (wanted == null) return DEFAULT;
        String s = wanted.trim().toLowerCase(Locale.ROOT);
        int cut = s.indexOf('_');
        if (cut < 0) cut = s.indexOf('-');
        if (cut > 0) s = s.substring(0, cut);
        return s.isEmpty() ? DEFAULT : s;
    }

    /**
     * Switches the language. A language with no table, bundled or in the
     * config folder, is refused and the current one stays; returns whether the
     * switch happened.
     */
    public static synchronized boolean use(String wanted, Consumer<String> warn) {
        String c = normalize(wanted);
        Map<String, String> next = new HashMap<>();
        boolean found = DEFAULT.equals(c);
        try (InputStream in = Lang.class.getResourceAsStream(RESOURCE + c + ".json")) {
            if (in != null) {
                found = true;
                Map<String, String> m = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), TABLE);
                if (m != null) next.putAll(m);
            }
        } catch (Exception e) {
            warn.accept("lang/" + c + ".json (bundled) could not be read: " + e.getMessage());
        }
        Path d = dir;
        Path file = d == null ? null : d.resolve(c + ".json");
        if (file != null && Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, String> m = new Gson().fromJson(r, TABLE);
                if (m != null) {
                    m.values().removeIf(v -> v == null);
                    next.putAll(m);
                }
                found = true;
            } catch (Exception e) {
                warn.accept(file + " is not valid JSON, ignored: " + e.getMessage());
            }
        }
        if (!found) {
            warn.accept("No language \"" + c + "\" (neither bundled nor in " + (d == null ? "lang/" : d) + "); keeping \"" + code + "\"");
            return false;
        }
        table = Map.copyOf(next);
        code = c;
        return true;
    }

    /** The language after the current one in {@link #available()}, wrapping round. */
    public static String next() {
        java.util.List<String> all = new java.util.ArrayList<>(available());
        int i = all.indexOf(code);
        return all.get((i + 1) % all.size());
    }

    /** "Русский", "English", or the bare code of a custom language. */
    public static String displayName(String c) {
        return switch (c) {
            case "ru" -> "Русский";
            case "en" -> "English";
            default -> c;
        };
    }

    /** Bundled languages plus every {@code <code>.json} in the config folder. */
    public static TreeSet<String> available() {
        TreeSet<String> out = new TreeSet<>();
        out.add(DEFAULT);
        out.add("en");
        Path d = dir;
        if (d != null && Files.isDirectory(d)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.json")) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    out.add(normalize(n.substring(0, n.length() - 5)));
                }
            } catch (Exception ignored) {
                // a listing failure only hides custom languages from the hint
            }
        }
        return out;
    }

    private Lang() {}
}
