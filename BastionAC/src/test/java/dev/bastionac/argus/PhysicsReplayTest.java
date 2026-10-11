package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;
import dev.bastionac.argus.features.MovementFeatures;
import dev.bastionac.telemetry.SessionData;
import dev.bastionac.telemetry.SessionWriter;
import dev.bastionac.telemetry.TelemetryReader;
import org.junit.jupiter.api.Test;

import static dev.bastionac.telemetry.TelemetryFormat.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The physics replay against a small vanilla simulator (LivingEntity.travelMidAir
 * on flat ground): honest movement must produce no violation at all, and each
 * cheat shape must show up in its own feature.
 */
class PhysicsReplayTest {

    /** A flat-world player, ticked the way the vanilla client ticks it. */
    static final class Sim {
        double x, y = 64, z, vx, vy, vz;
        boolean onGround = true;
        float yaw;
        double airAccel = 0.026;          // a speed hack raises this
        boolean jumpWithoutKey;           // a HighJump / bhop module jumps by itself
        double sideKick;                  // a strafe module pushes sideways
        float pushOffset;                 // a module that walks where the camera is not pointing
        final SessionWriter w = new SessionWriter("u", "Sim", 1_000_000, "f", 1L << 40);
        long t = 1_000_000;

        Sim() {
            this(0.13f, ENV_SPRINTING);
        }

        /** {@code attr}/{@code env}: what the server believes about the sprint. */
        Sim(float attr, int env) {
            w.header("{\"uuid\":\"u\",\"name\":\"Sim\",\"start\":1000000}");
            w.env(t, env);
            w.ground(t, 1);
            w.speed(t, attr);
        }

        void tick(boolean forward, boolean jump) {
            t += 50;
            int keys = (forward ? IN_FORWARD | IN_SPRINT : 0) | (jump && !jumpWithoutKey ? IN_JUMP : 0);
            w.input(t, keys);
            if (Math.abs(vx) < 0.003) vx = 0;
            if (Math.abs(vy) < 0.003) vy = 0;
            if (Math.abs(vz) < 0.003) vz = 0;
            boolean startGround = onGround;
            double r = Math.toRadians(yaw + pushOffset);
            double fx = -Math.sin(r), fz = Math.cos(r);
            if (jump && startGround) {
                vy = 0.42;
                if (forward) {
                    vx += fx * 0.2;
                    vz += fz * 0.2;
                }
            }
            double slip = startGround ? 0.6 : 1.0;
            double accel = startGround ? 0.13 * 0.21600002 / (slip * slip * slip) : airAccel;
            if (forward) {
                vx += fx * accel * 0.98;
                vz += fz * accel * 0.98;
            }
            if (!startGround && sideKick > 0) {
                vx += fz * sideKick;
                vz -= fx * sideKick;
            }
            x += vx;
            z += vz;
            double ny = y + vy;
            if (ny <= 64) {
                ny = 64;
                vy = 0;
                onGround = true;
            } else {
                onGround = false;
            }
            y = ny;
            double f = slip * 0.91;
            vx *= f;
            vz *= f;
            vy = (vy - 0.08) * 0.98;
            w.move(t, true, x, y, z, true, yaw, 10f, onGround, false);
            w.tickEnd(t + 1);
        }

        SessionData read() throws Exception {
            return TelemetryReader.readRaw(w.drain());
        }
    }

    private static FeatureSet run(Sim s) throws Exception {
        SessionData d = s.read();
        FeatureSet fs = new FeatureSet();
        MovementFeatures.analyze(d, fs, raw -> 0.6f);
        return fs;
    }

    private static double rate(FeatureSet fs, String key) {
        FeatureSet.Value v = fs.value(Features.spec(key));
        return v == null ? 0 : v.value();
    }

    /** Sprinting, sprint-jumping, turning, stopping — the honest mix. */
    private static void honestRun(Sim s) {
        for (int lap = 0; lap < 30; lap++) {
            for (int i = 0; i < 40; i++) s.tick(true, i % 12 == 0);
            s.yaw += 37;
            for (int i = 0; i < 15; i++) s.tick(false, false);
        }
    }

    @Test
    void vanillaMovementIsClean() throws Exception {
        Sim s = new Sim();
        honestRun(s);
        FeatureSet fs = run(s);
        assertEquals(0.0, rate(fs, "move.air_gain"), 1e-9, "air gain on vanilla");
        assertEquals(0.0, rate(fs, "move.ground_gain"), 1e-9, "ground gain on vanilla");
        assertEquals(0.0, rate(fs, "move.rise"), 1e-9, "rise on vanilla");
        assertEquals(0.0, rate(fs, "move.fall_fast"), 1e-9, "fast fall on vanilla");
        assertEquals(0.0, rate(fs, "move.jump_nokey"), 1e-9, "jumps without key on vanilla");
        assertEquals(0.0, rate(fs, "move.move_nokey"), 1e-9, "moving without keys on vanilla");
        assertEquals(0.0, rate(fs, "move.input_dir"), 1e-9, "push against keys on vanilla");
        assertEquals(0.0, rate(fs, "move.air_turn"), 1e-9, "air turn on vanilla");
        // Sprint-jumping keeps the player airborne most of the run; the coasting stretches are ground.
        assertTrue(fs.value(Features.spec("move.ground_gain")).support() > 200, "the ground was judged");
        assertTrue(fs.value(Features.spec("move.air_gain")).support() > 500, "the air was judged");
        assertTrue(fs.value(Features.spec("move.jump_nokey")).support() > 50, "the jumps were judged");
    }

    /** The live test: the client sprints, the server never heard START_SPRINTING (attribute 0.1). */
    @Test
    void sprintTheServerDidNotHearIsStillClean() throws Exception {
        Sim s = new Sim(0.1f, 0);
        honestRun(s);
        FeatureSet fs = run(s);
        assertEquals(0.0, rate(fs, "move.ground_gain"), 1e-9);
        assertEquals(0.0, rate(fs, "move.air_gain"), 1e-9);
    }

    @Test
    void airSpeedHackShows() throws Exception {
        Sim s = new Sim();
        s.airAccel = 0.07;
        honestRun(s);
        assertTrue(rate(run(s), "move.air_gain") > 0.3, "air speed hack must show in air_gain");
    }

    @Test
    void jumpWithoutKeyShows() throws Exception {
        Sim s = new Sim();
        s.jumpWithoutKey = true;
        honestRun(s);
        assertEquals(1.0, rate(run(s), "move.jump_nokey"), 1e-9);
    }

    @Test
    void strafeShows() throws Exception {
        Sim s = new Sim();
        s.sideKick = 0.06;
        honestRun(s);
        FeatureSet fs = run(s);
        assertTrue(rate(fs, "move.air_turn") > 0.3 || rate(fs, "move.air_gain") > 0.3, "strafe in the air");
    }

    @Test
    void groundPushAgainstKeysShows() throws Exception {
        Sim s = new Sim();
        // Only W held, the camera at yaw, the body pushed 90° to the side (silent-rotation strafe).
        s.pushOffset = 90f;
        for (int i = 0; i < 400; i++) s.tick(true, false);
        assertTrue(rate(run(s), "move.input_dir") > 0.8, "push 90° off the keys must show");
    }

    @Test
    void inputDirectionMatchesVanillaFormula() {
        // W at yaw 0 pushes +z; A (left) at yaw 0 pushes +x; W at yaw 90 pushes −x.
        double[] w0 = invokeDir(IN_FORWARD, 0f);
        assertEquals(0, w0[0], 1e-9);
        assertEquals(1, w0[1], 1e-9);
        double[] a0 = invokeDir(IN_LEFT, 0f);
        assertEquals(1, a0[0], 1e-9);
        double[] w90 = invokeDir(IN_FORWARD, 90f);
        assertEquals(-1, w90[0], 1e-9);
    }

    private static double[] invokeDir(int keys, float yaw) {
        try {
            var m = MovementFeatures.class.getDeclaredMethod("inputDirection", int.class, float.class);
            m.setAccessible(true);
            return (double[]) m.invoke(null, keys, yaw);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
