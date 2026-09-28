package dev.bastionac.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bastionac.BastionAC;
import dev.bastionac.util.SafeFiles;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent, capped history of anti-cheat events — alerts and punishments —
 * so staff can review what a player tripped and what they were punished for.
 * Stored as {@code config/bastionac/history.json}; bounded by count and age so
 * the file never grows without limit.
 */
public final class HistoryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final int MAX_ALERTS = 4000;
    private static final int MAX_PUNISH = 2000;
    private static final long RETENTION_MS = 30L * 24 * 3_600_000L; // 30 days

    public enum Kind { ALERT, KICK, TEMPBAN, MANUAL_BAN, UNBAN, VL_CLEAR, EDR_CHAIN_BAN, NET_LINK }

    /** One recorded event. Public fields for straightforward Gson (de)serialisation. */
    public static final class Event {
        public long time;
        public String kind;
        public String uuid;
        public String name;
        public String check;        // may be null
        public double vl;           // alerts only
        public int durationHours;   // bans only
        public String details;      // free text: reason / admin / notes
        /** Optional server-side context captured at alert time for staff review. */
        public Map<String, String> evidence;
    }

    private static final Deque<Event> ALERTS = new ArrayDeque<>();
    private static final Deque<Event> PUNISH = new ArrayDeque<>();

    private static volatile Path file;
    private static volatile boolean dirty;

    private static final class Store {
        List<Event> alerts = new ArrayList<>();
        List<Event> punishments = new ArrayList<>();
    }

    public static synchronized void init(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        file = configDir.resolve("history.json");
        load();
    }

    // ------------------------------------------------------------------ recording

    public static void recordAlert(ServerPlayerEntity player, CheckType type, double vl, String details) {
        recordAlert(player, type, vl, details, Map.of());
    }

    /** Records an alert together with a compact, server-authoritative evidence snapshot. */
    public static void recordAlert(ServerPlayerEntity player, CheckType type, double vl, String details,
                                   Map<String, String> evidence) {
        Event e = base(Kind.ALERT, player.getUuid(), player.getGameProfile().name());
        e.check = type.key;
        e.vl = vl;
        e.details = details;
        e.evidence = evidence == null || evidence.isEmpty() ? null : new LinkedHashMap<>(evidence);
        add(ALERTS, e, MAX_ALERTS);
        dirty = true; // flushed on tick / shutdown (alerts can be frequent)
    }

    public static void recordPunishment(Kind kind, UUID uuid, String name, String check, int hours, String details) {
        Event e = base(kind, uuid, name);
        e.check = check;
        e.durationHours = hours;
        e.details = details;
        add(PUNISH, e, MAX_PUNISH);
        save(); // punishments are rare and important — persist immediately
    }

    private static Event base(Kind kind, UUID uuid, String name) {
        Event e = new Event();
        e.time = System.currentTimeMillis();
        e.kind = kind.name();
        e.uuid = uuid.toString();
        e.name = name;
        return e;
    }

    private static synchronized void add(Deque<Event> deque, Event e, int max) {
        deque.addLast(e);
        while (deque.size() > max) deque.pollFirst();
    }

    // ------------------------------------------------------------------ queries

    public static synchronized List<Event> recentAlerts(int limit) {
        return newestFirst(ALERTS, limit);
    }

    public static synchronized List<Event> recentPunishments(int limit) {
        return newestFirst(PUNISH, limit);
    }

    public static synchronized List<Event> alertsFor(UUID uuid, int limit) {
        return filtered(ALERTS, uuid, limit);
    }

    public static synchronized List<Event> punishmentsFor(UUID uuid, int limit) {
        return filtered(PUNISH, uuid, limit);
    }

    /** Total alerts recorded for a player (across the retained window). */
    public static synchronized int alertCountFor(UUID uuid) {
        String id = uuid.toString();
        int n = 0;
        for (Event e : ALERTS) if (id.equals(e.uuid)) n++;
        return n;
    }

    private static List<Event> newestFirst(Deque<Event> deque, int limit) {
        List<Event> out = new ArrayList<>(deque);
        java.util.Collections.reverse(out);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static List<Event> filtered(Deque<Event> deque, UUID uuid, int limit) {
        String id = uuid.toString();
        List<Event> out = new ArrayList<>();
        Event[] arr = deque.toArray(new Event[0]);
        for (int i = arr.length - 1; i >= 0 && out.size() < limit; i--) {
            if (id.equals(arr[i].uuid)) out.add(arr[i]);
        }
        return out;
    }

    // ------------------------------------------------------------------ persistence

    /** Called from the server tick: flushes at most every ~10s when something changed. */
    public static void tick(long serverTick) {
        if (dirty && serverTick % 200 == 0) {
            save();
        }
    }

    private static synchronized void load() {
        ALERTS.clear();
        PUNISH.clear();
        if (file == null || !Files.exists(file)) return;
        try {
            Store store = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Store.class);
            if (store != null) {
                long cutoff = System.currentTimeMillis() - RETENTION_MS;
                if (store.alerts != null) store.alerts.forEach(e -> { if (e != null && e.time >= cutoff) add(ALERTS, e, MAX_ALERTS); });
                if (store.punishments != null) store.punishments.forEach(e -> { if (e != null && e.time >= cutoff) add(PUNISH, e, MAX_PUNISH); });
            }
            BastionAC.LOGGER.info("BastionAC history: {} alert(s), {} punishment(s)", ALERTS.size(), PUNISH.size());
        } catch (Exception e) {
            BastionAC.LOGGER.error("Failed to read history.json", e);
        }
    }

    /**
     * Snapshot on the server thread, serialise + write on the I/O thread. With
     * up to 6000 retained events the inline Gson pass plus the disk write was
     * the single longest thing this mod did on a tick.
     *
     * <p>The snapshot holds the same {@link Event} objects as the live deques,
     * which is safe because an event is filled in once at creation and never
     * touched again.
     */
    public static synchronized void save() {
        Store store = snapshot();
        if (store == null) return;
        SafeFiles.writeAsync(file, () -> GSON.toJson(store), t -> {
            dirty = true; // failed — let the next tick try again
            BastionAC.LOGGER.error("Failed to write history.json", t);
        });
    }

    /** Blocking variant — used on shutdown, where the data must hit the disk now. */
    public static synchronized void saveNow() {
        Store store = snapshot();
        if (store == null) return;
        try {
            SafeFiles.write(file, GSON.toJson(store));
        } catch (IOException e) {
            BastionAC.LOGGER.error("Failed to write history.json", e);
        }
    }

    private static Store snapshot() {
        if (file == null) return null;
        Store store = new Store();
        store.alerts = new ArrayList<>(ALERTS);
        store.punishments = new ArrayList<>(PUNISH);
        dirty = false;
        return store;
    }

    private HistoryManager() {}
}
