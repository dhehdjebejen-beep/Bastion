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
 * The owner's verdicts, turned into the model's weights.
 *
 * <pre>
 *   score = sigmoid( bias + Σ β_f · evidence_f + β_sig · signals )
 * </pre>
 *
 * Out of the box every β is 1 and the bias −3: three units of evidence make
 * an even chance, six make 95 %. Each case the owner labels "cheater" or
 * "not a cheater" is a training example; a logistic regression refits the
 * weights, pulled hard towards 1 (L2 prior) so a handful of labels nudges the
 * model instead of overturning it. A feature that keeps firing on players the
 * owner cleared loses weight; one that only fires on confirmed cheaters
 * gains it.
 *
 * <p>The highest score any cleared player ever got is remembered: the
 * automatic ban never fires below it.
 */
public final class Calibration {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final double DEFAULT_BIAS = -3.0;
    public static final String SIG = "sig";

    public double bias = DEFAULT_BIAS;
    public Map<String, Double> weights = new HashMap<>();
    public int cheaters, clean;
    /** The highest score a player labelled "not a cheater" got under the current weights. */
    public double maxCleanScore;
    /** Labelled cheaters the current weights would still call clean (below the suspect line). */
    public int missedCheaters;
    public long fittedAt;

    /** One labelled case: evidence per feature before weighting, the signals' evidence, and the owner's call. */
    public record Sample(Map<String, Double> evidence, double signals, boolean cheater) {}

    public synchronized double weight(String key) {
        Double w = weights.get(key);
        return w == null ? 1.0 : w;
    }

    public synchronized double logit(Map<String, Double> evidence, double signals) {
        double z = bias + weight(SIG) * signals;
        for (Map.Entry<String, Double> e : evidence.entrySet()) z += weight(e.getKey()) * e.getValue();
        return z;
    }

    public double score(Map<String, Double> evidence, double signals) {
        return Stats.sigmoid(logit(evidence, signals));
    }

    /** Refits on every labelled case (UNSURE ones are left out by the caller). */
    public synchronized void fit(List<Sample> samples, double suspectLine) {
        cheaters = 0;
        clean = 0;
        for (Sample s : samples) {
            if (s.cheater()) cheaters++;
            else clean++;
        }
        fittedAt = System.currentTimeMillis();
        if (samples.isEmpty()) {
            bias = DEFAULT_BIAS;
            weights.clear();
            maxCleanScore = 0;
            missedCheaters = 0;
            return;
        }
        // Parameters: bias + one β per key seen.
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (Sample s : samples) keys.addAll(s.evidence().keySet());
        keys.add(SIG);
        Map<String, Double> beta = new HashMap<>();
        for (String k : keys) beta.put(k, weight(k));
        double b = bias;
        final double lambda = 2.0, lr = 0.05;
        for (int it = 0; it < 2000; it++) {
            double gb = -2 * lambda * 0.25 * (b - DEFAULT_BIAS);
            Map<String, Double> g = new HashMap<>();
            for (String k : keys) g.put(k, -2 * lambda * (beta.get(k) - 1.0));
            for (Sample s : samples) {
                double z = b + beta.get(SIG) * s.signals();
                for (Map.Entry<String, Double> e : s.evidence().entrySet()) z += beta.get(e.getKey()) * e.getValue();
                double p = Stats.sigmoid(z);
                double err = (s.cheater() ? 1 : 0) - p;
                gb += err;
                g.merge(SIG, err * s.signals(), Double::sum);
                for (Map.Entry<String, Double> e : s.evidence().entrySet()) g.merge(e.getKey(), err * e.getValue(), Double::sum);
            }
            b += lr * gb / samples.size();
            for (String k : keys) beta.put(k, beta.get(k) + lr * g.get(k) / samples.size());
        }
        bias = Math.max(-6, Math.min(-1, b));
        weights.clear();
        for (String k : keys) weights.put(k, Math.max(0.25, Math.min(3.0, beta.get(k))));
        maxCleanScore = 0;
        missedCheaters = 0;
        for (Sample s : samples) {
            double p = Stats.sigmoid(logit(s.evidence(), s.signals()));
            if (!s.cheater()) maxCleanScore = Math.max(maxCleanScore, p);
            else if (p < suspectLine) missedCheaters++;
        }
    }

    public synchronized String toJson() {
        return GSON.toJson(this);
    }

    public static Calibration load(Path file) {
        try {
            if (file != null && Files.exists(file)) {
                Calibration c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Calibration.class);
                if (c != null) {
                    if (c.weights == null) c.weights = new HashMap<>();
                    return c;
                }
            }
        } catch (Exception e) {
            dev.bastionac.BastionAC.LOGGER.warn("Argus: model unreadable, using defaults: {}", e.toString());
        }
        return new Calibration();
    }
}
