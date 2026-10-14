package dev.bastionac.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Every {@code minecraft:brand} payload a connection sends, in order, from the
 * configuration phase on. Keyed by the connection object, which lives from
 * the first configuration packet to the last play packet; weak, so a closed
 * connection simply falls out.
 *
 * <p>A vanilla client sends its brand exactly once. A "client spoof" module
 * that rewrites it typically resends it later (or sends two different ones),
 * which this ledger keeps for {@code ClientChecks.checkBrand}.
 */
public final class BrandLedger {

    private static final Map<Object, List<String>> SEEN = Collections.synchronizedMap(new WeakHashMap<>());

    private BrandLedger() {}

    /** Netty thread: a brand payload arrived on this connection. */
    public static void note(Object connection, String brand) {
        if (connection == null) return;
        synchronized (SEEN) {
            List<String> l = SEEN.computeIfAbsent(connection, c -> new ArrayList<>());
            if (l.size() < 16) l.add(brand == null ? "" : brand);
        }
    }

    /** What the connection sent so far (a copy). */
    public static List<String> seen(Object connection) {
        if (connection == null) return List.of();
        synchronized (SEEN) {
            List<String> l = SEEN.get(connection);
            return l == null ? List.of() : new ArrayList<>(l);
        }
    }
}
