package dev.bastionac.argus;

import dev.bastionac.argus.LabSimulator.Cheat;
import dev.bastionac.argus.LabSimulator.Profile;
import dev.bastionac.argus.features.ClientFeatures;
import dev.bastionac.argus.features.CombatFeatures;
import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.MiningFeatures;
import dev.bastionac.argus.features.MovementFeatures;
import dev.bastionac.argus.features.PacketFeatures;
import dev.bastionac.config.ACConfig;
import dev.bastionac.telemetry.SessionData;
import dev.bastionac.telemetry.TelemetryReader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Argus Lab: a population of synthetic players, honest and cheating, played
 * through {@link LabSimulator}, measured by the live feature code, scored by
 * the live scorer — the bench Argus is measured and trained on.
 *
 * <ul>
 *   <li>{@link #corpus} plays the sessions (in parallel) and keeps each one's
 *       feature set;</li>
 *   <li>{@link #benchmark} scores a held-out set against baselines learned
 *       from other honest sessions and reports detection per cheat and
 *       intensity, and false alarms on honest players;</li>
 *   <li>{@link #train} fits the calibration on a separate labelled split —
 *       the prior Argus ships with.</li>
 * </ul>
 *
 * Run {@code main} for the full corpus and report; the unit test runs a
 * small one.
 */
final class ArgusLab {

    static final ACConfig.ArgusCfg CFG = new ACConfig.ArgusCfg();

    /** One played and measured session. */
    record Played(String scenario, Cheat cheat, double level, boolean cheater, String uuid, FeatureSet features) {}

    /** A cheat at one intensity, possibly with other modules on alongside it. */
    record Scenario(Cheat cheat, double level, Map<Cheat, Double> extra) {
        Scenario(Cheat cheat, double level) {
            this(cheat, level, Map.of());
        }

        String name() {
            if (cheat == null) return "честный";
            StringBuilder sb = new StringBuilder(cheat.name()).append(String.format(Locale.ROOT, "@%.2f", level));
            extra.keySet().forEach(c -> sb.append('+').append(c.name()));
            return sb.toString();
        }
    }

    /** An honest player drawn from a wide population — the hard part is not to flag these. */
    static Profile honest(long seed) {
        Random r = new Random(seed * 7919 + 17);
        Profile p = new Profile();
        p.seed = seed;
        p.pingMs = (int) Math.max(5, Math.min(400, Math.exp(Math.log(55) + r.nextGaussian() * 0.75)));
        p.jitterMs = 2 + r.nextDouble() * (p.pingMs > 150 ? 45 : 20);
        p.sensitivity = 0.03 + r.nextDouble() * 0.97;
        p.aimSkill = Math.min(1, Math.max(0, r.nextGaussian() * 0.25 + 0.5));
        p.cps = 4 + r.nextDouble() * 9;
        p.clickSigma = 0.16 + r.nextDouble() * 0.4;
        if (r.nextDouble() < 0.15) {   // a jitter clicker: fast and steady by hand
            p.cps = 10 + r.nextDouble() * 4;
            p.clickSigma = 0.1 + r.nextDouble() * 0.08;
        }
        double style = r.nextDouble();
        if (style < 0.3) {          // a PvP player
            p.fight = 0.6;
            p.dig = 0.05;
        } else if (style < 0.6) {   // a miner
            p.fight = 0.08;
            p.dig = 0.6;
        } else {                    // a bit of everything
            p.fight = 0.3;
            p.dig = 0.3;
        }
        p.minutes = 5 + r.nextInt(10);
        p.reach = 2.35 + r.nextDouble() * 0.55;
        return p;
    }

    /**
     * An honest player on a bad day: far away, on mobile internet, with the
     * game stuttering — out of the distribution the prior was trained on, the
     * place a false alarm would come from.
     */
    static Profile stressed(long seed) {
        Profile p = honest(seed);
        Random r = new Random(seed * 31 + 5);
        p.pingMs = 120 + r.nextInt(300);
        p.jitterMs = 25 + r.nextDouble() * 60;
        p.lagOneIn = 120 + r.nextInt(300);
        if (r.nextBoolean()) {   // and a steady fast clicker at that
            p.cps = 9 + r.nextDouble() * 5;
            p.clickSigma = 0.08 + r.nextDouble() * 0.08;
        }
        p.aimSkill = Math.min(1, p.aimSkill + 0.3);
        return p;
    }

    /** Plays one session and measures it with the live feature code. */
    static Played play(Profile p, Scenario sc, WorldSample rock) {
        Map<Cheat, Double> cheats = new EnumMap<>(Cheat.class);
        if (sc.cheat() != null) {
            cheats.putAll(sc.extra());
            cheats.put(sc.cheat(), sc.level());
            // What a cheat's player does: a fighter cheats in fights, a miner digs.
            if (sc.cheat() == Cheat.XRAY) {
                p.dig = Math.max(p.dig, 0.5);
                p.fight = Math.min(p.fight, 0.15);
            } else if (sc.cheat().ordinal() <= Cheat.AUTOCLICKER.ordinal() || sc.cheat() == Cheat.BLINK) {
                p.fight = Math.max(p.fight, 0.45);
                p.dig = Math.min(p.dig, 0.15);
            }
        }
        LabSimulator sim = new LabSimulator(p, cheats, rock);
        byte[] bytes = sim.play();
        try {
            SessionData s = TelemetryReader.readRaw(bytes);
            FeatureSet fs = new FeatureSet();
            MovementFeatures.analyze(s, fs, raw -> 0.6f);
            PacketFeatures.analyze(s, fs);
            CombatFeatures.analyze(s, fs);
            ClientFeatures.analyze(s, fs);
            MiningFeatures.analyze(s, fs, raw -> raw, rock);
            return new Played(sc.name(), sc.cheat(), sc.level(), sc.cheat() != null, "u" + p.seed, fs);
        } catch (Exception e) {
            throw new IllegalStateException("lab session " + p.seed + " " + sc.name(), e);
        }
    }

    /** Every scenario of the bench: each cheat at three intensities. */
    static List<Scenario> cheatScenarios(double... levels) {
        List<Scenario> out = new ArrayList<>();
        for (Cheat c : Cheat.values()) for (double l : levels) out.add(new Scenario(c, l));
        return out;
    }

    /**
     * The training mix: every cheat at random intensities (from barely-there
     * to blatant), and the bundles real clients switch on together.
     */
    static List<Scenario> trainingScenarios(long seed, int perCheat) {
        Random r = new Random(seed);
        List<Scenario> out = new ArrayList<>();
        for (Cheat c : Cheat.values()) {
            for (int k = 0; k < perCheat; k++) out.add(new Scenario(c, 0.15 + 0.85 * r.nextDouble()));
        }
        for (int k = 0; k < perCheat; k++) {
            out.add(new Scenario(Cheat.AURA_SMOOTH, 0.2 + 0.8 * r.nextDouble(),
                    Map.of(Cheat.REACH, 0.1 + 0.4 * r.nextDouble(), Cheat.AUTOCLICKER, r.nextDouble())));
            out.add(new Scenario(Cheat.SPEED, 0.1 + 0.4 * r.nextDouble(), Map.of(Cheat.TIMER, 0.1 + 0.3 * r.nextDouble())));
            out.add(new Scenario(Cheat.AIM_ASSIST, 0.2 + 0.8 * r.nextDouble(), Map.of(Cheat.REACH, 0.15 + 0.3 * r.nextDouble())));
            out.add(new Scenario(Cheat.XRAY, 0.15 + 0.5 * r.nextDouble(), Map.of(Cheat.BLINK, 0.2 + 0.5 * r.nextDouble())));
        }
        return out;
    }

    /** Plays honest players {@code [from, from+honest)} and {@code perCheat} players per cheat scenario. */
    static List<Played> corpus(long from, int honest, int perCheat, List<Scenario> scenarios, WorldSample rock) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        try {
            List<Future<Played>> jobs = new ArrayList<>();
            for (int i = 0; i < honest; i++) {
                long seed = from + i;
                jobs.add(pool.submit(() -> play(honest(seed), new Scenario(null, 0), rock)));
            }
            long seed = from + 100_000;
            for (Scenario sc : scenarios) {
                for (int k = 0; k < perCheat; k++) {
                    long s = seed++;
                    jobs.add(pool.submit(() -> play(honest(s), sc, rock)));
                }
            }
            List<Played> out = new ArrayList<>();
            for (Future<Played> f : jobs) out.add(f.get());
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    static List<Played> stressCorpus(long from, int n, WorldSample rock) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        try {
            List<Future<Played>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                long seed = from + i;
                jobs.add(pool.submit(() -> play(stressed(seed), new Scenario(null, 0), rock)));
            }
            List<Played> out = new ArrayList<>();
            for (Future<Played> f : jobs) out.add(f.get());
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Real session files ({@code .btl.gz}, e.g. {@code logs/bastionac/telemetry}
     * of a live server), measured like the lab's, each taken as one honest
     * session. Mining is left out (no world to sample) and the movement replay
     * takes every block as ordinary ground.
     */
    static List<Played> real(java.nio.file.Path dir, int limit) throws java.io.IOException {
        List<java.nio.file.Path> files;
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.walk(dir)) {
            files = st.filter(f -> f.getFileName().toString().endsWith(".btl.gz")).sorted().limit(limit).toList();
        }
        List<Played> out = new ArrayList<>();
        for (java.nio.file.Path f : files) {
            try {
                SessionData s = TelemetryReader.read(f);
                if (s.durationMs() < 120_000) continue;
                FeatureSet fs = new FeatureSet();
                MovementFeatures.analyze(s, fs, raw -> 0.6f);
                PacketFeatures.analyze(s, fs);
                CombatFeatures.analyze(s, fs);
                ClientFeatures.analyze(s, fs);
                String who = f.getParent() == null ? f.toString() : f.getParent().getFileName() + "/" + f.getFileName();
                out.add(new Played("реальная", null, 0, false, who, fs));
            } catch (Exception e) {
                System.out.println("skip " + f + ": " + e);
            }
        }
        return out;
    }

    /** Honest sessions as the server's learned norms. */
    static Baselines baselines(List<Played> honest) {
        Baselines b = new Baselines();
        for (Played p : honest) b.addSession(p.uuid(), Analysis.baselineValues(p.features()));
        return b;
    }

    static Scorer.Result score(Played p, Baselines base, Calibration cal) {
        return Scorer.score(p.features(), base, p.uuid() + "-case", cal, 0);
    }

    /** Training samples for the calibration: evidence per feature under the given norms. */
    static List<Calibration.Sample> samples(List<Played> set, Baselines base) {
        List<Calibration.Sample> out = new ArrayList<>();
        for (Played p : set) {
            Scorer.Result r = score(p, base, null);
            out.add(new Calibration.Sample(r.evidenceByKey(), 0, p.cheater()));
        }
        return out;
    }

    /** Detection and false alarms of one model on one held-out set. */
    static final class Bench {
        final Map<String, int[]> byScenario = new TreeMap<>();   // [n, suspect+, guilty]
        final Map<String, double[]> scoreSum = new TreeMap<>();
        int honest, honestSuspect, honestGuilty;
        double honestMax;
        final List<String> worstHonest = new ArrayList<>();
        final Map<String, String> missedWhy = new LinkedHashMap<>();

        double fpRate() {
            return honest == 0 ? 0 : (double) honestSuspect / honest;
        }

        /** Share of cheating sessions at least suspicious. */
        double detection() {
            int n = 0, d = 0;
            for (int[] v : byScenario.values()) {
                n += v[0];
                d += v[1];
            }
            return n == 0 ? 0 : (double) d / n;
        }

        double detection(Cheat c) {
            int n = 0, d = 0;
            for (Map.Entry<String, int[]> e : byScenario.entrySet()) {
                if (!e.getKey().startsWith(c.name() + "@")) continue;
                n += e.getValue()[0];
                d += e.getValue()[1];
            }
            return n == 0 ? 0 : (double) d / n;
        }

        String report(String title) {
            StringBuilder sb = new StringBuilder();
            sb.append("== ").append(title).append(" ==\n");
            sb.append(String.format(Locale.ROOT, "честные: %d, подозрительно %d (%.1f%%), виновен %d, макс. балл %.3f%n",
                    honest, honestSuspect, 100.0 * fpRate(), honestGuilty, honestMax));
            for (String w : worstHonest) sb.append("   ").append(w).append('\n');
            sb.append(String.format(Locale.ROOT, "читеры: обнаружено %.1f%%%n", 100 * detection()));
            for (Map.Entry<String, int[]> e : byScenario.entrySet()) {
                int[] v = e.getValue();
                sb.append(String.format(Locale.ROOT, "  %-22s n=%-3d ≥подозр. %5.1f%%  виновен %5.1f%%  ср.балл %.3f%n",
                        e.getKey(), v[0], 100.0 * v[1] / v[0], 100.0 * v[2] / v[0], scoreSum.get(e.getKey())[0] / v[0]));
            }
            if (!missedWhy.isEmpty()) {
                sb.append("пропуски (пример на сценарий):\n");
                missedWhy.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
            }
            return sb.toString();
        }
    }

    static Bench benchmark(List<Played> test, Baselines base, Calibration cal) {
        Bench b = new Bench();
        List<double[]> honestScores = new ArrayList<>();
        Map<Integer, String> honestWhy = new LinkedHashMap<>();
        for (Played p : test) {
            Scorer.Result r = score(p, base, cal);
            CaseFile.Verdict v = Argus.verdictOf(r, CFG);
            boolean sus = v == CaseFile.Verdict.SUSPECT || v == CaseFile.Verdict.GUILTY;
            if (!p.cheater()) {
                b.honest++;
                if (sus) b.honestSuspect++;
                if (v == CaseFile.Verdict.GUILTY) b.honestGuilty++;
                b.honestMax = Math.max(b.honestMax, r.score());
                honestScores.add(new double[]{r.score(), honestScores.size()});
                honestWhy.put(honestWhy.size(), p.uuid() + " " + v + " " + why(r));
            } else {
                int[] c = b.byScenario.computeIfAbsent(p.scenario(), k -> new int[3]);
                c[0]++;
                if (sus) c[1]++;
                if (v == CaseFile.Verdict.GUILTY) c[2]++;
                b.scoreSum.computeIfAbsent(p.scenario(), k -> new double[1])[0] += r.score();
                if (!sus && !b.missedWhy.containsKey(p.scenario())) b.missedWhy.put(p.scenario(), why(r));
            }
        }
        honestScores.sort((x, y) -> Double.compare(y[0], x[0]));
        for (int i = 0; i < Math.min(3, honestScores.size()); i++) {
            if (honestScores.get(i)[0] < 0.2) break;
            b.worstHonest.add(honestWhy.get((int) honestScores.get(i)[1]));
        }
        return b;
    }

    static String why(Scorer.Result r) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "балл %.3f, сильных %d:", r.score(), r.strong()));
        int n = 0;
        for (Scorer.Row row : r.rows()) {
            if (n++ >= 5) break;
            if (row.evidence() <= 0 && n > 3) break;
            sb.append(String.format(Locale.ROOT, " %s=%s(z%.1f,e%.2f)", row.spec().key(), row.spec().format(row.value()), row.z(), row.evidence()));
        }
        return sb.toString();
    }

    /**
     * The prior: a logistic regression on a labelled split, classes balanced
     * (as many cheating as honest sessions in weight), lightly regularised
     * towards all-ones. Then the false-alarm guard: the bias goes down until
     * the most suspicious honest training session sits below
     * {@link #HONEST_CEILING} — a prior must never be the reason an honest
     * player is reported.
     */
    static Calibration.Prior trainPrior(List<Played> train, Baselines base, String version) {
        return trainPrior(train, List.of(), base, version);
    }

    /**
     * @param alsoHonest sessions that only tighten the honest budget (real
     *                   sessions from a server: mostly honest, unlabelled)
     */
    static Calibration.Prior trainPrior(List<Played> train, List<Played> alsoHonest, Baselines base, String version) {
        return trainBagged(List.of(train), alsoHonest, base, version);
    }

    /**
     * Bagged and in both regimes. Each bag (an independently played training
     * corpus) is fitted on its own; the prior is the mean of their weights, so
     * no weight rests on the luck of one seed. Every session is a training
     * example twice: scored against the server's norms and against the
     * built-in priors (a fresh server without norms) — the model has to work
     * in both.
     */
    static Calibration.Prior trainBagged(List<List<Played>> bags, List<Played> alsoHonest, Baselines base, String version) {
        Baselines cold = new Baselines();
        // The honest budget: whatever a feature once gave an honest player (in
        // any bag, either regime), its weight may turn into at most
        // HONEST_BUDGET of logit — a feature honest play can light up never
        // decides a case on its own.
        List<Calibration.Sample> honestAll = new ArrayList<>();
        for (List<Played> bag : bags) {
            for (Calibration.Sample s : samples(bag, base)) if (!s.cheater()) honestAll.add(s);
            for (Calibration.Sample s : samples(bag, cold)) if (!s.cheater()) honestAll.add(s);
        }
        honestAll.addAll(samples(alsoHonest, base));
        honestAll.addAll(samples(alsoHonest, cold));
        Map<String, Double> caps = new TreeMap<>();
        for (Calibration.Sample s : honestAll) {
            s.evidence().forEach((k, e) -> {
                if (e > 0) caps.merge(k, HONEST_BUDGET / e, Math::min);
            });
        }
        Map<String, Double> sum = new TreeMap<>();
        Map<String, Integer> count = new TreeMap<>();
        double biasSum = 0;
        for (List<Played> bag : bags) {
            List<Calibration.Sample> raw = new ArrayList<>(samples(bag, base));
            raw.addAll(samples(bag, cold));
            int nC = 0, nH = 0;
            for (Calibration.Sample s : raw) {
                if (s.cheater()) nC++;
                else nH++;
            }
            double wC = nC == 0 ? 1 : (double) nH / nC;
            List<Calibration.Sample> weighted = new ArrayList<>();
            for (Calibration.Sample s : raw) {
                weighted.add(new Calibration.Sample(s.evidence(), s.signals(), s.cheater(), s.cheater() ? wC : 1));
            }
            // The prior's bias stays at 3.2's −3: nothing found is worth 4.7 %, as it always was.
            Calibration.Fit f = Calibration.optimise(weighted, Calibration.Prior.NEUTRAL, 0.02, 6000, caps,
                    Calibration.DEFAULT_BIAS);
            biasSum += f.bias();
            f.weights().forEach((k, v) -> {
                sum.merge(k, v, Double::sum);
                count.merge(k, 1, Integer::sum);
            });
        }
        // Mean over the bags; a key a bag never saw counts as its neutral 1.
        // The lab is a model of the game, not the game: no weight it learns
        // may go past LAB_MAX_WEIGHT (staff labels on a live server may, up to 3).
        Map<String, Double> fitted = new TreeMap<>();
        for (Map.Entry<String, Double> e : sum.entrySet()) {
            int missing = bags.size() - count.get(e.getKey());
            double mean = (e.getValue() + missing * 1.0) / bags.size();
            fitted.put(e.getKey(), Math.min(LAB_MAX_WEIGHT, mean));
        }
        double worst = Double.NEGATIVE_INFINITY;
        for (Calibration.Sample s : honestAll) {
            double z = biasSum / bags.size();
            for (Map.Entry<String, Double> e : s.evidence().entrySet()) z += fitted.getOrDefault(e.getKey(), 1.0) * e.getValue();
            worst = Math.max(worst, z);
        }
        double ceiling = Math.log(HONEST_CEILING / (1 - HONEST_CEILING));
        double b = biasSum / bags.size();
        if (worst > ceiling) b -= worst - ceiling;
        b = Math.max(Calibration.MIN_BIAS, Math.min(Calibration.MAX_BIAS, b));
        Map<String, Double> w = new TreeMap<>();
        // Rounded down: rounding must never carry a weight over its honest budget.
        fitted.forEach((k, v) -> w.put(k, Math.floor(v * 1000) / 1000.0));
        return new Calibration.Prior(version, Math.floor(b * 1000) / 1000.0, w);
    }

    /** The highest weight the lab may give a feature. */
    static final double LAB_MAX_WEIGHT = 2.5;

    /** Logit one feature may at most add to any honest training session. */
    static final double HONEST_BUDGET = 1.2;

    /** No honest training session may score above this under the prior. */
    static final double HONEST_CEILING = 0.25;

    /** A model that is exactly the given prior, no labels. */
    static Calibration model(Calibration.Prior p) {
        Calibration.Prior.override(p);
        Calibration c = new Calibration();
        c.bias = p.bias();
        c.weights.putAll(p.weights());
        return c;
    }

    static String priorJson(Calibration.Prior p, String note) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("version", p.version());
        o.addProperty("note", note);
        o.addProperty("bias", p.bias());
        com.google.gson.JsonObject w = new com.google.gson.JsonObject();
        new TreeMap<>(p.weights()).forEach(w::addProperty);
        o.add("weights", w);
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o) + "\n";
    }

    /** Each feature: honest 5/50/95th percentiles and each cheat's median — where separation is possible at all. */
    static String distributions(List<Played> all) {
        StringBuilder sb = new StringBuilder("== распределения признаков ==\n");
        for (dev.bastionac.argus.features.Features.Spec spec : dev.bastionac.argus.features.Features.ALL.values()) {
            List<Double> hon = new ArrayList<>();
            Map<String, List<Double>> by = new TreeMap<>();
            for (Played p : all) {
                FeatureSet.Value v = p.features().value(spec);
                if (v == null || !Double.isFinite(v.value()) || v.support() < spec.minSupport()) continue;
                if (!p.cheater()) hon.add(v.value());
                else by.computeIfAbsent(p.cheat().name(), k -> new ArrayList<>()).add(v.value());
            }
            if (hon.isEmpty()) continue;
            double[] h = hon.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            sb.append(String.format(Locale.ROOT, "%-22s честные p5 %s p50 %s p95 %s max %s |", spec.key(),
                    spec.format(q(h, 0.05)), spec.format(q(h, 0.5)), spec.format(q(h, 0.95)), spec.format(h[h.length - 1])));
            by.forEach((k, v) -> {
                double[] c = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                double med = q(c, 0.5);
                if (spec.direction() * (med - q(h, 0.5)) > 0 && Math.abs(med - q(h, 0.5)) > 0.5 * Math.abs(q(h, 0.95) - q(h, 0.5)) + 1e-9) {
                    sb.append(' ').append(k).append('=').append(spec.format(med));
                }
            });
            sb.append('\n');
        }
        return sb.toString();
    }

    static double q(double[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * (sorted.length - 1)))];
    }

    /**
     * The full bench. Three disjoint populations: honest sessions that become
     * the server's norms; a labelled training set (cheats at random
     * intensities and in bundles); a held-out test set (each cheat at fixed
     * intensities). Args: honest-per-set, cheaters-per-scenario, [--write].
     */
    public static void main(String[] args) throws Exception {
        int honest = args.length > 0 && args[0].matches("\\d+") ? Integer.parseInt(args[0]) : 300;
        int perCheat = args.length > 1 && args[1].matches("\\d+") ? Integer.parseInt(args[1]) : 10;
        List<String> argl = java.util.Arrays.asList(args);
        boolean write = argl.contains("--write");
        int ri = argl.indexOf("--real");
        List<Played> realSessions = ri >= 0 && ri + 1 < args.length ? real(java.nio.file.Path.of(args[ri + 1]), 2000) : List.of();
        if (!realSessions.isEmpty()) System.out.println("real sessions: " + realSessions.size());
        long t0 = System.currentTimeMillis();
        WorldSample rock = LabSimulator.rock(4242);
        List<Played> norms = corpus(1, honest, 0, List.of(), rock);
        int nBags = 3;
        List<List<Played>> bags = new ArrayList<>();
        for (int k = 0; k < nBags; k++) {
            long from = 1_000_000 + k * 10_000_000L;
            List<Played> bag = corpus(from, honest, 1, trainingScenarios(7 + k, perCheat), rock);
            // Honest players on bad days belong in training too: what a feature
            // does to them is what keeps its weight down.
            bag.addAll(stressCorpus(from + 500_000, honest / 2, rock));
            bags.add(bag);
        }
        List<Played> train = bags.get(0);
        List<Played> test = corpus(2_000_000, honest, perCheat, cheatScenarios(0.25, 0.5, 1.0), rock);
        System.out.printf(Locale.ROOT, "corpus: %d norms + %d×%d training + %d test sessions in %.1f s%n", norms.size(),
                nBags, train.size(), test.size(), (System.currentTimeMillis() - t0) / 1000.0);
        List<Played> everything = new ArrayList<>(norms);
        everything.addAll(test);
        System.out.println(distributions(everything));

        Baselines base = baselines(norms);
        Calibration neutral = model(Calibration.Prior.NEUTRAL);
        Bench before = benchmark(test, base, neutral);
        System.out.println(before.report("нормы сервера, модель без обучения (все веса 1)"));
        Bench coldBefore = benchmark(test, new Baselines(), neutral);
        System.out.println(coldBefore.report("без норм, модель без обучения (все веса 1)"));

        String version = "lab-3.3.0";
        Calibration.Prior prior = trainBagged(bags, realSessions, base, version);
        Calibration trained = model(prior);
        Bench after = benchmark(test, base, trained);
        System.out.println(after.report("нормы сервера, обученный prior"));
        Bench coldAfter = benchmark(test, new Baselines(), trained);
        System.out.println(coldAfter.report("без норм, обученный prior"));
        Calibration.Prior.override(null);

        System.out.println(summary(before, after, coldBefore, coldAfter));
        System.out.println(ablation(test, base, prior));
        List<Played> stress = stressCorpus(3_000_000, honest, rock);
        Bench stressBench = benchmark(stress, base, trained);
        System.out.println(stressBench.report("стресс: честные с плохой сетью, лагами и ровными кликами"));
        if (!realSessions.isEmpty()) {
            System.out.println(benchmark(realSessions, base, trained).report("реальные сессии (без меток), нормы лаборатории"));
            System.out.println(benchmark(realSessions, baselines(realSessions), trained).report("реальные сессии, их собственные нормы"));
        }
        String json = priorJson(prior, String.format(Locale.ROOT,
                "Argus Lab: %d honest norm sessions, %d bags of %d honest + %d cheating training sessions, each with and without norms; held-out: FP %.1f%%, stress FP %.1f%%, detection %.1f%% (with norms), %.1f%% (without)",
                honest, nBags, honest + honest / 2, train.size() - honest - honest / 2, 100 * after.fpRate(),
                100 * stressBench.fpRate(), 100 * after.detection(), 100 * coldAfter.detection()));
        System.out.println(json);
        if (write) {
            java.nio.file.Path out = java.nio.file.Path.of("src/main/resources/bastionac/argus-prior.json");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, json);
            System.out.println("written " + out.toAbsolutePath());
        }
    }

    /**
     * What each feature is worth: detection with the feature's weight set to
     * zero, against the full model (held-out, with norms). A feature whose
     * removal costs nothing is either redundant or idle on this bench.
     */
    static String ablation(List<Played> test, Baselines base, Calibration.Prior prior) {
        StringBuilder sb = new StringBuilder("== вклад признаков (детект без признака, с нормами) ==\n");
        double full = benchmark(test, base, model(prior)).detection();
        List<String[]> rows = new ArrayList<>();
        for (String key : prior.weights().keySet()) {
            if (Calibration.SIG.equals(key)) continue;
            Map<String, Double> w = new TreeMap<>(prior.weights());
            w.put(key, 0.0);
            Bench b = benchmark(test, base, model(new Calibration.Prior(prior.version(), prior.bias(), w)));
            rows.add(new String[]{key, String.format(Locale.ROOT, "%.1f", 100 * (full - b.detection()))});
        }
        Calibration.Prior.override(null);
        rows.sort((x, y) -> Double.compare(Double.parseDouble(y[1]), Double.parseDouble(x[1])));
        sb.append(String.format(Locale.ROOT, "полная модель: %.1f%%%n", 100 * full));
        for (String[] r : rows) sb.append(String.format(Locale.ROOT, "  %-22s −%s п.п.%n", r[0], r[1]));
        return sb.toString();
    }

    /** Detection per cheat, before and after training, as one table. */
    static String summary(Bench before, Bench after, Bench coldBefore, Bench coldAfter) {
        StringBuilder sb = new StringBuilder("== сводка: обнаружено (≥ подозрительно), held-out ==\n");
        sb.append(String.format(Locale.ROOT, "%-12s %10s %10s %10s %10s%n", "чит", "без обуч.", "обучена", "без/б.н.", "обуч./б.н."));
        for (Cheat c : Cheat.values()) {
            sb.append(String.format(Locale.ROOT, "%-12s %9.0f%% %9.0f%% %9.0f%% %9.0f%%%n", c.name(),
                    100 * before.detection(c), 100 * after.detection(c), 100 * coldBefore.detection(c), 100 * coldAfter.detection(c)));
        }
        sb.append(String.format(Locale.ROOT, "%-12s %9.1f%% %9.1f%% %9.1f%% %9.1f%%%n", "ложные",
                100 * before.fpRate(), 100 * after.fpRate(), 100 * coldBefore.fpRate(), 100 * coldAfter.fpRate()));
        sb.append(String.format(Locale.ROOT, "%-12s %10.3f %10.3f %10.3f %10.3f%n", "макс.чест",
                before.honestMax, after.honestMax, coldBefore.honestMax, coldAfter.honestMax));
        return sb.toString();
    }
}
