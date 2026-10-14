package dev.bastionac.argus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bastionac.argus.features.Stats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The model: the owner's verdicts on top of a prior trained in Argus Lab.
 *
 * <pre>
 *   score = sigmoid( bias + Σ β_f · evidence_f + β_sig · signals )
 * </pre>
 *
 * Out of the box the weights are the shipped prior ({@code bastionac/argus-prior.json},
 * fitted on thousands of synthetic honest and cheating sessions run through
 * the live feature code — see {@code ArgusLab}); without it every β is 1 and
 * the bias −3. Each case the owner labels "cheater" or "not a cheater" is a
 * training example; a logistic regression refits the weights, pulled hard
 * towards the prior (L2) so a handful of labels nudges the model instead of
 * overturning it. A feature that keeps firing on players the owner cleared
 * loses weight; one that only fires on confirmed cheaters gains it.
 *
 * <p>Two things no training may change: the bias stays at or below
 * {@link #MAX_BIAS} — a session with no evidence at all is never suspicious,
 * whatever the labels say — and a labelled cheater with no evidence teaches
 * nothing (the features did not see what the owner saw), so it is counted
 * but not fitted on (3.3).
 *
 * <p>The highest score any cleared player ever got is remembered: the
 * automatic ban never fires below it.
 */
public final class Calibration {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final double DEFAULT_BIAS = -3.0;
    /** sigmoid(−2.5) ≈ 0.076: what "nothing found" can at most be worth. */
    public static final double MAX_BIAS = -2.5;
    public static final double MIN_BIAS = -6.0;
    public static final double MIN_WEIGHT = 0.25, MAX_WEIGHT = 3.0;
    public static final String SIG = "sig";

    public double bias = Prior.get().bias();
    public Map<String, Double> weights = new HashMap<>();
    public int cheaters, clean;
    /** The highest score a player labelled "not a cheater" got under the current weights. */
    public double maxCleanScore;
    /** Labelled cheaters the current weights would still call clean (below the suspect line). */
    public int missedCheaters;
    public long fittedAt;
    /** The prior this model was fitted on top of; another one shipped → refit (3.3). */
    public String priorVersion = Prior.get().version();

    /** One labelled case: evidence per feature before weighting, the signals' evidence, and the owner's call. */
    public record Sample(Map<String, Double> evidence, double signals, boolean cheater, double weight) {
        public Sample(Map<String, Double> evidence, double signals, boolean cheater) {
            this(evidence, signals, cheater, 1.0);
        }

        boolean informative() {
            if (signals > 0) return true;
            for (double v : evidence.values()) if (v > 0) return true;
            return false;
        }
    }

    /**
     * The shipped starting point: a bias and a weight per feature. Weights it
     * does not name are 1.
     */
    public record Prior(String version, double bias, Map<String, Double> weights) {

        private static volatile Prior shipped;

        static final Prior NEUTRAL = new Prior("neutral", DEFAULT_BIAS, Map.of());

        public double weight(String key) {
            Double w = weights.get(key);
            return w == null ? 1.0 : w;
        }

        public static Prior get() {
            Prior p = shipped;
            if (p == null) shipped = p = load();
            return p;
        }

        /** For tests: run with this prior instead of the shipped one (null: back to the shipped). */
        static void override(Prior p) {
            shipped = p;
        }

        private static Prior load() {
            try (java.io.InputStream in = Calibration.class.getResourceAsStream("/bastionac/argus-prior.json")) {
                if (in == null) return NEUTRAL;
                Stored st = new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Stored.class);
                if (st == null || st.weights == null || !Double.isFinite(st.bias)) return NEUTRAL;
                Map<String, Double> w = new HashMap<>();
                st.weights.forEach((k, v) -> {
                    if (v != null && Double.isFinite(v)) w.put(k, Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, v)));
                });
                return new Prior(st.version == null ? "?" : st.version,
                        Math.max(MIN_BIAS, Math.min(MAX_BIAS, st.bias)), Map.copyOf(w));
            } catch (Exception e) {
                return NEUTRAL;
            }
        }

        static final class Stored {
            String version;
            double bias = DEFAULT_BIAS;
            Map<String, Double> weights;
        }
    }

    public synchronized double weight(String key) {
        Double w = weights.get(key);
        return w == null ? Prior.get().weight(key) : w;
    }

    public synchronized double logit(Map<String, Double> evidence, double signals) {
        double z = bias + weight(SIG) * signals;
        for (Map.Entry<String, Double> e : evidence.entrySet()) z += weight(e.getKey()) * e.getValue();
        return z;
    }

    public double score(Map<String, Double> evidence, double signals) {
        return Stats.sigmoid(logit(evidence, signals));
    }

    /** Refits on every labelled case (UNSURE ones are left out by the caller), on top of the shipped prior. */
    public synchronized void fit(List<Sample> samples, double suspectLine) {
        Prior prior = Prior.get();
        priorVersion = prior.version();
        cheaters = 0;
        clean = 0;
        for (Sample s : samples) {
            if (s.cheater()) cheaters++;
            else clean++;
        }
        fittedAt = System.currentTimeMillis();
        weights.clear();
        bias = prior.bias();
        maxCleanScore = 0;
        missedCheaters = 0;
        if (samples.isEmpty()) return;
        Fit f = optimise(samples, prior, 2.0, 2000);
        bias = f.bias;
        weights.putAll(f.weights);
        for (Sample s : samples) {
            double p = Stats.sigmoid(logit(s.evidence(), s.signals()));
            if (!s.cheater()) maxCleanScore = Math.max(maxCleanScore, p);
            else if (p < suspectLine) missedCheaters++;
        }
    }

    /** A fitted bias and weights. */
    record Fit(double bias, Map<String, Double> weights) {}

    /**
     * L2-regularised logistic regression by full-batch gradient descent:
     * maximise Σ w·log-likelihood − λ·Σ(β − β_prior)² − λ/4·(b − b_prior)².
     * Cheaters with no evidence are left out (they can only drag the bias
     * up); the bias is kept in [{@link #MIN_BIAS}, {@link #MAX_BIAS}], the
     * weights in [{@link #MIN_WEIGHT}, {@link #MAX_WEIGHT}].
     */
    static Fit optimise(List<Sample> all, Prior prior, double lambda, int iterations) {
        return optimise(all, prior, lambda, iterations, Map.of(), MAX_BIAS);
    }

    /**
     * As above, with a ceiling per weight ({@code caps}; keys it does not name:
     * {@link #MAX_WEIGHT}) and a ceiling for the bias.
     */
    static Fit optimise(List<Sample> all, Prior prior, double lambda, int iterations, Map<String, Double> caps,
                        double maxBias) {
        List<Sample> samples = new java.util.ArrayList<>();
        for (Sample s : all) if (!s.cheater() || s.informative()) samples.add(s);
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (Sample s : samples) keys.addAll(s.evidence().keySet());
        keys.add(SIG);
        Map<String, Double> beta = new HashMap<>();
        for (String k : keys) {
            double cap = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, caps.getOrDefault(k, MAX_WEIGHT)));
            beta.put(k, Math.min(cap, prior.weight(k)));
        }
        double b = Math.min(prior.bias(), Math.min(MAX_BIAS, maxBias));
        double total = 0;
        for (Sample s : samples) total += s.weight();
        if (total <= 0) return new Fit(b, beta);
        final double lr = 0.05;
        for (int it = 0; it < iterations; it++) {
            double gb = -2 * lambda * 0.25 * (b - prior.bias());
            Map<String, Double> g = new HashMap<>();
            for (String k : keys) g.put(k, -2 * lambda * (beta.get(k) - prior.weight(k)));
            for (Sample s : samples) {
                double z = b + beta.get(SIG) * s.signals();
                for (Map.Entry<String, Double> e : s.evidence().entrySet()) z += beta.get(e.getKey()) * e.getValue();
                double err = s.weight() * ((s.cheater() ? 1 : 0) - Stats.sigmoid(z));
                gb += err;
                g.merge(SIG, err * s.signals(), Double::sum);
                for (Map.Entry<String, Double> e : s.evidence().entrySet()) g.merge(e.getKey(), err * e.getValue(), Double::sum);
            }
            b = Math.max(MIN_BIAS, Math.min(Math.min(MAX_BIAS, maxBias), b + lr * gb / total));
            for (String k : keys) {
                double cap = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, caps.getOrDefault(k, MAX_WEIGHT)));
                beta.put(k, Math.max(MIN_WEIGHT, Math.min(cap, beta.get(k) + lr * g.get(k) / total)));
            }
        }
        return new Fit(b, beta);
    }

    /** Whether this model was fitted on top of the prior that ships now. */
    public synchronized boolean onCurrentPrior() {
        return java.util.Objects.equals(priorVersion, Prior.get().version());
    }

    public synchronized String toJson() {
        return GSON.toJson(this);
    }

    public static Calibration load(Path file) {
        try {
            if (file != null && Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Calibration c = GSON.fromJson(json, Calibration.class);
                if (c != null) {
                    if (c.weights == null) c.weights = new HashMap<>();
                    // A model saved before 3.3 was fitted towards all-ones, not the prior.
                    com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
                    if (!root.isJsonObject() || !root.getAsJsonObject().has("priorVersion")) c.priorVersion = null;
                    return c;
                }
            }
        } catch (Exception e) {
            dev.bastionac.BastionAC.LOGGER.warn("Argus: model unreadable, using defaults: {}", e.toString());
        }
        return new Calibration();
    }
}
