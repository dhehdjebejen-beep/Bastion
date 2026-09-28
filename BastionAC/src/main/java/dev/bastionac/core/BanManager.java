package dev.bastionac.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.bastionac.BastionAC;
import dev.bastionac.config.ACConfig;
import dev.bastionac.util.SafeFiles;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Progressive auto-tempban. Staff alerts per player are counted in a sliding
 * window; crossing the threshold issues a vanilla temp-ban whose length
 * escalates with how many bans the player already has in the history window.
 * Ban history is persisted so escalation survives restarts.
 */
public final class BanManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Alert timestamps (ms) per player — in memory, survive rejoin, pruned by time.
    private static final Map<UUID, Deque<Long>> ALERTS = new ConcurrentHashMap<>();
    // Ban timestamps (ms) per player — persisted for escalation.
    private static final Map<UUID, Deque<Long>> BAN_HISTORY = new ConcurrentHashMap<>();

    private static volatile Path file;
    private static volatile MinecraftServer server;

    public static void init(MinecraftServer srv, Path configDir) throws IOException {
        server = srv;
        Files.createDirectories(configDir);
        file = configDir.resolve("bans.json");
        load();
    }

    public static void shutdown() {
        server = null;
        ALERTS.clear();
    }

    /**
     * Drops alert windows that can no longer influence anything.
     *
     * <p>Deliberately time-based and NOT tied to disconnect: the alert window
     * must survive a rejoin, otherwise a cheater sitting one alert below the
     * ban threshold simply relogs to reset the counter and can never be banned.
     * Called from the server tick; the map is only as big as "distinct players
     * who tripped a check in the last window".
     */
    public static void sweep(long nowMillis) {
        ACConfig.Punishment cfg = BastionAC.config().punishment;
        long windowMs = cfg.alertWindowMinutes * 60_000L;
        ALERTS.entrySet().removeIf(e -> {
            Deque<Long> hits = e.getValue();
            synchronized (hits) {
                while (!hits.isEmpty() && nowMillis - hits.peekFirst() > windowMs) hits.pollFirst();
                return hits.isEmpty();
            }
        });
    }

    /**
     * Records one staff alert for the player. Returns true (and issues the
     * ban) when the alert threshold within the window is reached.
     */
    public static boolean onAlert(ServerPlayerEntity player, CheckType type) {
        ACConfig.Punishment cfg = BastionAC.config().punishment;
        if (!cfg.autoTempbanEnabled) return false;
        // Staff are never auto-banned: a false positive on an admin locks the
        // people who could undo it out of the server. They still get kicked
        // and alerted like everyone else.
        if (isExemptOperator(player)) return false;
        // FP-prone / mod-sensitive checks (vehicles, sprint-jump…) never ban.
        if (cfg.banExcludedChecks != null && cfg.banExcludedChecks.contains(type.key)) return false;

        long now = System.currentTimeMillis();
        UUID uuid = player.getUuid();
        Deque<Long> hits = ALERTS.computeIfAbsent(uuid, u -> new ArrayDeque<>());
        long windowMs = cfg.alertWindowMinutes * 60_000L;
        synchronized (hits) {
            hits.addLast(now);
            while (!hits.isEmpty() && now - hits.peekFirst() > windowMs) hits.pollFirst();
            if (hits.size() < cfg.alertsToBan) return false;
            hits.clear(); // consumed — do not re-ban on the very next alert
        }
        issueBan(player, now, cfg);
        return true;
    }

    private static void issueBan(ServerPlayerEntity player, long now, ACConfig.Punishment cfg) {
        UUID uuid = player.getUuid();
        String name = player.getGameProfile().name();

        long historyMs = cfg.banHistoryHours * 3_600_000L;
        Deque<Long> history = BAN_HISTORY.computeIfAbsent(uuid, u -> new ArrayDeque<>());
        int priorBans;
        synchronized (history) {
            while (!history.isEmpty() && now - history.peekFirst() > historyMs) history.pollFirst();
            priorBans = history.size();
            history.addLast(now);
        }
        save();

        int hours = banHours(priorBans, cfg.banStepsHours);
        long until = now + hours * 3_600_000L;
        String reason = TextFmt.strip(cfg.banReason);
        noteBanNetwork(uuid, name, player);

        MinecraftServer srv = server;
        if (srv != null) {
            srv.execute(() -> {
                PlayerConfigEntry entry = new PlayerConfigEntry(uuid, name);
                srv.getPlayerManager().getUserBanList().add(new BannedPlayerEntry(
                        entry, new Date(now), "BastionAC", new Date(until), reason));
                ServerPlayerEntity online = srv.getPlayerManager().getPlayer(uuid);
                if (online != null && online.networkHandler != null) {
                    online.networkHandler.disconnect(TextFmt.literal(cfg.banReason
                            + "\n&7Срок: &f" + formatDuration(hours)));
                }
                if (cfg.broadcastBan) {
                    srv.getPlayerManager().broadcast(TextFmt.literal(
                            "&8[&cBAC&8] &e" + name + " &7заблокирован автоматически на &f" + formatDuration(hours)), false);
                }
            });
        }
        HistoryManager.recordPunishment(HistoryManager.Kind.TEMPBAN, uuid, name, null, hours, "авто-бан по алертам");
        BastionAC.LOGGER.warn("[TEMPBAN] {} banned for {}h (prior bans in window: {})", name, hours, priorBans);
    }

    /**
     * A burned attack chain is an incident, not a single flag: the correlated
     * pattern repeated past the point where the engine was already watching.
     * Bans at the top of the escalation ladder (never a plain step — the
     * chain score earned that), records the network context (masked IP) the
     * way every ban now does, and reports to staff. Called from the chain
     * detector through the server-like hook, on the server thread.
     */
    /** Legacy entry: no check signature. */
    public static void chainBan(UUID uuid, String name, int burnedCount) {
        chainBan(uuid, name, burnedCount, "");
    }

    /**
     * @param kinds the distinct ban-eligible checks that burned the chain,
     *              joined with '+', for the ban reason and the history line.
     */
    public static void chainBan(UUID uuid, String name, int burnedCount, String kinds) {
        MinecraftServer srv = server;
        if (srv == null) return;
        ACConfig.Punishment cfg = BastionAC.config().punishment;
        if (!cfg.autoTempbanEnabled) return;
        ServerPlayerEntity onlineNow = srv.getPlayerManager().getPlayer(uuid);
        if (onlineNow != null && isExemptOperator(onlineNow)) {
            BastionAC.LOGGER.warn("[CHAINBAN] {} is staff — chain burned ({}) but no ban (punishment.exemptOperators)",
                    name, kinds);
            return;
        }
        long now = System.currentTimeMillis();
        // The normal ladder: prior bans in the history window decide the
        // length, exactly like an alert-ladder ban. A burned chain is strong
        // evidence, not a reason to skip to the top step.
        long historyMs = cfg.banHistoryHours * 3_600_000L;
        Deque<Long> history = BAN_HISTORY.computeIfAbsent(uuid, u -> new ArrayDeque<>());
        int priorBans;
        synchronized (history) {
            while (!history.isEmpty() && now - history.peekFirst() > historyMs) history.pollFirst();
            priorBans = history.size();
            history.addLast(now);
        }
        save();
        int hours = banHours(priorBans, cfg.banStepsHours);
        final int fHours = hours;
        long until = now + hours * 3_600_000L;
        String reason = "BastionAC: цепочка нарушений (" + (kinds == null || kinds.isEmpty() ? "несколько проверок" : kinds) + ")";

        // Network context FIRST: the registry entry is what the log line and
        // the admin UI read below — a chain ban must appear on the "Network"
        // tab like every other ban does.
        noteBanNetwork(uuid, name, onlineNow, "chain");
        String maskedIp = NetworkRegistry.maskedIpOf(uuid);
        srv.execute(() -> {
            PlayerConfigEntry entry = new PlayerConfigEntry(uuid, name);
            srv.getPlayerManager().getUserBanList().add(new BannedPlayerEntry(
                    entry, new Date(now), "BastionAC", new Date(until), reason));
            ServerPlayerEntity online = srv.getPlayerManager().getPlayer(uuid);
            if (online != null && online.networkHandler != null) {
                online.networkHandler.disconnect(TextFmt.literal(
                        "&c&lВы заблокированы.\n&7Причина: подтверждённая цепочка нарушений.\n&7Обжалование: официальный канал MaxCora."));
            }
            if (cfg.broadcastBan) {
                srv.getPlayerManager().broadcast(TextFmt.literal(
                        "&8[&cBAC&8] &e" + name + " &7заблокирован по цепочке атак на &f"
                                + formatDuration(fHours)), false);
            }
        });
        HistoryManager.recordPunishment(HistoryManager.Kind.EDR_CHAIN_BAN, uuid, name, kinds, hours,
                "burned chain #" + burnedCount + " [" + kinds + "]" + (maskedIp == null ? "" : " ip=" + maskedIp));
        BastionAC.LOGGER.warn("[CHAINBAN] {} banned {}h for a burned attack chain (#{}, checks={}, prior bans={}, ip={})",
                name, hours, burnedCount, kinds, priorBans, maskedIp == null ? "?" : maskedIp);
    }

    /**
     * Kick + staff alert for protocol abuse that is disruptive but not
     * dangerous (BookBot, chat floods): the connection is dropped, the
     * incident is recorded, nobody is banned.
     */
    public static void kickWithAlert(ServerPlayerEntity player, String trigger, String detail) {
        MinecraftServer srv = server;
        if (srv == null || player == null) return;
        UUID uuid = player.getUuid();
        String name = player.getGameProfile().name();
        HistoryManager.recordPunishment(HistoryManager.Kind.KICK, uuid, name, trigger, 0,
                "packet-guard: " + trigger + (detail == null ? "" : " — " + detail));
        srv.execute(() -> {
            if (player.networkHandler != null) {
                player.networkHandler.disconnect(TextFmt.literal(
                        "&cСоединение сброшено защитой сервера.\n&7Причина: " + trigger));
            }
            Alerts.sendNetSignal(name, uuid, trigger, "кик защитой протокола", detail == null ? trigger : detail);
        });
        BastionAC.LOGGER.warn("[GUARD-KICK] {} kicked: {} ({})", name, trigger, detail);
    }

    /** True for staff when punishment.exemptOperators is on (default). */
    public static boolean isExemptOperator(ServerPlayerEntity player) {
        if (player == null) return false;
        ACConfig.Punishment cfg = BastionAC.config().punishment;
        return cfg.exemptOperators && Alerts.isAdmin(player);
    }

    /**
     * Register the network context (masked IP) of a ban for cross-reference
     * by the join-time check. Called by every ban-issuing path.
     */
    private static void noteBanNetwork(UUID uuid, String name, ServerPlayerEntity online) {
        NetworkRegistry.noteBanIp(uuid, name, online);
    }

    /** Same, with the issuing path (chain / auto / manual). */
    private static void noteBanNetwork(UUID uuid, String name, ServerPlayerEntity online, String source) {
        NetworkRegistry.noteBanIp(uuid, name, online, source);
    }

    /**
     * Called on join (server thread): reports to staff when the joining
     * player's IP was previously seen on a banned account. A signal for
     * humans, never an automatic action — CGNAT shares IPs across innocent
     * households, and the ToS keeps the decision human.
     */
    public static void reportJoinOnBannedIp(ServerPlayerEntity player) {
        NetworkRegistry.checkJoinOnBannedIp(player);
    }

    // ------------------------------------------------------------------
    // Catastrophic-threat permanent ban
    // ------------------------------------------------------------------

    /**
     * Unconditional automatic permanent ban for a catastrophic threat: a
     * crash exploit, a packet flood, an NBT/compression bomb — anything where
     * the server's integrity is on the line and waiting for staff attention
     * means losing it. Not part of the alert ladder: called directly by the
     * packet filters the moment their criteria are met.
     *
     * <p>The vanilla ban entry is permanent (no expiry), the socket is dropped
     * at once, and the incident is recorded to history and the log so the
     * human review afterwards has the full context.
     */
    public static void catastrophicBan(ServerPlayerEntity player, String trigger, String detail) {
        ACConfig.Punishment cfg = BastionAC.config().punishment;
        if (isExemptOperator(player)) {
            // Staff: drop the connection and page everyone, never a permanent ban.
            BastionAC.LOGGER.error("[CATASTROPHIC] {} is staff — kicked instead of banned; trigger: {} ({})",
                    player.getGameProfile().name(), trigger, detail);
            HistoryManager.recordPunishment(HistoryManager.Kind.KICK, player.getUuid(), player.getGameProfile().name(),
                    trigger, 0, "catastrophic (staff, no ban): " + trigger + (detail == null ? "" : " — " + detail));
            MinecraftServer srv0 = server;
            if (srv0 != null) srv0.execute(() -> {
                if (player.networkHandler != null) {
                    player.networkHandler.disconnect(TextFmt.literal("&cСоединение сброшено защитой сервера (" + trigger + ")."));
                }
            });
            return;
        }
        noteBanNetwork(player.getUuid(), player.getGameProfile().name(), player);
        if (!cfg.catastrophicPermabanEnabled) {
            // Disabled by config: degrade to the harshest temp-ban step.
            manualBan(player, cfg.banStepsHours.isEmpty() ? 720 : cfg.banStepsHours.get(cfg.banStepsHours.size() - 1),
                    "BastionAC catastrophic trigger (permaban disabled): " + trigger);
            return;
        }
        UUID uuid = player.getUuid();
        String name = player.getGameProfile().name();
        long now = System.currentTimeMillis();
        String reason = "BastionAC: катастрофическая угроза — " + trigger
                + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");

        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            PlayerConfigEntry entry = new PlayerConfigEntry(uuid, name);
            srv.getPlayerManager().getUserBanList().add(new BannedPlayerEntry(
                    entry, new Date(now), "BastionAC", null, reason));   // null expiry = permanent
            ServerPlayerEntity online = srv.getPlayerManager().getPlayer(uuid);
            if (online != null && online.networkHandler != null) {
                online.networkHandler.disconnect(TextFmt.literal(
                        "&c&lВы заблокированы навсегда.\n&7Причина: попытка нарушить работу сервера.\n"
                                + "&7Обжалование: официальный канал MaxCora."));
            }
            if (cfg.broadcastBan) {
                srv.getPlayerManager().broadcast(TextFmt.literal(
                        "&8[&cBAC&8] &e" + name + " &7заблокирован навсегда: угроза работе сервера"), false);
            }
        });
        HistoryManager.recordPunishment(HistoryManager.Kind.MANUAL_BAN, uuid, name, null, 0,
                "catastrophic: " + trigger + (detail == null ? "" : " — " + detail));
        BastionAC.LOGGER.error("[PERMABAN] {} permanently banned — trigger: {} detail: {}",
                name, trigger, detail);
    }

    // ------------------------------------------------------------------
    // Manual moderation (from the admin panel)
    // ------------------------------------------------------------------

    /** Admin-issued temp-ban for a fixed number of hours. Also feeds escalation history. */
    public static void manualBan(ServerPlayerEntity target, int hours, String by) {
        MinecraftServer srv = server;
        if (srv == null || hours < 1) return;
        UUID uuid = target.getUuid();
        String name = target.getGameProfile().name();
        long now = System.currentTimeMillis();
        long until = now + hours * 3_600_000L;
        String reason = "Ручной бан BastionAC (" + by + ")";
        noteBanNetwork(uuid, name, target, "manual");

        Deque<Long> history = BAN_HISTORY.computeIfAbsent(uuid, u -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(now);
        }
        save();

        srv.execute(() -> {
            PlayerConfigEntry entry = new PlayerConfigEntry(uuid, name);
            srv.getPlayerManager().getUserBanList().add(new BannedPlayerEntry(
                    entry, new Date(now), by, new Date(until), reason));
            ServerPlayerEntity online = srv.getPlayerManager().getPlayer(uuid);
            if (online != null && online.networkHandler != null) {
                online.networkHandler.disconnect(TextFmt.literal(
                        "&cВы заблокированы администрацией.\n&7Срок: &f" + formatDuration(hours)));
            }
            srv.getPlayerManager().broadcast(TextFmt.literal(
                    "&8[&cBAC&8] &e" + name + " &7заблокирован администратором на &f" + formatDuration(hours)), false);
        });
        HistoryManager.recordPunishment(HistoryManager.Kind.MANUAL_BAN, uuid, name, null, hours, "админ: " + by);
        BastionAC.LOGGER.warn("[MANUALBAN] {} banned by {} for {}h", name, by, hours);
    }

    /** Removes a player from the vanilla ban list (admin unban). @return true if a ban was removed. */
    public static boolean unban(UUID uuid, String name, String by) {
        MinecraftServer srv = server;
        if (srv == null) return false;
        PlayerConfigEntry entry = new PlayerConfigEntry(uuid, name);
        boolean was = srv.getPlayerManager().getUserBanList().get(entry) != null;
        srv.getPlayerManager().getUserBanList().remove(entry);
        if (was) {
            HistoryManager.recordPunishment(HistoryManager.Kind.UNBAN, uuid, name, null, 0, "админ: " + by);
            BastionAC.LOGGER.info("[UNBAN] {} unbanned by {}", name, by);
        }
        return was;
    }

    /** True if the player is currently in the vanilla ban list. */
    public static boolean isBanned(UUID uuid, String name) {
        MinecraftServer srv = server;
        if (srv == null) return false;
        return srv.getPlayerManager().getUserBanList().get(new PlayerConfigEntry(uuid, name)) != null;
    }

    /** Escalation ladder: prior bans in window → hours. Pure, unit-tested. */
    public static int banHours(int priorBansInWindow, List<Integer> steps) {
        if (steps == null || steps.isEmpty()) return 1;
        int idx = Math.min(Math.max(0, priorBansInWindow), steps.size() - 1);
        Integer h = steps.get(idx);
        return h == null || h < 1 ? 1 : h;
    }

    public static String formatDuration(int hours) {
        if (hours % 168 == 0) return (hours / 168) + " нед.";
        if (hours % 24 == 0) return (hours / 24) + " дн.";
        return hours + " ч.";
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static synchronized void load() {
        BAN_HISTORY.clear();
        if (file == null || !Files.exists(file)) return;
        try {
            Type type = new TypeToken<Map<String, List<Long>>>() {}.getType();
            Map<String, List<Long>> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (loaded != null) {
                loaded.forEach((k, v) -> {
                    try {
                        if (k != null && v != null) BAN_HISTORY.put(UUID.fromString(k), new ArrayDeque<>(v));
                    } catch (IllegalArgumentException ignored) {
                    }
                });
            }
        } catch (Exception e) {
            BastionAC.LOGGER.error("Failed to read bans.json", e);
        }
    }

    /**
     * Snapshots the history on the calling (server) thread and hands the write
     * to the I/O thread. Serialising and writing inline stalled the tick that
     * issued the ban; the snapshot is a plain copy, so nothing can change under
     * the serialiser afterwards.
     */
    private static synchronized void save() {
        if (file == null) return;
        Map<String, List<Long>> out = new java.util.HashMap<>();
        BAN_HISTORY.forEach((k, v) -> {
            synchronized (v) {
                if (!v.isEmpty()) out.put(k.toString(), new ArrayList<>(v));
            }
        });
        SafeFiles.writeAsync(file, () -> GSON.toJson(out),
                t -> BastionAC.LOGGER.error("Failed to write bans.json", t));
    }

    private BanManager() {}
}
