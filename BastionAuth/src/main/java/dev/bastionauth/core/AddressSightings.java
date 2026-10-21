package dev.bastionauth.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which accounts joined from which address lately — by the address HMAC, never
 * the address itself (1.13).
 *
 * <h2>Why</h2>
 * A resumed session trusts the address: no password if the same IP comes back
 * with the same client. Behind a carrier NAT, in a dormitory or on a family
 * Wi-Fi the address is shared, and anybody there who knows the name and runs
 * a default client walked in. The one thing the server can see is whether the
 * address <em>is</em> shared: another account joining from it while a session
 * waits means it is no secret, and that session is not resumed.
 *
 * <p>Kept in memory, seeded at startup from the last logins in the database
 * (an account's last address and time), so a restart does not forget.
 */
public final class AddressSightings {

    private final Map<String, Map<UUID, Long>> seen = new ConcurrentHashMap<>();

    /** An account joined (or logged in) from this address at {@code at}. */
    public void note(String ipHmac, UUID who, long at) {
        if (ipHmac == null || who == null) return;
        seen.computeIfAbsent(ipHmac, k -> new ConcurrentHashMap<>()).merge(who, at, Math::max);
    }

    /** Whether an account other than {@code self} was seen at this address at or after {@code since}. */
    public boolean sharedSince(String ipHmac, UUID self, long since) {
        Map<UUID, Long> here = ipHmac == null ? null : seen.get(ipHmac);
        if (here == null) return false;
        for (Map.Entry<UUID, Long> e : here.entrySet()) {
            if (!e.getKey().equals(self) && e.getValue() >= since) return true;
        }
        return false;
    }

    /** Forgets sightings older than {@code before}. */
    public void prune(long before) {
        seen.values().forEach(m -> m.values().removeIf(t -> t < before));
        seen.values().removeIf(Map::isEmpty);
    }

    int addresses() {
        return seen.size();
    }
}
