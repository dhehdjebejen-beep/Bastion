package dev.bastionac.argus;

import dev.bastionac.argus.features.CombatFeatures;
import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.MovementFeatures;
import dev.bastionac.argus.features.PacketFeatures;
import dev.bastionac.config.ACConfig;
import dev.bastionac.telemetry.SessionData;
import dev.bastionac.telemetry.SessionWriter;
import dev.bastionac.telemetry.TelemetryReader;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static dev.bastionac.telemetry.TelemetryFormat.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-session scenarios, end to end: a synthetic session is written in the
 * real telemetry format, read back, measured by every feature family, scored
 * and given a verdict exactly as a case would be. Honest play must come out
 * clean; each cheat must come out at least suspicious, for its own reasons.
 */
class ArgusScenarioTest {

    /** A duel: the player strafes round a target and hits it. */
    static final class Duel {
        final SessionWriter w = new SessionWriter("u", "Sim", 1_000_000, "f", 1L << 40);
        final Random rnd;
        long t = 1_000_000;
        double x, y = 64, z;
        float yaw, pitch = 5f;
        /** Mouse-count size of one rotation step (vanilla: (sens·0.6+0.2)³·8·0.15); 50 % by default. */
        double step = Math.pow(0.5, 3) * 8 * 0.15;
        int ping = 60;

        // How the "player" behaves.
        boolean auraAim;          // rotations computed, not moused: exact, off-grid, snapped
        boolean autoClicker;      // perfectly even clicks
        double reach = 2.6;       // hit distance
        double airAccel = 0.026;  // a speed hack raises this
        double timer = 1.0;       // >1: the client's ticks come faster than 50 ms

        Duel(long seed) {
            rnd = new Random(seed);
            w.header("{\"uuid\":\"u\",\"name\":\"Sim\",\"start\":1000000}");
            w.env(t, ENV_SPRINTING);
            w.ground(t, 1);
            w.speed(t, 0.13f);
        }

        /** Target bearing for this tick: it circles the player slowly. */
        double bearing(int i) {
            return (i * 2.3) % 360 + 15 * Math.sin(i / 7.0);
        }

        void fight(int ticks) {
            double vx = 0, vz = 0, vy = 0;
            boolean ground = true;
            long nextClick = t + 300;
            float lastDy = 0, lastDp = 0;
            for (int i = 0; i < ticks; i++) {
                t += Math.round(50 / timer);
                boolean jump = i % 11 == 0;
                w.input(t, IN_FORWARD | IN_SPRINT | (jump ? IN_JUMP : 0));
                // Movement: sprint-jumping, vanilla air control (or a raised one).
                double r = Math.toRadians(yaw);
                double fx = -Math.sin(r), fz = Math.cos(r);
                if (jump && ground) {
                    vy = 0.42;
                    vx += fx * 0.2;
                    vz += fz * 0.2;
                }
                double slip = ground ? 0.6 : 1.0;
                double accel = ground ? 0.13 * 0.21600002 / (slip * slip * slip) : airAccel;
                vx += fx * accel * 0.98;
                vz += fz * accel * 0.98;
                x += vx;
                z += vz;
                double ny = y + vy;
                if (ny <= 64) { ny = 64; vy = 0; ground = true; } else ground = false;
                y = ny;
                vx *= slip * 0.91;
                vz *= slip * 0.91;
                vy = (vy - 0.08) * 0.98;

                // Rotation towards the target.
                double want = bearing(i);
                double err = wrap(want - yaw);
                float dy, dp;
                if (auraAim) {
                    // Straight onto the target, in exact floats; a fixed step while tracking.
                    dy = (float) err;
                    dp = (float) ((5 + 2 * Math.sin(i / 9.0)) - pitch);
                    if (Math.abs(dy) < 3) { dy = lastDy; }
                } else {
                    // A hand: a fraction of the way, noisy, quantised to mouse counts.
                    double hand = err * (0.35 + 0.3 * rnd.nextDouble()) + rnd.nextGaussian() * 1.5;
                    dy = (float) (Math.round(hand / step) * step);
                    dp = (float) (Math.round(rnd.nextGaussian() * 0.8 / step) * step);
                }
                yaw += dy;
                pitch = Math.max(-90, Math.min(90, pitch + dp));
                lastDy = dy;
                lastDp = dp;
                w.move(t, true, x, y, z, true, yaw, pitch, ground, false);
                w.tickEnd(t + 1);

                // Clicks.
                if (t >= nextClick) {
                    SessionWriter.Attack a = new SessionWriter.Attack();
                    a.targetId = 7;
                    a.flags = SessionWriter.Attack.F_PLAYER | SessionWriter.Attack.F_SPRINTING;
                    a.distance = (float) (reach + rnd.nextGaussian() * 0.15);
                    double miss = Math.abs(wrap(bearing(i) - yaw));
                    a.aimError = (float) (auraAim ? Math.abs(rnd.nextGaussian() * 0.3) : miss + Math.abs(rnd.nextGaussian() * 3));
                    a.yaw = yaw;
                    a.pitch = pitch;
                    a.cooldown = 1f;
                    a.ping = ping;
                    w.attack(t, a);
                    long gap;
                    if (autoClicker) gap = 95 + rnd.nextInt(11);          // 95–105 ms, flat
                    else gap = Math.round(Math.exp(4.8 + rnd.nextGaussian() * 0.35)); // lognormal ~120 ms, heavy tail
                    if (rnd.nextInt(25) == 0) gap += 1200;               // a pause between flurries
                    nextClick = t + gap;
                }
            }
        }

        static double wrap(double d) {
            d %= 360;
            if (d >= 180) d -= 360;
            if (d < -180) d += 360;
            return d;
        }

        Scorer.Result score() throws Exception {
            SessionData s = TelemetryReader.readRaw(w.drain());
            FeatureSet fs = new FeatureSet();
            MovementFeatures.analyze(s, fs, raw -> 0.6f);
            PacketFeatures.analyze(s, fs);
            CombatFeatures.analyze(s, fs);
            return Scorer.score(fs, new Baselines(), "u", new Calibration(), 0);
        }
    }

    private static final ACConfig.ArgusCfg CFG = new ACConfig.ArgusCfg();

    private static String why(Scorer.Result r) {
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "score %.3f, strong %d: ", r.score(), r.strong()));
        for (Scorer.Row row : r.rows()) {
            if (row.evidence() <= 0) continue;
            sb.append(row.spec().key()).append(String.format(java.util.Locale.ROOT, "=%.3f(e%.2f) ", row.value(), row.evidence()));
        }
        return sb.toString();
    }

    private static CaseFile.Verdict verdict(Duel d) throws Exception {
        d.fight(6000);
        Scorer.Result r = d.score();
        System.out.println("SCENARIO → " + Argus.verdictOf(r, CFG) + " | " + why(r));
        return Argus.verdictOf(r, CFG);
    }

    @Test
    void honestFightersAreClean() throws Exception {
        for (long seed : new long[]{1, 2, 3, 42, 77}) {
            assertEquals(CaseFile.Verdict.CLEAN, verdict(new Duel(seed)), "honest duel, seed " + seed);
        }
        Duel lowSens = new Duel(9);
        lowSens.step = Math.pow(0.1 * 0.6 + 0.2, 3) * 8 * 0.15;     // 10 % sensitivity, tiny steps
        assertEquals(CaseFile.Verdict.CLEAN, verdict(lowSens), "low sensitivity");
        Duel highSens = new Duel(10);
        highSens.step = Math.pow(1.0 * 0.6 + 0.2, 3) * 8 * 0.15;    // 100 %
        assertEquals(CaseFile.Verdict.CLEAN, verdict(highSens), "high sensitivity");
    }

    @Test
    void highPingHitsAreNotReach() throws Exception {
        Duel lag = new Duel(11);
        lag.ping = 320;
        lag.reach = 3.5;     // what the server sees: the target already moved on
        assertEquals(CaseFile.Verdict.CLEAN, verdict(lag));
    }

    @Test
    void killAuraIsGuilty() throws Exception {
        Duel d = new Duel(42);
        d.auraAim = true;
        d.autoClicker = true;
        assertEquals(CaseFile.Verdict.GUILTY, verdict(d), "computed aim: centred and off the mouse grid");
    }

    @Test
    void reachIsAtLeastSuspicious() throws Exception {
        Duel d = new Duel(42);
        d.reach = 3.6;
        assertTrue(verdict(d).ordinal() >= CaseFile.Verdict.SUSPECT.ordinal());
    }

    @Test
    void speedWithTimerIsGuilty() throws Exception {
        Duel d = new Duel(42);
        d.airAccel = 0.06;
        d.timer = 1.1;
        assertEquals(CaseFile.Verdict.GUILTY, verdict(d));
    }

    /**
     * Known limit, written down: a steady autoclicker at a human rate is not
     * told apart from a steady human. The client attacks inside its own tick,
     * so both land on the same 50 ms grid; the realtime CPS cap is the
     * defence there. This test pins the behaviour so a future change is a
     * conscious one.
     */
    @Test
    void humanRateAutoclickerAloneIsNotConvicted() throws Exception {
        Duel d = new Duel(42);
        d.autoClicker = true;
        assertEquals(CaseFile.Verdict.CLEAN, verdict(d));
    }

}
