package dev.bastionclaims.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.bastionclaims.BastionClaims;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves blocks by their in-game Russian name (e.g. «камень», «воздух»,
 * «динамит») for the {@code /we} commands. Names come from the official 1.21.11
 * translations bundled at {@code /bastionclaims/blocks_ru.json}; matched to live
 * blocks by translation key at first use. English ids still work as a fallback.
 */
public final class BlockNames {

    private static final Map<String, Block> BY_RU = new ConcurrentHashMap<>();
    private static final List<String> DISPLAY = new ArrayList<>();
    private static volatile boolean loaded;

    /** Convenience shorthands resolved before the normal lookup (e.g. "0" == air). */
    private static final Map<String, String> ALIASES = Map.of(
            "0", "minecraft:air");

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Map<String, String> lang;
        try (InputStream in = BlockNames.class.getResourceAsStream("/bastionclaims/blocks_ru.json")) {
            if (in == null) {
                BastionClaims.LOGGER.warn("blocks_ru.json not found; Russian block names disabled");
                return;
            }
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            lang = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            BastionClaims.LOGGER.error("Failed to load blocks_ru.json", e);
            return;
        }
        if (lang == null) return;
        for (Block b : Registries.BLOCK) {
            String ru = lang.get(b.getTranslationKey());   // "block.minecraft.stone" -> "Камень"
            if (ru == null || ru.isBlank()) continue;
            if (BY_RU.putIfAbsent(ru.toLowerCase(Locale.ROOT), b) == null) {
                DISPLAY.add(ru);
            }
        }
        DISPLAY.sort(String.CASE_INSENSITIVE_ORDER);
        BastionClaims.LOGGER.info("BastionClaims: {} Russian block names loaded", BY_RU.size());
    }

    /** Russian name, or English id/identifier, to a default block state; {@code null} if unknown. */
    public static BlockState resolve(String input) {
        ensureLoaded();
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;

        String alias = ALIASES.get(s.toLowerCase(Locale.ROOT));
        if (alias != null) s = alias;

        Block ru = BY_RU.get(s.toLowerCase(Locale.ROOT));
        if (ru != null) return ru.getDefaultState();

        // English identifier fallback (invalid/non-ascii input throws → treated as unknown).
        try {
            String id = s.toLowerCase(Locale.ROOT).replace(' ', '_');
            Identifier ident = id.contains(":") ? Identifier.of(id) : Identifier.of("minecraft", id);
            return Registries.BLOCK.getOptionalValue(ident).map(Block::getDefaultState).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Russian display names, for command tab-completion. */
    public static List<String> suggestions() {
        ensureLoaded();
        return DISPLAY;
    }

    private BlockNames() {}
}
