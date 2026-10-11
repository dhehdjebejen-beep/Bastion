package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;
import dev.bastionac.argus.features.Stats;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Features → evidence → one calibrated score.
 *
 * <pre>
 *   z         = direction · (value − reference) / scale          robust: median and MAD
 *   evidence  = weight · support/(support + half) · clamp((z − 3)/2, 0, 4)
 *   category  = 1 − exp(−Σ evidence / 2)                          for the panel
 *   score     = sigmoid(bias + Σ β·evidence + β_sig·signals)      see Calibration
 * </pre>
 *
 * A value within three robust σ of normal is worth nothing at all: most of
 * what a session does is ordinary, and ordinary must add up to zero, not to
 * a slow drift towards a ban. Evidence also grows with how much data stands
 * behind it, so twelve attacks never weigh as much as two hundred.
 */
public final class Scorer {

    public record Row(Features.Spec spec, double value, double support, double ref, double scale, double z,
                      double evidence, String source) {}

    public record Result(List<Row> rows, Map<Features.Category, Double> categories, double evidence,
                         double signalEvidence, double score, int strong, boolean hardHit, boolean enoughData) {

        /** Pre-weighting evidence per feature key — what the calibration learns from. */
        public Map<String, Double> evidenceByKey() {
            Map<String, Double> m = new LinkedHashMap<>();
            for (Row r : rows) if (r.evidence() > 0) m.put(r.spec().key(), r.evidence());
            return m;
        }
    }

    private Scorer() {}

    public static Result score(FeatureSet fs, Baselines base, String uuid, Calibration cal, double signalEvidence) {
        List<Row> rows = new ArrayList<>();
        Map<Features.Category, Double> sums = new EnumMap<>(Features.Category.class);
        boolean enough = false;
        int strong = 0;
        boolean hard = false;
        for (Features.Spec spec : Features.ALL.values()) {
            FeatureSet.Value v = fs.value(spec);
            if (v == null || !Double.isFinite(v.value()) || v.support() < spec.minSupport()) continue;
            enough = true;
            double ref, scale, z;
            String source;
            if (spec.agg() == Features.Agg.FLAG) {
                ref = 0;
                scale = 1;
                z = v.value() > 0 ? 9 : 0;
                source = "факт";
            } else if (spec.agg() == Features.Agg.Z) {
                ref = 0;
                scale = 1;
                z = v.value();
                source = "против случайного копания";
            } else {
                Baselines.Ref r = base == null
                        ? new Baselines.Ref(spec.priorRef(), spec.priorScale(), 0, "норма по умолчанию")
                        : base.population(spec);
                ref = r.ref();
                scale = r.scale();
                z = spec.direction() * (v.value() - ref) / scale;
                source = r.source();
            }
            double conf = v.support() / (v.support() + spec.halfSupport());
            if (spec.agg() == Features.Agg.FLAG) conf = 1;
            double cap = spec.hard() ? 5 : 4;
            double e = spec.weight() * conf * clamp((z - 3) / 2, 0, cap);
            // A sudden change of this player's own play (only on top of an unusual value).
            if (base != null && e > 0 && spec.agg() != Features.Agg.FLAG && spec.agg() != Features.Agg.Z) {
                Baselines.Ref own = base.self(uuid, spec);
                if (own != null) {
                    double zs = spec.direction() * (v.value() - own.ref()) / own.scale();
                    double bonus = 0.5 * spec.weight() * conf * clamp((zs - 4) / 2, 0, 2);
                    if (bonus > 0) {
                        e += bonus;
                        source += " + резко не как обычно у игрока";
                    }
                }
            }
            rows.add(new Row(spec, v.value(), v.support(), ref, scale, z, e, source));
            sums.merge(spec.cat(), e, Double::sum);
            if (e >= 1.0) strong++;
            if (spec.hard() && e >= 2.5) hard = true;
        }
        rows.sort((a, b) -> Double.compare(b.evidence(), a.evidence()));
        Map<Features.Category, Double> cats = new EnumMap<>(Features.Category.class);
        sums.forEach((k, s) -> cats.put(k, 1 - Math.exp(-s / 2)));
        double sig = Math.min(2.5, Math.max(0, signalEvidence));
        Map<String, Double> byKey = new LinkedHashMap<>();
        double total = 0;
        for (Row r : rows) {
            if (r.evidence() <= 0) continue;
            byKey.put(r.spec().key(), r.evidence());
            total += r.evidence() * (cal == null ? 1 : cal.weight(r.spec().key()));
        }
        double score = cal == null ? Stats.sigmoid(Calibration.DEFAULT_BIAS + total + sig) : cal.score(byKey, sig);
        return new Result(rows, cats, total, sig, score, strong, hard, enough);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
