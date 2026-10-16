package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;
import dev.bastionac.argus.features.MovementFeatures;
import dev.bastionac.telemetry.SessionWriter;
import org.junit.jupiter.api.Test;

import static dev.bastionac.telemetry.TelemetryFormat.ST_FLYING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ClickTP on foot, and an operator hovering in creative (3.4). */
class TeleportAndHoverTest {

    private static FeatureSet run(PhysicsReplayTest.Sim s) throws Exception {
        FeatureSet fs = new FeatureSet();
        MovementFeatures.analyze(s.read(), fs, raw -> 0.6f);
        return fs;
    }

    private static double value(FeatureSet fs, String key) {
        FeatureSet.Value v = fs.value(Features.spec(key));
        return v == null ? 0 : v.value();
    }

    private static void run(PhysicsReplayTest.Sim s, int ticks) {
        for (int i = 0; i < ticks; i++) s.tick(true, i % 12 == 0);
    }

    @Test
    void honestRunningHasNoTeleports() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        for (int lap = 0; lap < 20; lap++) {
            run(s, 40);
            s.yaw += 37;
        }
        FeatureSet fs = run(s);
        assertEquals(0.0, value(fs, "move.teleport"), 1e-9);
        assertEquals(0, fs.raw().get("move.teleport").momentTotal);
    }

    @Test
    void clickTpShowsEvenWhenTheServerSetsItBack() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        run(s, 200);
        long[] at = new long[3];
        for (int k = 0; k < 3; k++) {
            // One packet five blocks ahead, the server answers with a setback 25 ms later.
            double bx = s.x, bz = s.z;
            s.z += 5;
            s.tick(false, false);
            at[k] = s.t;
            s.w.teleportSent(s.t + 25, 100 + k, bx, s.y, bz, 0);
            s.x = bx;
            s.z = bz;
            s.vx = 0;
            s.vz = 0;
            s.tick(false, false);
            run(s, 60);
        }
        FeatureSet fs = run(s);
        FeatureSet.Acc a = fs.raw().get("move.teleport");
        assertEquals(3, a.momentTotal, "each jump counted, the setback excuses only what follows it");
        assertEquals(at[0], a.moments.a[0], "and the staff see when");
        assertTrue(value(fs, "move.teleport") > 10, "per hour");
    }

    @Test
    void hoveringInCreativeIsNotRiseAgainstGravity() throws Exception {
        PhysicsReplayTest.Sim s = new PhysicsReplayTest.Sim();
        run(s, 40);
        SessionWriter.State st = new SessionWriter.State();
        st.gameMode = 1;
        st.flags = ST_FLYING;
        s.w.state(s.t + 1, st);
        // Flying up, then hanging still: a hovering client sends no position at all.
        for (int i = 0; i < 20; i++) {
            s.t += 50;
            s.y += 0.4;
            s.w.move(s.t, true, s.x, s.y, s.z, true, s.yaw, 10f, false, false);
            s.w.tickEnd(s.t + 1);
        }
        for (int i = 0; i < 400; i++) {
            s.t += 50;
            s.w.tickEnd(s.t + 1);
        }
        // ...until it moves again: the silent ticks in between are reconstructed and judged.
        s.t += 50;
        s.w.move(s.t, true, s.x + 0.1, s.y, s.z, true, s.yaw, 10f, false, false);
        s.w.tickEnd(s.t + 1);
        FeatureSet fs = run(s);
        assertEquals(0.0, value(fs, "move.rise"), 1e-9, "silent ticks inherit the creative state of the last packet");
    }
}
