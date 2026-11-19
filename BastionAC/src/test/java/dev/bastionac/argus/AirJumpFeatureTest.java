package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.MovementFeatures;
import org.junit.jupiter.api.Test;

import static dev.bastionac.telemetry.TelemetryFormat.*;
import static org.junit.jupiter.api.Assertions.*;

class AirJumpFeatureTest {
    private static FeatureSet sequence(int repeats, boolean support, boolean impulse) throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        for (int i = 0; i < 30; i++) s.tick(true, false);
        if (impulse) s.w.velocity(s.t, 0, 2.5, 0.8, false);
        s.tick(true, true);
        if (support) s.w.env(s.t, ENV_SPRINTING | ENV_SUPPORT);
        for (int repeat = 0; repeat < repeats; repeat++) {
            for (int i = 0; i < 3; i++) s.tick(true, false);
            s.vy = 0.42;
            s.vz += 0.2;
            s.tick(true, true);
        }
        for (int i = 0; i < 40; i++) s.tick(true, false);
        FeatureSet f = new FeatureSet();
        MovementFeatures.analyze(s.read(), f, raw -> 0.6f);
        return f;
    }

    @Test
    void twoRenewedJumpImpulsesWhileRisingAreVisibleInAShortSession() throws Exception {
        assertEquals(2, sequence(2, false, false).raw().get("move.air_jump").num);
    }

    @Test
    void oneAmbiguousEventDoesNotBecomeAnAccusation() throws Exception {
        assertEquals(0, sequence(1, false, false).raw().get("move.air_jump").num);
    }

    @Test
    void supportAndRecordedExternalMotionPreventAirJumpEvidence() throws Exception {
        assertEquals(0, sequence(4, true, false).raw().get("move.air_jump").num);
        assertEquals(0, sequence(4, false, true).raw().get("move.air_jump").num);
    }
}
