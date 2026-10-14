package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model on top of the shipped prior (3.3). */
class CalibrationTest {

    private static final Calibration.Prior PRIOR = new Calibration.Prior("t", -3.2,
            Map.of("pkt.blind_stall", 2.0, "cmb.click_rhythm", 0.8));

    @AfterEach
    void shippedPrior() {
        Calibration.Prior.override(null);
    }

    @Test
    void noLabelsMeansThePrior() {
        Calibration.Prior.override(PRIOR);
        Calibration c = new Calibration();
        c.fit(List.of(), 0.6);
        assertEquals(-3.2, c.bias, 1e-9);
        assertEquals(2.0, c.weight("pkt.blind_stall"), 1e-9);
        assertEquals(1.0, c.weight("move.air_gain"), 1e-9, "a key the prior does not name weighs 1");
    }

    @Test
    void labelsRefineThePriorInsteadOfOneAndAll() {
        Calibration.Prior.override(PRIOR);
        List<Calibration.Sample> s = new ArrayList<>();
        for (int i = 0; i < 6; i++) s.add(new Calibration.Sample(Map.of("pkt.blind_stall", 1.5), 0, true));
        for (int i = 0; i < 6; i++) s.add(new Calibration.Sample(Map.of("cmb.click_rhythm", 1.0), 0, false));
        Calibration c = new Calibration();
        c.fit(s, 0.6);
        assertTrue(c.weight("pkt.blind_stall") > 2.0, "confirmed cheaters raise it from the prior's 2.0");
        assertTrue(c.weight("cmb.click_rhythm") < 0.8, "cleared players lower it from the prior's 0.8");
        assertTrue(c.bias <= Calibration.MAX_BIAS);
    }

    @Test
    void aCheaterWithNoEvidenceTeachesNothing() {
        Calibration.Prior.override(PRIOR);
        List<Calibration.Sample> s = new ArrayList<>();
        // The owner banned forty players for what the features never saw.
        for (int i = 0; i < 40; i++) s.add(new Calibration.Sample(Map.of(), 0, true));
        s.add(new Calibration.Sample(Map.of(), 0, false));
        Calibration c = new Calibration();
        c.fit(s, 0.6);
        assertTrue(c.bias <= -3.0, "an empty session is still not suspicious: bias " + c.bias);
        assertEquals(40, c.cheaters);
        assertEquals(40, c.missedCheaters);
        assertTrue(c.score(Map.of(), 0) < 0.06);
    }

    @Test
    void theBiasNeverPassesItsCeiling() {
        Calibration.Prior.override(PRIOR);
        List<Calibration.Sample> s = new ArrayList<>();
        for (int i = 0; i < 50; i++) s.add(new Calibration.Sample(Map.of("cmb.aim_error", 0.05), 0, true));
        Calibration c = new Calibration();
        c.fit(s, 0.6);
        assertTrue(c.bias <= Calibration.MAX_BIAS, "bias " + c.bias);
    }

    @Test
    void aModelFromBeforeThePriorIsRefitted() throws Exception {
        Path dir = Files.createTempDirectory("argus-model");
        Path old = dir.resolve("model.json");
        Files.writeString(old, "{\"bias\":-2.8,\"weights\":{\"cmb.reach\":1.3},\"cheaters\":2,\"clean\":3}");
        assertFalse(Calibration.load(old).onCurrentPrior(), "a 3.2 model was fitted towards all-ones");
        Calibration fresh = new Calibration();
        Files.writeString(old, fresh.toJson());
        assertTrue(Calibration.load(old).onCurrentPrior());
        assertTrue(Calibration.load(dir.resolve("missing.json")).onCurrentPrior());
    }

    @Test
    void perHourCountsEpisodesNotMinutes() {
        Features.Spec stall = Features.spec("pkt.blind_stall");
        // One stall in the first three minutes: a quarter hour at least, not twenty an hour.
        FeatureSet one = new FeatureSet();
        one.perHour("pkt.blind_stall", 1, 0.05);
        assertEquals(4.0, one.value(stall).value(), 1e-9);
        // Seven in ten minutes: the count carries the confidence.
        FeatureSet seven = new FeatureSet();
        seven.perHour("pkt.blind_stall", 7, 10 / 60.0);
        Scorer.Result r = Scorer.score(seven, null, "u", null, 0);
        Scorer.Row row = r.rows().stream().filter(x -> x.spec() == stall).findFirst().orElseThrow();
        double hoursOnly = (10 / 60.0) / (10 / 60.0 + stall.halfSupport());
        assertTrue(row.evidence() > stall.weight() * hoursOnly * 4 * 1.5, "evidence " + row.evidence());
    }
}
