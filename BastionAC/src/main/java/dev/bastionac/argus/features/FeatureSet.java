package dev.bastionac.argus.features;

import dev.bastionac.telemetry.Prim;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The raw material of the features: counts and samples, accumulated over one
 * or more sessions, turned into values only at the end. Keeping counts (not
 * rates) is what lets two sessions of one evening be judged as one window.
 */
public final class FeatureSet {

    public static final class Acc {
        public double num, den;
        public final Prim.Doubles samples = new Prim.Doubles();
        /** When the feature's suspicious events happened (epoch ms; the first {@link #MOMENTS_KEPT}). */
        public final Prim.Longs moments = new Prim.Longs();
        /** How many there were in all. */
        public int momentTotal;
    }

    /** Moments kept per feature: plenty for "when", bounded for a long session. */
    public static final int MOMENTS_KEPT = 4000;

    /** A suspicious event of this feature happened at {@code t} (3.4: the staff see when). */
    public void moment(String key, long t) {
        Acc a = acc(key);
        a.momentTotal++;
        if (a.moments.n < MOMENTS_KEPT) a.moments.add(t);
    }

    /**
     * A finished feature value and how much data stands behind it. For a
     * per-hour feature {@code events} is the count itself — a handful of
     * impossible episodes in ten minutes says more than the ten minutes do.
     */
    public record Value(double value, double support, double events) {
        public Value(double value, double support) {
            this(value, support, Double.NaN);
        }
    }

    /**
     * A per-hour rate is never computed over less than this many hours: one
     * honest hiccup in the first three minutes must not read as twenty an
     * hour (3.3).
     */
    public static final double PER_HOUR_FLOOR_H = 0.25;

    private final Map<String, Acc> accs = new LinkedHashMap<>();

    public Acc acc(String key) {
        return accs.computeIfAbsent(key, k -> new Acc());
    }

    public void rate(String key, double hits, double total) {
        Acc a = acc(key);
        a.num += hits;
        a.den += total;
    }

    public void sample(String key, double v) {
        if (Double.isFinite(v)) acc(key).samples.add(v);
    }

    public void perHour(String key, double count, double hours) {
        Acc a = acc(key);
        a.num += count;
        a.den += hours;
    }

    public void flag(String key, boolean on) {
        acc(key).samples.add(on ? 1 : 0);
    }

    /** Observed vs expected counts for a Poisson tail test (Agg.Z). */
    public void poisson(String key, double observed, double expected) {
        Acc a = acc(key);
        a.num += observed;
        a.den += expected;
    }

    public void merge(FeatureSet o) {
        o.accs.forEach((k, v) -> {
            Acc a = acc(k);
            a.num += v.num;
            a.den += v.den;
            for (int i = 0; i < v.samples.n; i++) a.samples.add(v.samples.a[i]);
            for (int i = 0; i < v.moments.n && a.moments.n < MOMENTS_KEPT; i++) a.moments.add(v.moments.a[i]);
            a.momentTotal += v.momentTotal;
        });
    }

    private static double[] negate(double[] s) {
        double[] out = new double[s.length];
        for (int i = 0; i < s.length; i++) out[i] = -s[i];
        return out;
    }

    public boolean has(String key) {
        return accs.containsKey(key);
    }

    public Map<String, Acc> raw() {
        return accs;
    }

    /** The feature's value per its spec, or null when there is nothing to say. */
    public Value value(Features.Spec spec) {
        Acc a = accs.get(spec.key());
        if (a == null) return null;
        double[] s = Arrays.copyOf(a.samples.a, a.samples.n);
        return switch (spec.agg()) {
            case RATE -> a.den > 0 ? new Value(a.num / a.den, a.den) : null;
            case PER_HOUR -> a.den > 0 ? new Value(a.num / Math.max(a.den, PER_HOUR_FLOOR_H), a.den, a.num) : null;
            case MEDIAN -> s.length == 0 ? null : new Value(Stats.percentile(s, 0.5), s.length);
            case P95 -> s.length == 0 ? null : new Value(Stats.percentile(s, 0.95), s.length);
            case CV -> s.length < 3 ? null : new Value(Stats.cv(s), s.length);
            case LOG_CV -> s.length < 3 ? null : new Value(Math.log(Math.max(1e-3, Stats.cv(s))), s.length);
            case KURTOSIS -> s.length < 4 ? null : new Value(Stats.excessKurtosis(s), s.length);
            case MAX -> s.length == 0 ? null : new Value(Stats.max(s), s.length);
            case MIN -> s.length == 0 ? null : new Value(-Stats.max(negate(s)), s.length);
            case FLAG -> s.length == 0 ? null : new Value(Stats.max(s), 1);
            case Z -> a.den <= 0 ? null : new Value(Stats.poissonUpperZ(a.num, a.den), a.den);
        };
    }
}
