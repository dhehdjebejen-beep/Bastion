package dev.bastionac.argus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bastionac.argus.features.Features;
import dev.bastionac.argus.features.Stats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What normal looks like — learned from this server's own players.
 *
 * <p>Every finished session that nobody ended up calling a cheater adds its
 * feature values to two pools: the population (the last {@link #POP_CAP}
 * sessions of everybody) and the player's own history (his last
 * {@link #SELF_CAP} sessions). The scorer measures a new value against the
 * median and MAD of the population — robust, so a few cheaters hiding in the
 * pool cannot drag the norm towards themselves — and, for a sudden change of
 * one player's play, against his own history.
 *
 * <p>Until a pool has {@link #MIN_POOL} values, the prior from
 * {@link Features} stands in for it.
 */
public final class Baselines {

    static final int POP_CAP = 400;
    static final int SELF_CAP = 12;
    static final int MIN_POOL = 12;
    static final int MIN_SELF = 5;

    private static final Gson GSON = new GsonBuilder().create();

    /** One value and whose session it came from (so a later "cheater" label can take it back out). */
    static final class Entry {
        String uuid;
        double v;

        Entry(String uuid, double v) {
            this.uuid = uuid;
            this.v = v;
        }
    }

    private final Map<String, Deque<Entry>> pop = new HashMap<>();
    private final Map<String, Map<String, Deque<Double>>> self = new HashMap<>();
    private int sessions;

    /** Where a value is compared to: the reference, its scale and what they came from. */
    public record Ref(double ref, double scale, int n, String source) {}

    public synchronized void addSession(String uuid, Map<String, Double> values) {
        if (values.isEmpty()) return;
        sessions++;
        values.forEach((k, v) -> {
            if (!Double.isFinite(v)) return;
            Deque<Entry> d = pop.computeIfAbsent(k, x -> new ArrayDeque<>());
            d.addLast(new Entry(uuid, v));
            while (d.size() > POP_CAP) d.pollFirst();
            Deque<Double> s = self.computeIfAbsent(uuid, x -> new HashMap<>()).computeIfAbsent(k, x -> new ArrayDeque<>());
            s.addLast(v);
            while (s.size() > SELF_CAP) s.pollFirst();
        });
    }

    /** A player was labelled a cheater: his sessions leave the population. */
    public synchronized void forget(String uuid) {
        for (Deque<Entry> d : pop.values()) d.removeIf(e -> e.uuid.equals(uuid));
        self.remove(uuid);
    }

    public synchronized Ref population(Features.Spec spec) {
        Deque<Entry> d = pop.get(spec.key());
        if (d == null || d.size() < MIN_POOL) return new Ref(spec.priorRef(), spec.priorScale(), 0, "норма по умолчанию");
        double[] v = new double[d.size()];
        int i = 0;
        for (Entry e : d) v[i++] = e.v;
        double med = Stats.median(v);
        double scale = Math.max(1.4826 * Stats.mad(v), spec.minScale());
        return new Ref(med, scale, v.length, "норма сервера (" + v.length + " сессий)");
    }

    /** The player's own norm, or null with too little history. */
    public synchronized Ref self(String uuid, Features.Spec spec) {
        Map<String, Deque<Double>> m = self.get(uuid);
        Deque<Double> d = m == null ? null : m.get(spec.key());
        if (d == null || d.size() < MIN_SELF) return null;
        double[] v = d.stream().mapToDouble(Double::doubleValue).toArray();
        return new Ref(Stats.median(v), Math.max(1.4826 * Stats.mad(v), spec.minScale()), v.length, "своя норма");
    }

    public synchronized int sessions() {
        return sessions;
    }

    /** How many values the population pool holds for this feature. */
    public synchronized int poolSize(String key) {
        Deque<Entry> d = pop.get(key);
        return d == null ? 0 : d.size();
    }

    // ---------------------------------------------------------------- persistence

    private static final class Stored {
        int sessions;
        Map<String, List<Entry>> pop = new HashMap<>();
        Map<String, Map<String, List<Double>>> self = new HashMap<>();
    }

    public synchronized String toJson() {
        Stored s = new Stored();
        s.sessions = sessions;
        pop.forEach((k, v) -> s.pop.put(k, new ArrayList<>(v)));
        self.forEach((u, m) -> {
            Map<String, List<Double>> mm = new HashMap<>();
            m.forEach((k, v) -> mm.put(k, new ArrayList<>(v)));
            s.self.put(u, mm);
        });
        return GSON.toJson(s);
    }

    public static Baselines load(Path file) {
        Baselines b = new Baselines();
        try {
            if (file != null && Files.exists(file)) {
                Stored s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
                if (s != null) {
                    b.sessions = s.sessions;
                    if (s.pop != null) s.pop.forEach((k, v) -> b.pop.put(k, new ArrayDeque<>(v)));
                    if (s.self != null) {
                        s.self.forEach((u, m) -> {
                            Map<String, Deque<Double>> mm = new HashMap<>();
                            m.forEach((k, v) -> mm.put(k, new ArrayDeque<>(v)));
                            b.self.put(u, mm);
                        });
                    }
                }
            }
        } catch (Exception e) {
            dev.bastionac.BastionAC.LOGGER.warn("Argus: baselines unreadable, starting fresh: {}", e.toString());
        }
        return b;
    }
}
