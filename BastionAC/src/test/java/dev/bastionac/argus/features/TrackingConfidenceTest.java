package dev.bastionac.argus.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackingConfidenceTest {
    @Test void threeDelayMarginRetainsStrictOneSidedFamilyConfidence() {
        assertTrue(3 * normalTail(CombatFeatures.INSTANT_CORRELATION_SIGMA) < .01);
        assertTrue((CombatFeatures.REL_MAX - CombatFeatures.REL_MIN + 1)
                * normalTail(CombatFeatures.LAG_CORRELATION_SIGMA) < .01);
        assertEquals(3.5, CombatFeatures.LAG_CORRELATION_SIGMA);
    }

    @Test void smallerSearchUsesItsOwnFisherLowerBound() {
        double instant = CombatFeatures.correlationLower(.25, 1000, CombatFeatures.INSTANT_CORRELATION_SIGMA);
        double delayed = CombatFeatures.correlationLower(.25, 1000, CombatFeatures.LAG_CORRELATION_SIGMA);
        assertEquals(.16533725815080827, instant, 1e-12);
        assertEquals(.14373088357372787, delayed, 1e-12);
        assertTrue(instant >= CombatFeatures.MIN_TRACK_CORRELATION);
        assertTrue(delayed < CombatFeatures.MIN_TRACK_CORRELATION);
        assertEquals(0, CombatFeatures.correlationLower(.25, 0, CombatFeatures.INSTANT_CORRELATION_SIGMA));
        assertEquals(0, CombatFeatures.correlationLower(-.9, 1000, CombatFeatures.INSTANT_CORRELATION_SIGMA));
        assertTrue(Double.isFinite(CombatFeatures.correlationLower(1, 1000, CombatFeatures.INSTANT_CORRELATION_SIGMA)));
    }

    @Test void supportedInstantAnswerDoesNotDependOnAnUncertainOutsidePeak() {
        var profile = new CombatFeatures.Profile();
        profile.windows = 6;
        peak(profile, 0, .25, 1000);
        peak(profile, 8, .3, 20);
        var features = new FeatureSet();
        profile.emit(features);
        assertNull(features.value(Features.spec("cmb.track_lag")));
        assertEquals(.16533725815080827, features.value(Features.spec("cmb.track_instant")).value(), 1e-12);
        assertEquals(0, profile.windows);
        for (double information : profile.information) assertEquals(0, information);
    }

    @Test void supportedDelayedPeakCannotAuthorizeAnUncertainInstantAnswer() {
        var profile = new CombatFeatures.Profile();
        profile.windows = 6;
        peak(profile, 0, .25, 50);
        peak(profile, 4, .9, 1000);
        var features = new FeatureSet();
        profile.emit(features);
        assertEquals(4, features.value(Features.spec("cmb.track_lag")).value());
        assertEquals(0, features.value(Features.spec("cmb.track_instant")).value());
        assertEquals(0.0, dev.bastionac.argus.Scorer.score(features, null, "sample", null, 0)
                .evidenceByKey().getOrDefault("cmb.track_instant", 0.0));
    }

    @Test void measuredControlsPreserveTheUncensoredTrackingPopulation() {
        var baseline = new dev.bastionac.argus.Baselines();
        var spec = Features.spec("cmb.track_instant");
        for (int session = 0; session < 12; session++) {
            var features = new FeatureSet();
            for (int window = 0; window < 3; window++) {
                var profile = new CombatFeatures.Profile();
                profile.windows = 6;
                peak(profile, 0, .25, 50);
                peak(profile, 4, .9, 1000);
                profile.emit(features);
            }
            assertEquals(3, features.value(spec).support());
            assertEquals(0, features.value(spec).value());
            baseline.addSession("control-" + session, java.util.Map.of(spec.key(), features.value(spec).value()));
        }
        assertEquals(12, baseline.poolSize(spec.key()));
        assertEquals(0, baseline.population(spec).ref());
        assertEquals(spec.minScale(), baseline.population(spec).scale());
    }

    @Test void marginalControlBoundsRemainMeasuredRatherThanBeingRoundedToZero() {
        var profile = new CombatFeatures.Profile();
        profile.windows = 6;
        peak(profile, 0, .25, 200);
        peak(profile, 4, .9, 1000);
        var features = new FeatureSet();
        profile.emit(features);
        double measured = features.value(Features.spec("cmb.track_instant")).value();
        assertEquals(CombatFeatures.correlationLower(.25, 200, CombatFeatures.INSTANT_CORRELATION_SIGMA), measured);
        assertTrue(measured > 0 && measured < CombatFeatures.MIN_TRACK_CORRELATION);
    }

    @Test void controlsAtTheConfidenceFloorCannotBecomePositiveEvidence() {
        var baseline = new dev.bastionac.argus.Baselines();
        var spec = Features.spec("cmb.track_instant");
        for (int i = 0; i < 12; i++) baseline.addSession("control-" + i, java.util.Map.of(spec.key(), 0.0));
        var features = new FeatureSet();
        for (int i = 0; i < 10; i++) features.sample(spec.key(), CombatFeatures.MIN_TRACK_CORRELATION);
        for (var norms : new dev.bastionac.argus.Baselines[]{null, baseline}) {
            assertEquals(0.0, dev.bastionac.argus.Scorer.score(features, norms, "sample", null, 0)
                    .evidenceByKey().getOrDefault(spec.key(), 0.0));
        }
    }

    @Test void delayedSupportCannotCreateAnUnavailableInstantMeasurement() {
        var profile = new CombatFeatures.Profile();
        profile.windows = 6;
        peak(profile, 4, .9, 1000);
        var features = new FeatureSet();
        profile.emit(features);
        assertEquals(4, features.value(Features.spec("cmb.track_lag")).value());
        assertNull(features.value(Features.spec("cmb.track_instant")));

        profile.windows = 6;
        peak(profile, 0, .9, 0);
        profile.emit(features);
        assertNull(features.value(Features.spec("cmb.track_instant")));

        profile = new CombatFeatures.Profile();
        profile.add(new double[50], new double[50], 0, 0);
        profile.emit(features);
        assertNull(features.value(Features.spec("cmb.track_instant")));
    }

    @Test void overlappingDifferencesRetainTheBartlettInformationDiscount() {
        var profile = new CombatFeatures.Profile();
        var random = new java.util.Random(985);
        double[] white = new double[2000];
        for (int i = 0; i < white.length; i++) white[i] = random.nextGaussian();
        double[] differences = CombatFeatures.diff(white);
        profile.add(differences, differences, 0, 0);
        double information = profile.information[-CombatFeatures.REL_MIN];
        assertTrue(information > differences.length * .5);
        assertTrue(information < differences.length * .8);
    }

    private static void peak(CombatFeatures.Profile profile, int lag, double correlation, double information) {
        int index = lag - CombatFeatures.REL_MIN;
        profile.sxx[index] = profile.syy[index] = 1;
        profile.sxy[index] = correlation;
        profile.information[index] = information;
    }

    private static double normalTail(double lower) {
        // Simpson integration evaluates the probability independently of Fisher's transform.
        int intervals = 10000;
        double step = (10 - lower) / intervals, sum = 0;
        for (int i = 0; i <= intervals; i++) {
            double x = lower + i * step;
            double density = Math.exp(-x * x / 2) / Math.sqrt(2 * Math.PI);
            sum += density * (i == 0 || i == intervals ? 1 : i % 2 == 0 ? 2 : 4);
        }
        return sum * step / 3;
    }
}
