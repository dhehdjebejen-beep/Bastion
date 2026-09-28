package dev.bastionac.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.bastionac.BastionAC;
import dev.bastionac.util.SafeFiles;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Network registry: the anti-cheat's own memory of which network each ban
 * came from, which accounts shared it, and who is joining from a network
 * that previously hosted a banned account.
 *
 * <p>Threat model (offline-mode server): the vanilla ban list is
 * name/UUID-keyed and a banned player simply registers a new name. The
 * unkeyed network context is what actually follows them. We deliberately
 * work with a <b>subnet token</b> (/24 for IPv4, /64 for IPv6) rather than
 * the raw IP: the low octets rotate on mobile and CGNAT networks, so the
 * subnet is both the stable identifier and the coarsest one — matching at
 * this grain can never be used as sole proof, only as a signal (per the
 * ToS the decision stays human).
 *
 * <p>Privacy: raw IPs are never stored here. Each sighting keeps a masked
 * form ({@code 1.2.*.*}) for humans plus a subnet token for matching;
 * correlating anything finer would need the auth database, which already
 * HMACs full IPs under a key this mod does not hold.
 *
 * <p>Storage: {@code config/bastionac/networks.json} — ban-network records
 * and account-link sightings, both TTL-pruned. Written atomically on the
 * I/O thread like every other store.
 */
public final class NetworkRegistry {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Ban-network records older than this are pruned (ms). */
    private static final long BAN_TTL_MS = 365L * 24 * 3_600_000L;
    /** Link sightings older than this are pruned (ms). */
    private static final long LINK_TTL_MS = 90L * 24 * 3_600_000L;
    /** How many minutes after a ban the same subnet joining is reported. */
    private static final long REPORT_WINDOW_MS = 14L * 24 * 3_600_000L;

    /** One banned player's network context. */
    public static final class BanNetwork {
        public String uuid;
        public String name;
        public String maskedIp;
        public String subnet;
        public long at;
        public String reason;
        /** Which path issued the ban: "chain", "auto" or "manual". */
        public String source;
    }

    /** One "these two accounts were seen on the same subnet" record. */
    public static final class LinkSighting {
        public String uuidA;
        public String nameA;
        public String uuidB;
        public String nameB;
        public String subnet;
        public long at;
        /** Dedup key: ordered "uuidA|uuidB" — not part of the old format, absent in old files. */
        public String pairKey;
        /** Auth-bridge links only: the signals the grade was built from ("device+ip+name"). */
        public String signals;
        /** Auth-bridge links only: LIKELY / CONFIRMED. */
        public String grade;
        /** Auth-bridge links only: the composite score (e.g. "100"). */
        public String score;
    }

    private static final Map<UUID, BanNetwork> BAN_NETWORKS = new ConcurrentHashMap<>();
    private static final List<LinkSighting> LINKS = new ArrayList<>();
    /** Subnet → UUIDs seen there (rebuilt on load, maintained on record). */
    private static final Map<String, Set<String>> SUBNET_UUIDS = new ConcurrentHashMap<>();

    private static volatile Path file;

    private NetworkRegistry() {}

    public static void init(Path configDir) {
        try {
            Files.createDirectories(configDir);
            file = configDir.resolve("networks.json");
            load();
        } catch (Exception e) {
            BastionAC.LOGGER.error("NetworkRegistry init failed", e);
        }
    }

    // ------------------------------------------------------------------ recording

    /** Ban context: called by every ban path, from the server thread. */
    public static void noteBanIp(UUID uuid, String name, ServerPlayerEntity online) {
        noteBanIp(uuid, name, online, "auto");
    }

    /** Ban context with the issuing path: chain / auto / manual. */
    public static void noteBanIp(UUID uuid, String name, ServerPlayerEntity online, String source) {
        if (online == null) return;
        String ip = ipOf(online);
        if (ip == null) return;
        BanNetwork rec = new BanNetwork();
        rec.uuid = uuid.toString();
        rec.name = name;
        rec.maskedIp = mask(ip);
        rec.subnet = subnetOf(ip);
        rec.at = System.currentTimeMillis();
        rec.reason = "ban";
        rec.source = source;
        BAN_NETWORKS.put(uuid, rec);
        NAME_BY_UUID.put(uuid.toString(), name);
        save();
    }

    /** Join-time sighting: records the subnet the player joined from. */
    public static void noteJoin(ServerPlayerEntity player) {
        String ip = ipOf(player);
        if (ip == null) return;
        String subnet = subnetOf(ip);
        String myUuid = player.getUuid().toString();
        String myName = player.getGameProfile().name();
        SUBNET_UUIDS.computeIfAbsent(subnet, s -> ConcurrentHashMap.newKeySet())
                .add(myUuid);
        // Same-subnet correlation: someone else was seen on this subnet
        // before. A sighting, never a proof — recorded for the admin UI.
        Set<String> others = SUBNET_UUIDS.get(subnet);
        if (others != null && others.size() > 1) {
            for (String other : others) {
                if (other.equals(myUuid)) continue;
                String otherName = NAME_BY_UUID.get(other);
                if (otherName == null) continue; // offline, unknown — skip pairing
                noteLinkPair(other, otherName, myUuid, myName, subnet);
            }
        }
    }

    /** Name of an online player, for join-time link pairing. */
    private static final Map<String, String> NAME_BY_UUID = new ConcurrentHashMap<>();

    /** Called by the join hook so links can name both sides. */
    public static void noteName(UUID uuid, String name) {
        if (uuid != null && name != null && !name.isBlank()) NAME_BY_UUID.put(uuid.toString(), name);
    }

    /** Records a same-subnet sighting between two different accounts. */
    public static void noteLink(String uuidA, String nameA, String uuidB, String nameB, String subnet) {
        if (uuidA.equals(uuidB)) return;
        noteLinkPair(uuidA, nameA, uuidB, nameB, subnet);
    }

    /**
     * BastionAuth bridge entry: a graded multi-signal account link. The
     * subnet field carries a marker instead (legacy format compatibility);
     * the signal breakdown, grade and score live in the dedicated fields
     * the UI renders. Called on the auth worker — safe: everything here is
     * thread-safe.
     */
    public static void noteLinkedAccount(String nameA, String uuidA, String nameB, String uuidB) {
        noteLinkedAccount(nameA, uuidA, nameB, uuidB, null, null, null);
    }

    /** Full bridge form: carries the signals, the grade and the composite score. */
    public static void noteLinkedAccount(String nameA, String uuidA, String nameB, String uuidB,
                                         String signals, String grade, String score) {
        if (uuidA == null || uuidB == null || uuidA.equals(uuidB)) return;
        String pairKey = uuidA.compareTo(uuidB) < 0 ? uuidA + "|" + uuidB : uuidB + "|" + uuidA;
        synchronized (LINKS) {
            for (LinkSighting existing : LINKS) {
                if (pairKey.equals(existing.pairKey) && "auth:".equals(existing.subnet)) {
                    // Already recorded — refresh the strongest evidence we
                    // have seen for this pair (a later pass can add the
                    // password signal to an existing join-time link).
                    if (signals != null && existing.signals == null) {
                        existing.signals = signals;
                        existing.grade = grade;
                        existing.score = score;
                    }
                    return;
                }
            }
            LinkSighting l = new LinkSighting();
            l.uuidA = uuidA;
            l.nameA = nameA;
            l.uuidB = uuidB;
            l.nameB = nameB;
            l.subnet = "auth:"; // marker — UI renders it as a multi-signal link
            l.at = System.currentTimeMillis();
            l.pairKey = pairKey;
            l.signals = signals;
            l.grade = grade;
            l.score = score;
            LINKS.add(l);
            while (LINKS.size() > 4000) LINKS.remove(0);
        }
        save();
    }

    /** Dedup: the same pair on the same subnet is one sighting until it ages out. */
    private static void noteLinkPair(String uuidA, String nameA, String uuidB, String nameB, String subnet) {
        if (uuidA.equals(uuidB)) return;
        String pairKey = uuidA.compareTo(uuidB) < 0
                ? uuidA + "|" + uuidB : uuidB + "|" + uuidA;
        synchronized (LINKS) {
            for (LinkSighting l : LINKS) {
                if (pairKey.equals(l.pairKey) && l.subnet.equals(subnet)) return;
            }
        }
        LinkSighting l = new LinkSighting();
        l.uuidA = uuidA;
        l.nameA = nameA;
        l.uuidB = uuidB;
        l.nameB = nameB;
        l.subnet = subnet;
        l.at = System.currentTimeMillis();
        l.pairKey = pairKey;
        synchronized (LINKS) {
            LINKS.add(l);
            while (LINKS.size() > 4000) LINKS.remove(0);
        }
        save();
    }

    // ------------------------------------------------------------------ queries

    /** Masked IP last recorded for a ban of this player, or null. */
    public static String maskedIpOf(UUID uuid) {
        BanNetwork rec = BAN_NETWORKS.get(uuid);
        return rec == null ? null : rec.maskedIp;
    }

    /**
     * Join check: reports to staff when the joiner's subnet matches the
     * subnet of a recent ban. A staff signal only — CGNAT shares subnets
     * across innocent households, so this never acts on its own.
     */
    public static void checkJoinOnBannedIp(ServerPlayerEntity player) {
        String ip = ipOf(player);
        if (ip == null) return;
        String subnet = subnetOf(ip);
        long now = System.currentTimeMillis();
        for (BanNetwork rec : BAN_NETWORKS.values()) {
            if (rec.uuid.equals(player.getUuid().toString())) continue;
            if (!subnet.equals(rec.subnet)) continue;
            if (now - rec.at > REPORT_WINDOW_MS) continue;
            String ago = ago(now - rec.at);
            BastionAC.LOGGER.warn("[NET] {} joined from the subnet of banned {} ({}, {} ago)",
                    player.getGameProfile().name(), rec.name, rec.maskedIp, ago);
            Alerts.sendNetSignal(player.getGameProfile().name(), player.getUuid(), rec.name,
                    "join с подсети недавнего бана", rec.maskedIp + ", " + ago + " назад");
        }
    }

    /** All ban-network records, newest first — for the admin UI. */
    public static List<BanNetwork> banNetworks() {
        List<BanNetwork> out = new ArrayList<>(BAN_NETWORKS.values());
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    /** All link sightings, newest first — for the admin UI. */
    public static List<LinkSighting> links() {
        synchronized (LINKS) {
            List<LinkSighting> out = new ArrayList<>(LINKS);
            out.sort((a, b) -> Long.compare(b.at, a.at));
            return out;
        }
    }

    /** Uuids seen on a subnet (including the joiner themselves). */
    public static Set<String> uuidsOnSubnet(String subnet) {
        Set<String> s = SUBNET_UUIDS.get(subnet);
        return s == null ? Set.of() : Set.copyOf(s);
    }

    // ------------------------------------------------------------------ helpers

    private static String ipOf(ServerPlayerEntity player) {
        try {
            if (player.networkHandler != null) {
                var addr = player.networkHandler.getConnectionAddress();
                if (addr instanceof java.net.InetSocketAddress inet && inet.getAddress() != null) {
                    return inet.getAddress().getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** IPv4 /24 or IPv6 /64 prefix of an IP string. Pure — unit-tested. */
    public static String subnetOf(String ip) {
        if (ip == null || ip.equals("unknown")) return "unknown";
        if (ip.indexOf(':') >= 0) {
            String[] parts = ip.split(":");
            List<String> groups = new ArrayList<>();
            for (String p : parts) {
                if (p.isEmpty()) break;
                groups.add(p);
            }
            if (groups.size() >= 4) return groups.get(0) + ":" + groups.get(1) + ":" + groups.get(2) + ":" + groups.get(3) + "::/64";
            return ip + "/128";
        }
        String[] oct = ip.split("\\.");
        if (oct.length == 4) return oct[0] + "." + oct[1] + "." + oct[2] + ".0/24";
        return ip + "/32";
    }

    /** Partially masked IP for display. Pure — unit-tested. */
    public static String mask(String ip) {
        if (ip == null || ip.isEmpty() || "unknown".equals(ip)) return "?";
        if (ip.indexOf(':') >= 0) {
            String[] groups = ip.split(":");
            if (groups.length >= 2 && !groups[0].isEmpty()) return groups[0] + ":" + groups[1] + "::*";
            return "ipv6:*";
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) return parts[0] + "." + parts[1] + ".*.*";
        return "*";
    }

    static String ago(long deltaMs) {
        long s = Math.max(0, deltaMs) / 1000;
        if (s < 60) return s + "с";
        long m = s / 60;
        if (m < 60) return m + "м";
        long h = m / 60;
        if (h < 24) return h + "ч";
        return (h / 24) + "д";
    }

    /** Public wrapper for the admin UI (same formatting, visible name). */
    public static String agoPublic(long deltaMs) {
        return ago(deltaMs);
    }

    // ------------------------------------------------------------------ persistence

    private static final class Store {
        List<BanNetwork> banNetworks = new ArrayList<>();
        List<LinkSighting> links = new ArrayList<>();
    }

    private static synchronized void load() {
        BAN_NETWORKS.clear();
        synchronized (LINKS) {
            LINKS.clear();
        }
        if (file == null || !Files.exists(file)) return;
        try {
            Type type = new TypeToken<Store>() {}.getType();
            Store store = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (store != null) {
                long now = System.currentTimeMillis();
                if (store.banNetworks != null) {
                    for (BanNetwork rec : store.banNetworks) {
                        if (rec == null || rec.uuid == null) continue;
                        if (now - rec.at > BAN_TTL_MS) continue;
                        try {
                            BAN_NETWORKS.put(UUID.fromString(rec.uuid), rec);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (store.links != null) {
                    for (LinkSighting l : store.links) {
                        if (l == null || l.uuidA == null || now - l.at > LINK_TTL_MS) continue;
                        if (l.pairKey == null && l.uuidB != null) {
                            // migrate old records that predate the dedup key
                            l.pairKey = l.uuidA.compareTo(l.uuidB) < 0
                                    ? l.uuidA + "|" + l.uuidB : l.uuidB + "|" + l.uuidA;
                        }
                        LINKS.add(l);
                    }
                }
            }
            BastionAC.LOGGER.info("BastionAC network registry: {} ban network(s), {} link(s)",
                    BAN_NETWORKS.size(), LINKS.size());
        } catch (Exception e) {
            BastionAC.LOGGER.error("Failed to read networks.json", e);
        }
        rebuildSubnetIndex();
    }

    private static void rebuildSubnetIndex() {
        SUBNET_UUIDS.clear();
        synchronized (LINKS) {
            for (LinkSighting l : LINKS) {
                // Auth-bridge links carry a marker, not a real subnet — they
                // must not pollute the subnet index.
                if (l.subnet == null || l.subnet.startsWith("auth:")) continue;
                SUBNET_UUIDS.computeIfAbsent(l.subnet, s -> ConcurrentHashMap.newKeySet())
                        .add(l.uuidA);
                SUBNET_UUIDS.computeIfAbsent(l.subnet, s -> ConcurrentHashMap.newKeySet())
                        .add(l.uuidB);
            }
        }
        BAN_NETWORKS.values().forEach(rec ->
                SUBNET_UUIDS.computeIfAbsent(rec.subnet, s -> ConcurrentHashMap.newKeySet()).add(rec.uuid));
    }

    /** Test isolation: drops every record without touching the file. */
    static synchronized void forgetAllForTests() {
        BAN_NETWORKS.clear();
        synchronized (LINKS) {
            LINKS.clear();
        }
        SUBNET_UUIDS.clear();
        NAME_BY_UUID.clear();
    }

    private static synchronized void save() {
        if (file == null) return;
        Store store = new Store();
        store.banNetworks = new ArrayList<>(BAN_NETWORKS.values());
        synchronized (LINKS) {
            store.links = new ArrayList<>(LINKS);
        }
        SafeFiles.writeAsync(file, () -> GSON.toJson(store),
                t -> BastionAC.LOGGER.error("Failed to write networks.json", t));
    }
}
