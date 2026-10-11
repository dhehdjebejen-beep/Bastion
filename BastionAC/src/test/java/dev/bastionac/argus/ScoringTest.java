package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;
import dev.bastionac.argus.features.MiningFeatures;
import dev.bastionac.argus.features.Stats;
import dev.bastionac.config.ACConfig;
import dev.bastionac.telemetry.SessionData;
import dev.bastionac.telemetry.SessionWriter;
import dev.bastionac.telemetry.TelemetryReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static dev.bastionac.telemetry.TelemetryFormat.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statistics, the score, the verdict rules, the calibration and the X-ray tests. */
class ScoringTest {

    @Test
    void statsBasics() {
        assertEquals(3.0, Stats.median(new double[]{5, 1, 3}), 1e-12);
        assertEquals(1.0, Stats.mad(new double[]{1, 2, 3, 4, 5}), 1e-12);
        assertEquals(-1.2, Stats.excessKurtosis(uniform(20000)), 0.05, "uniform clicks have kurtosis −1.2");
        assertEquals(0.0, Stats.poissonUpperZ(5, 10), 1e-12, "fewer than expected is nothing");
        double z = Stats.poissonUpperZ(30, 10);
        assertTrue(z > 4.5 && z < 6.5, "30 events where 10 were due is ~5σ, got " + z);
        assertEquals(12.0, Stats.poissonUpperZ(400, 1), 1e-9);
        assertEquals(1.959964, Stats.inverseNormalUpper(0.025), 1e-4);
    }

    private static double[] uniform(int n) {
        Random r = new Random(1);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = 60 + r.nextDouble() * 40;
        return v;
    }

    @Test
    void ordinaryValuesAreWorthNothing() {
        FeatureSet fs = new FeatureSet();
        fs.rate("move.air_gain", 2, 1000);        // 0,2 % — inside the prior
        fs.rate("move.ground_gain", 3, 2000);
        for (int i = 0; i < 40; i++) fs.sample("pkt.tick_rate", 20.0 + (i % 3 - 1) * 0.02);
        Scorer.Result r = Scorer.score(fs, new Baselines(), "u", new Calibration(), 0);
        assertEquals(0.0, r.evidence(), 1e-9);
        assertTrue(r.score() < 0.06, "an honest window scores " + r.score());
        assertEquals(CaseFile.Verdict.CLEAN, Argus.verdictOf(r, new ACConfig.ArgusCfg()));
    }

    @Test
    void independentStrongEvidenceIsGuilty() {
        FeatureSet fs = new FeatureSet();
        fs.rate("move.air_gain", 300, 1000);       // 30 % of air ticks over vanilla
        for (int i = 0; i < 40; i++) fs.sample("pkt.tick_rate", 22.0);   // ×1.1 timer
        Scorer.Result r = Scorer.score(fs, new Baselines(), "u", new Calibration(), 0);
        assertTrue(r.strong() >= 2);
        assertTrue(r.score() > 0.97, "score " + r.score());
        assertEquals(CaseFile.Verdict.GUILTY, Argus.verdictOf(r, new ACConfig.ArgusCfg()));
    }

    @Test
    void oneSoftFeatureAloneIsNeverGuilty() {
        FeatureSet fs = new FeatureSet();
        fs.rate("move.air_gain", 900, 1000);
        Scorer.Result r = Scorer.score(fs, new Baselines(), "u", new Calibration(), 2.5);
        assertTrue(r.strong() < 2 && !r.hardHit());
        CaseFile.Verdict v = Argus.verdictOf(r, new ACConfig.ArgusCfg());
        assertTrue(v != CaseFile.Verdict.GUILTY, "a single statistical feature is at most suspicious, got " + v);
    }

    @Test
    void baselineReplacesThePrior() {
        Baselines b = new Baselines();
        // This server's honest players simply strafe more than the prior assumes.
        for (int i = 0; i < 30; i++) b.addSession("p" + i, Map.of("move.air_turn", 0.03 + (i % 5) * 0.002));
        FeatureSet fs = new FeatureSet();
        fs.rate("move.air_turn", 32, 1000);
        Scorer.Result withBase = Scorer.score(fs, b, "x", new Calibration(), 0);
        Scorer.Result prior = Scorer.score(fs, new Baselines(), "x", new Calibration(), 0);
        assertEquals(0.0, withBase.evidence(), 1e-9, "normal for this server");
        assertTrue(prior.evidence() > 0, "unusual against the prior");
        b.forget("p1");
        assertEquals(29, b.poolSize("move.air_turn"));
    }

    @Test
    void calibrationLearnsFromLabels() {
        Calibration cal = new Calibration();
        List<Calibration.Sample> s = new ArrayList<>();
        // "cmb.snap" fires on players the owner cleared; "move.jump_nokey" only on cheaters.
        for (int i = 0; i < 6; i++) s.add(new Calibration.Sample(Map.of("cmb.snap", 3.0), 0, false));
        for (int i = 0; i < 6; i++) s.add(new Calibration.Sample(Map.of("move.jump_nokey", 2.0), 0, true));
        cal.fit(s, 0.6);
        assertTrue(cal.weight("cmb.snap") < 1.0, "snap weight " + cal.weight("cmb.snap"));
        assertTrue(cal.weight("move.jump_nokey") > 1.0, "jump weight " + cal.weight("move.jump_nokey"));
        assertTrue(cal.maxCleanScore < 0.5, "cleared players score low now: " + cal.maxCleanScore);
        assertEquals(6, cal.cheaters);
        assertEquals(6, cal.clean);
    }

    @Test
    void wallBreaksAndRevealTest() throws Exception {
        // A tunnel at y=10 along x; diamonds hidden at random in the rock, 1 in 200.
        WorldSample w = new WorldSample(-20, 0, -20, 141, 21, 41);
        Random r = new Random(7);
        for (int i = 0; i < w.cells.length; i++) w.cells[i] = (byte) (r.nextInt(200) == 0 ? CTX_DIAMOND : CTX_STONE);
        SessionWriter honest = tunnel(w, false);
        SessionWriter tracer = tunnel(w, true);
        MiningFeatures.Classifier cls = raw -> raw;   // raw ids are the categories in this test
        FeatureSet h = new FeatureSet(), t = new FeatureSet();
        SessionData hd = TelemetryReader.readRaw(honest.drain()), td = TelemetryReader.readRaw(tracer.drain());
        MiningFeatures.analyze(hd, h, cls, w);
        MiningFeatures.analyze(td, t, cls, w);
        double hz = h.value(Features.spec("mine.reveal_z")).value();
        double tz = t.value(Features.spec("mine.reveal_z")).value();
        assertTrue(hz < 2.5, "a straight tunnel finds ore at the rock's own rate, z=" + hz);
        assertTrue(tz > 4, "a tunnel that bends to every diamond is far above it, z=" + tz);
        assertEquals(0.0, h.value(Features.spec("mine.wall_breaks")).value(), 1e-9);
    }

    /** Digs a 1×2 tunnel along x; the tracer detours to every diamond within 3 blocks of it. */
    private static SessionWriter tunnel(WorldSample w, boolean tracer) {
        SessionWriter s = new SessionWriter("u", "M", 0, "f", 1L << 40);
        s.header("{\"uuid\":\"u\",\"name\":\"M\",\"start\":0}");
        s.life(0, LIFE_WORLD, 0, 0, 10, 0);
        long t = 0;
        java.util.Set<Long> dug = new java.util.HashSet<>();
        for (int x = 0; x < 120; x++) {
            for (int y = 10; y <= 11; y++) t = dig(s, w, dug, x, y, 0, t);
            if (!tracer) continue;
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    if (w.get(x, 10 + dy, dz) != CTX_DIAMOND || dz == 0) continue;
                    // Walk a side branch straight to it.
                    int step = dz > 0 ? 1 : -1;
                    for (int z = step; z != dz + step; z += step) t = dig(s, w, dug, x, 10 + dy, z, t);
                }
            }
        }
        return s;
    }

    private static long dig(SessionWriter s, WorldSample w, java.util.Set<Long> dug, int x, int y, int z, long t) {
        long p = MiningFeatures.pack(x, y, z);
        if (!dug.add(p)) return t;
        int cat = w.get(x, y, z);
        t += 300;
        s.action(t, 0, p, 1, 0, cat, 5f, 0, 1);
        s.broken(t + 1, p, cat, 1);
        return t;
    }
}
