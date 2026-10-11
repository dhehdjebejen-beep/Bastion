package dev.bastionac.argus.features;

import dev.bastionac.telemetry.SessionData;

import java.util.List;
import java.util.Locale;

/**
 * What the client says it is. The big Russian clients (Nursultan, Celestial,
 * Expensive and their forks) hide their channels and call themselves
 * vanilla, so this rarely fires on them — but the free and open ones, and
 * careless pastes, still announce themselves, and an announcement is a fact.
 */
public final class ClientFeatures {

    /**
     * Channel namespaces of known cheat clients and cheat-only mods. A legit
     * client never registers these. Matched on the namespace before the ':'.
     * Only unambiguous names: "celestial", "future", "impact" and the like
     * are also the names of honest mods (Celestial is a custom-sky mod).
     */
    static final List<String> CHEAT_NAMESPACES = List.of(
            "meteor-client", "meteorclient", "wurst", "liquidbounce", "thunderhack", "catlean", "boze",
            "rusherhack", "aristois", "bleachhack", "coffeeclient", "nursultan", "wexside", "akrien",
            "seedcracker");

    private ClientFeatures() {}

    public static void analyze(SessionData s, FeatureSet out) {
        if (s.brand == null) return;
        boolean cheat = false;
        for (String ch : s.channels) {
            String ns = ch.toLowerCase(Locale.ROOT);
            int colon = ns.indexOf(':');
            if (colon > 0) ns = ns.substring(0, colon);
            for (String bad : CHEAT_NAMESPACES) {
                if (ns.equals(bad) || ns.startsWith(bad + "-") || ns.startsWith(bad + "_")) {
                    cheat = true;
                    break;
                }
            }
            if (cheat) break;
        }
        out.flag("cli.cheat_channel", cheat);
        String brand = s.brand.toLowerCase(Locale.ROOT);
        boolean vanillaBrand = brand.equals("vanilla") || brand.equals("minecraft");
        boolean modded = false;
        for (String ch : s.channels) {
            if (!ch.startsWith("minecraft:")) {
                modded = true;
                break;
            }
        }
        out.flag("cli.brand_spoof", vanillaBrand && modded);
    }

    /** The channel that matched, for the report (null when none). */
    public static String cheatChannel(SessionData s) {
        for (String ch : s.channels) {
            String ns = ch.toLowerCase(Locale.ROOT);
            int colon = ns.indexOf(':');
            if (colon > 0) ns = ns.substring(0, colon);
            for (String bad : CHEAT_NAMESPACES) {
                if (ns.equals(bad) || ns.startsWith(bad + "-") || ns.startsWith(bad + "_")) return ch;
            }
        }
        return null;
    }
}
