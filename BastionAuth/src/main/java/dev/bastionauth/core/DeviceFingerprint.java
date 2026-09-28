package dev.bastionauth.core;

import dev.bastionauth.BastionAuth;
import dev.bastionauth.crypto.Secrets;
import net.minecraft.server.network.ServerPlayerEntity;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * Device fingerprinting without any client modification: everything used
 * here is metadata the vanilla protocol already hands the server during the
 * configuration and play phases.
 *
 * <ul>
 *   <li><b>Client brand</b> — the string the client sends in its brand
 *       plugin message ("vanilla", "fabric", or a proxy's telltale). A
 *       modified launcher almost always leaks here.</li>
 *   <li><b>Synced client options</b> — language, view distance, chat
 *       visibility, main arm, model parts, particle status. Unique to a user
 *       profile; remarkably stable across sessions of the same human.</li>
 *   <li><b>Network /24</b> — the subnet of the connection address. Mobile
 *       carriers rotate the low octets but keep the /24 for weeks.</li>
 * </ul>
 *
 * <p>The three go through HMAC-SHA256 with the server's secret (the same
 * pepper store BastionAuth already keeps in {@code secrets.bin}), producing a
 * stable {@code DeviceHash}. Equal hashes across different UUIDs mean the
 * same installation profile — a strong twin signal, reported to staff as
 * {@code LinkedAccountDetected} without auto-punishing (per the agreement,
 * a technical link alone is grounds for a decision, not a verdict).
 */
public final class DeviceFingerprint {

    /** Cached hash per online player uuid. */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, String> HASHES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Client brand per uuid, captured from the {@code minecraft:brand} custom
     * payload by the network-handler mixin. Vanilla 1.21.11 no longer stores
     * the brand server-side, so the payload tap is the only reliable source.
     */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, String> BRANDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Brands parked during the configuration phase, keyed by the CONNECTION
     * they arrived on. The brand plugin message arrives before the player
     * object and its UUID exist, and two players can be mid-handshake at the
     * same moment — a single shared slot let one connection's brand be
     * claimed by the other's join. Keying by the connection (the handler
     * instance the payload was read on) makes the handoff unambiguous: a
     * brand payload followed by a join on the same connection refers to the
     * same client by construction, and different connections can never
     * see each other's strings.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Object, String> PENDING_BRANDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Called by the packet mixin when a brand payload arrives (any phase). */
    public static void noteBrandPayload(Object connection, String brand) {
        if (connection == null || brand == null || brand.isBlank()) return;
        PENDING_BRANDS.put(connection, brand);
    }

    /**
     * Binds the connection's parked brand payload to this player's uuid.
     * Called on join with the same connection object the payload arrived on.
     */
    public static String claimPendingBrand(Object connection, UUID uuid) {
        if (connection == null || uuid == null) return null;
        String brand = PENDING_BRANDS.remove(connection);
        if (brand != null && !brand.isBlank()) {
            BRANDS.put(uuid, brand);
            return brand;
        }
        return null;
    }

    /** Called by the packet mixin when the brand payload arrives. */
    public static void recordBrand(UUID uuid, String brand) {
        if (uuid == null || brand == null || brand.isBlank()) return;
        BRANDS.put(uuid, brand);
    }

    private DeviceFingerprint() {}

    // ------------------------------------------------------------------ pre-login material

    /**
     * What the handshake and login phases reveal before a player exists:
     * the address string the client connected with, its protocol version
     * and the profile UUID it claims. Parked by connection object.
     */
    public record PreLogin(String host, int port, int protocol, String helloName, UUID helloUuid, long atMillis) {}

    private static final java.util.concurrent.ConcurrentHashMap<Object, PreLogin> PENDING_PRELOGIN =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Claimed pre-login material per online uuid. */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, PreLogin> PRELOGIN =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Handshake tap (netty thread). */
    public static void noteHandshake(Object connection, String address, int port, int protocol) {
        if (connection == null) return;
        String host = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        // Forge/FML and some proxies append a NUL-separated marker; keep the host only.
        int nul = host.indexOf('\0');
        if (nul >= 0) host = host.substring(0, nul);
        if (host.length() > 255) host = host.substring(0, 255);
        PENDING_PRELOGIN.put(connection, new PreLogin(host, port, protocol, null, null, System.currentTimeMillis()));
        pruneStalePreLogin();
    }

    /** Login-hello tap (netty thread). */
    public static void noteHello(Object connection, String name, UUID profileId) {
        if (connection == null) return;
        PreLogin prev = PENDING_PRELOGIN.get(connection);
        PENDING_PRELOGIN.put(connection, new PreLogin(
                prev == null ? "" : prev.host(), prev == null ? 0 : prev.port(),
                prev == null ? 0 : prev.protocol(), name, profileId, System.currentTimeMillis()));
    }

    /** Binds the connection's parked pre-login material to the joined player. */
    public static PreLogin claimPreLogin(Object connection, UUID uuid) {
        if (connection == null || uuid == null) return null;
        PreLogin p = PENDING_PRELOGIN.remove(connection);
        if (p != null) PRELOGIN.put(uuid, p);
        return p;
    }

    public static PreLogin preLoginOf(UUID uuid) {
        return PRELOGIN.get(uuid);
    }

    /** Connections that never reached play (status pings, refused logins) must not pile up. */
    private static void pruneStalePreLogin() {
        if (PENDING_PRELOGIN.size() < 256) return;
        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        PENDING_PRELOGIN.entrySet().removeIf(e -> e.getValue().atMillis() < cutoff);
    }

    /**
     * The launcher-claimed UUID, or null when it carries no information: the
     * client sent nothing, or it sent exactly the name-derived offline UUID
     * (which every vanilla-faithful launcher does — no identity in that).
     */
    public static String informativeClientUuid(UUID playerUuid, PreLogin p) {
        if (p == null || p.helloUuid() == null) return null;
        if (p.helloUuid().equals(playerUuid)) return null;
        if (p.helloName() != null && p.helloUuid().equals(
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + p.helloName()).getBytes(StandardCharsets.UTF_8)))) {
            return null;
        }
        return p.helloUuid().toString();
    }

    // ------------------------------------------------------------------ channel fingerprint

    /**
     * The plugin channels the client declared it can receive (Fabric API's
     * {@code minecraft:register}): a list of the client's networked mods.
     * Two installs with the same mod set produce the same list. Returned
     * sorted and joined, bounded, or null when nothing beyond the bare
     * Fabric/vanilla baseline was declared (no signal in that).
     */
    public static String channelsOf(ServerPlayerEntity player) {
        try {
            java.util.Set<net.minecraft.util.Identifier> ids =
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.getSendable(player);
            if (ids == null || ids.isEmpty()) return null;
            java.util.TreeSet<String> sorted = new java.util.TreeSet<>();
            for (net.minecraft.util.Identifier id : ids) {
                String s = id.toString();
                if (s.startsWith("minecraft:")) continue;
                sorted.add(s);
            }
            if (sorted.isEmpty()) return null;
            String joined = String.join(",", sorted);
            return joined.length() > 4000 ? joined.substring(0, 4000) : joined;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the channel list is only Fabric API's own channels — every Fabric client has those. */
    public static boolean channelsAreBaseline(String channels) {
        if (channels == null || channels.isEmpty()) return true;
        for (String c : channels.split(",")) {
            if (!c.startsWith("fabric") && !c.startsWith("c:")) return false;
        }
        return true;
    }

    /** HMAC of the brand + client options only — the install profile without the network. */
    public static String computeProfile(ServerPlayerEntity player, Secrets secrets) {
        try {
            return secrets.deviceHmac("profile|" + describe(player));
        } catch (Exception e) {
            return null;
        }
    }

    /** Computes and caches the player's device hash. Call on join (server thread). */
    public static String record(ServerPlayerEntity player, Secrets secrets) {
        String hash = compute(player, secrets);
        if (hash != null) HASHES.put(player.getUuid(), hash);
        return hash;
    }

    public static String cached(UUID uuid) {
        return HASHES.get(uuid);
    }

    public static void forget(UUID uuid) {
        HASHES.remove(uuid);
        BRANDS.remove(uuid);
        PRELOGIN.remove(uuid);
    }

    /** The composite fingerprint string (pre-hash), for audit logs. */
    public static String describe(ServerPlayerEntity player) {
        StringBuilder sb = new StringBuilder();
        sb.append(brandOf(player));
        SyncedClientOptionsView view = optionsOf(player);
        sb.append('|').append(view.language())
          .append('|').append(view.viewDistance())
          .append('|').append(view.chatVisibility())
          .append('|').append(view.chatColorsEnabled())
          .append('|').append(view.playerModelParts())
          .append('|').append(view.mainArm())
          .append('|').append(view.filtersText())
          .append('|').append(view.allowsServerListing())
          .append('|').append(view.particleStatus());
        return sb.toString();
    }

    /** HMAC-SHA256(brand + client options + /24 subnet). Null if anything is missing. */
    public static String compute(ServerPlayerEntity player, Secrets secrets) {
        try {
            String subnet = subnetOf(player);
            if (subnet == null) return null;
            String material = describe(player) + "|" + subnet;
            return secrets.deviceHmac(material);
        } catch (Exception e) {
            BastionAuth.LOGGER.warn("DeviceFingerprint failed for {}: {}",
                    player.getGameProfile().name(), e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ parts

    /** The client brand string ("vanilla" for an untouched client), as
     *  captured from the brand plugin message. */
    public static String brandOf(ServerPlayerEntity player) {
        String brand = BRANDS.get(player.getUuid());
        if (brand == null || brand.isBlank()) return "unknown";
        return brand.toLowerCase(Locale.ROOT);
    }

    /** The /24 (IPv4) or /64 (IPv6) subnet of the player's address. */
    public static String subnetOf(ServerPlayerEntity player) {
        try {
            SocketAddress address = player.networkHandler.getConnectionAddress();
            if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
                return subnetOf(inet.getAddress().getHostAddress());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Pure subnet derivation, for tests and audit lines. */
    public static String subnetOf(String ip) {
        if (ip == null || ip.equals("unknown")) return null;
        if (ip.contains(":")) {
            // IPv6: /64 prefix
            int cut = ip.indexOf("::") >= 0 ? indexOf64(ip) : -1;
            String[] parts = ip.split(":");
            if (parts.length >= 4) return parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3] + "::/64";
            return ip + "/128";
        }
        // IPv4: /24
        String[] oct = ip.split("\\.");
        if (oct.length == 4) return oct[0] + "." + oct[1] + "." + oct[2] + ".0/24";
        return ip + "/32";
    }

    private static int indexOf64(String ip) {
        return ip.indexOf("::");
    }

    /** Accessor view over the vanilla SyncedClientOptions record. */
    public record SyncedClientOptionsView(
            String language, int viewDistance, String chatVisibility, boolean chatColorsEnabled,
            int playerModelParts, String mainArm, boolean filtersText, boolean allowsServerListing,
            String particleStatus) {}

    private static SyncedClientOptionsView optionsOf(ServerPlayerEntity player) {
        try {
            var o = player.getClientOptions();
            return new SyncedClientOptionsView(
                    o.language(), o.viewDistance(),
                    String.valueOf(o.chatVisibility()), o.chatColorsEnabled(),
                    o.playerModelParts(), String.valueOf(o.mainArm()),
                    o.filtersText(), o.allowsServerListing(),
                    String.valueOf(o.particleStatus()));
        } catch (Exception e) {
            return new SyncedClientOptionsView("?", -1, "?", false, -1, "?", false, false, "?");
        }
    }

    /** Constant-time comparison helper for two hashes. */
    public static boolean sameDevice(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
