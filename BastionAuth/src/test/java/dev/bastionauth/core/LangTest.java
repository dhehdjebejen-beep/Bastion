package dev.bastionauth.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangTest {

    private static final Pattern CALL = Pattern.compile(
            "\\bLang\\.(?:t|k|arg)\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\"(?:\\s*\\+\\s*\"(?:[^\"\\\\]|\\\\.)*\")*)\\s*[,)]");
    private static final Pattern PART = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxc%n]");
    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");

    @AfterEach
    void backToRussian() {
        Lang.setDir(null);
        Lang.use("ru", w -> {});
    }

    private static Map<String, String> bundled(String code) throws IOException {
        try (var in = LangTest.class.getResourceAsStream("/bastionauth/lang/" + code + ".json")) {
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {}.getType());
        }
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> b.append(n);
            }
        }
        return b.toString();
    }

    /** Every Lang.t/k/arg literal in the sources. */
    private static List<String> sourceKeys() throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = CALL.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    StringBuilder key = new StringBuilder();
                    Matcher part = PART.matcher(m.group(1));
                    while (part.find()) key.append(unescape(part.group(1)));
                    out.add(key.toString());
                }
            }
        }
        return out;
    }

    private static List<String> formats(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = FORMAT.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    @Test
    void everyLineInTheCodeHasAnEnglishTranslation() throws IOException {
        Map<String, String> en = bundled("en");
        TreeSet<String> missing = new TreeSet<>();
        for (String k : sourceKeys()) if (CYRILLIC.matcher(k).find() && !en.containsKey(k)) missing.add(k);
        assertTrue(missing.isEmpty(), "untranslated: " + missing);
    }

    @Test
    void translationsKeepTheirPlaceholdersAndAreEnglish() throws IOException {
        for (Map.Entry<String, String> e : bundled("en").entrySet()) {
            assertEquals(formats(e.getKey()), formats(e.getValue()), "placeholders of: " + e.getKey());
            assertFalse(CYRILLIC.matcher(e.getValue()).find(), "Cyrillic left in: " + e.getValue());
        }
    }

    @Test
    void russianIsTheIdentityAndEnglishTranslates() {
        assertEquals("&aПароль изменён.", Lang.t("&aПароль изменён."));
        assertTrue(Lang.use("en_US", w -> {}));
        assertEquals("en", Lang.code());
        assertEquals("&aPassword changed.", Lang.t("&aПароль изменён."));
        assertEquals("текст, которого нет в таблице", Lang.t("текст, которого нет в таблице"));
        assertTrue(Lang.use("ru", w -> {}));
        assertEquals("&aПароль изменён.", Lang.t("&aПароль изменён."));
    }

    @Test
    void anUnknownLanguageIsRefusedAndTheCurrentOneStays() {
        Lang.use("en", w -> {});
        List<String> warnings = new ArrayList<>();
        assertFalse(Lang.use("xx", warnings::add));
        assertEquals("en", Lang.code());
        assertEquals(1, warnings.size());
    }

    @Test
    void aFileInTheConfigFolderOverridesSingleLinesAndAddsLanguages(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("lang"));
        Files.writeString(dir.resolve("lang/en.json"), "{\"&aПароль изменён.\": \"&7Short.\"}");
        Files.writeString(dir.resolve("lang/de.json"), "{\"&aПароль изменён.\": \"&7Kurz.\"}");
        Lang.setDir(dir);
        assertTrue(Lang.available().containsAll(List.of("ru", "en", "de")));
        assertTrue(Lang.use("en", w -> {}));
        assertEquals("&7Short.", Lang.t("&aПароль изменён."));
        assertEquals("&cWrong codeword.", Lang.t("&cНеверное кодовое слово."), "the rest of the bundled table stays");
        assertTrue(Lang.use("de", w -> {}));
        assertEquals("&7Kurz.", Lang.t("&aПароль изменён."));
        assertEquals("&cНеверное кодовое слово.", Lang.t("&cНеверное кодовое слово."), "an untranslated line falls back to Russian");
        assertEquals("en", Lang.next(), "de wraps round past ru to en");
    }

    @Test
    void nextCyclesThroughTheLanguages() {
        Lang.use("ru", w -> {});
        assertEquals("en", Lang.next());
        Lang.use("en", w -> {});
        assertEquals("ru", Lang.next());
    }
}
