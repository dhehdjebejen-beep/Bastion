package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.MovementFeatures;
import dev.bastionac.argus.features.ServerImpulse;
import dev.bastionac.telemetry.SessionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerImpulseTest {
    private static void tick(PhysicsReplayTest.Sim s, int n) {
        for (int i = 0; i < n; i++) s.tick(false, false);
    }

    private static FeatureSet features(SessionData s) {
        FeatureSet f = new FeatureSet();
        MovementFeatures.analyze(s, f, raw -> 0.6f);
        return f;
    }

    @Test
    void upwardServerImpulseRemainsExcludedBeyondOneAndAHalfSecondsUntilLanding() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        tick(s, 40);
        long sent = s.t;
        s.w.velocity(sent, 0, 2.5, 0.8, false);
        s.vy = 2.5;
        s.vz = 0.8;
        tick(s, 160);
        SessionData d = s.read();
        boolean[] excluded = ServerImpulse.excluded(d);
        boolean extended = false;
        int landed = -1;
        for (int i = 0; i < d.moveT.n; i++) {
            if (d.moveT.a[i] <= sent + 1500) continue;
            if ((d.moveFlags.a[i] & 4) == 0) {
                assertTrue(excluded[i], "recorded launch is still in flight");
                extended = true;
            } else if (landed < 0) landed = i;
        }
        assertTrue(extended);
        assertTrue(landed > 0);
        assertFalse(excluded[landed + 2], "a completed trajectory cannot exempt later play");
        assertEquals(0, features(d).raw().get("move.rise").num);
    }

    @Test
    void horizontalImpulseExpiresEvenWithoutLanding() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        s.y = 500;
        s.onGround = false;
        tick(s, 10);
        long sent = s.t;
        s.w.velocity(sent, 0.25, 0, 0, true);
        s.vx = 0.25;
        tick(s, 130);
        SessionData d = s.read();
        boolean[] excluded = ServerImpulse.excluded(d);
        int i = d.moveT.n - 1;
        assertEquals(0, d.moveFlags.a[i] & 4, "still above the floor");
        assertFalse(excluded[i], "vanilla drag exhausted the issued horizontal velocity");
    }

    @Test
    void ignoredUpwardVelocityDoesNotExemptGroundMovementForItsWholeDecayTime() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        tick(s, 40);
        s.w.velocity(s.t, 0, 3, 0, false);
        tick(s, 80); // the player never took off
        SessionData d = s.read();
        assertFalse(ServerImpulse.excluded(d)[d.moveT.n - 1]);
    }

    @Test
    void absoluteVelocityCancellationEndsThePreviousAirborneTrajectory() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        s.y = 500;
        s.onGround = false;
        s.w.velocity(s.t, 0, 0.42, 0, false);
        s.vy = 0.42;
        tick(s, 10);
        s.w.velocity(s.t, 0, 0, 0, false);
        s.vy = 0;
        tick(s, 90);
        SessionData d = s.read();
        int last = d.moveT.n - 1;
        assertEquals(0, d.moveFlags.a[last] & 4, "the player has not landed yet");
        assertFalse(ServerImpulse.excluded(d)[last], "the absolute zero ended the issued upward motion");
    }

    @Test
    void additiveExplosionDoesNotCancelAnExistingUpwardImpulse() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        s.y = 500;
        s.onGround = false;
        s.w.velocity(s.t, 0, 2.5, 0, false);
        s.vy = 2.5;
        tick(s, 10);
        long explosion = s.t;
        s.w.velocity(explosion, 0, 0, 0, true);
        tick(s, 90);
        SessionData d = s.read();
        int last = d.moveT.n - 1;
        assertTrue(d.moveT.a[last] > explosion + 3500);
        assertTrue(ServerImpulse.excluded(d)[last], "additive zero did not replace the existing launch");
    }

    @Test
    void speedAfterLandingIsStillJudged() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        tick(s, 40);
        s.w.velocity(s.t, 0, 2.5, 0.8, false);
        s.vy = 2.5;
        s.vz = 0.8;
        tick(s, 160);
        s.airAccel = 0.07;
        for (int i = 0; i < 600; i++) s.tick(true, i % 12 == 0);
        FeatureSet f = features(s.read());
        assertTrue(f.raw().get("move.air_gain").num / f.raw().get("move.air_gain").den > 0.3);
    }

    @Test
    void speedWithoutServerImpulseRetainsItsEvidence() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        s.airAccel = 0.07;
        for (int i = 0; i < 600; i++) s.tick(true, i % 12 == 0);
        SessionData d = s.read();
        for (boolean excluded : ServerImpulse.excluded(d)) assertFalse(excluded);
        FeatureSet f = features(d);
        assertTrue(f.raw().get("move.air_gain").num / f.raw().get("move.air_gain").den > 0.3);
    }
}
