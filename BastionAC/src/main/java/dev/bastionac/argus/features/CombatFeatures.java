package dev.bastionac.argus.features;

import dev.bastionac.core.MathUtil;
import dev.bastionac.telemetry.Prim;
import dev.bastionac.telemetry.SessionData;

import static dev.bastionac.telemetry.TelemetryFormat.MV_LOOK;

/**
 * Aim and clicking. A hand on a mouse is noisy in specific ways: its rotation
 * steps sit on the sensitivity grid, it rarely repeats a step exactly, it
 * misses the centre of a hitbox by a few degrees and its clicks have a
 * heavy tail. An aim module is smooth, exact, centred and evenly random.
 *
 * <p>Rotations are the raw floats the client sent. A vanilla client never
 * wraps its yaw (it accumulates past ±180), so a single step of more than
 * 300° is a rotation the client did not make with the mouse.
 */
public final class CombatFeatures {

    private CombatFeatures() {}

    public static void analyze(SessionData s, FeatureSet out) {
        double hours = Math.max(1e-6, s.durationMs() / 3_600_000.0);
        Prim.Longs tpTimes = teleportTimes(s);
        rotationShape(s, out, tpTimes, hours);
        if (s.attacks.isEmpty()) return;

        long center = 0, centerJudged = 0, blind = 0, snaps = 0;
        for (SessionData.Attack a : s.attacks) {
            if (a.distance > 1.5f) {
                centerJudged++;
                if (a.aimError < 1.2f) center++;
            }
            if (a.distance > 1.0f) out.sample("cmb.aim_error", a.aimError);
            if (a.aimError > 60f) blind++;
            if (a.has(dev.bastionac.telemetry.SessionWriter.Attack.F_PLAYER)) out.sample("cmb.reach", a.distance);
            if (flickBefore(s, a.t) > 25.0) snaps++;
        }
        out.rate("cmb.aim_center", center, centerJudged);
        out.rate("cmb.blind_hit", blind, s.attacks.size());
        out.rate("cmb.snap", snaps, s.attacks.size());

        // Clicking rhythm: intervals inside streaks (a second's pause ends a streak).
        for (int i = 1; i < s.attacks.size(); i++) {
            long dt = s.attacks.get(i).t - s.attacks.get(i - 1).t;
            if (dt > 0 && dt < 1000) {
                out.sample("cmb.click_cv", dt);
                out.sample("cmb.click_kurt", dt);
            }
        }
    }

    /** Largest single rotation step in the 160 ms before an attack (degrees). */
    static double flickBefore(SessionData s, long t) {
        int hi = MovementFeatures.lowerBound(s.moveT.a, s.moveT.n, t + 1);
        double best = 0;
        float prevYaw = Float.NaN, prevPitch = Float.NaN;
        int lo = MovementFeatures.lowerBound(s.moveT.a, s.moveT.n, t - 400);
        for (int i = lo; i < hi; i++) {
            if ((s.moveFlags.a[i] & MV_LOOK) == 0) continue;
            float y = s.moveYaw.a[i], p = s.movePitch.a[i];
            if (!Float.isNaN(prevYaw) && s.moveT.a[i] >= t - 160) {
                double d = Math.abs(wrap(y - prevYaw)) + Math.abs(p - prevPitch);
                best = Math.max(best, d);
            }
            prevYaw = y;
            prevPitch = p;
        }
        return best;
    }

    /**
     * Rotation stream as a whole: exact repeats of a step and off-grid steps
     * (inside combat windows), yaw wraps and exact 45° headings (anywhere).
     */
    static void rotationShape(SessionData s, FeatureSet out, Prim.Longs tpTimes, double hours) {
        Prim.Longs combat = new Prim.Longs();
        for (SessionData.Attack a : s.attacks) combat.add(a.t);
        float prevYaw = Float.NaN, prevPitch = Float.NaN;
        double prevDy = Double.NaN, prevDp = Double.NaN;
        long pairs = 0, equal = 0, wraps = 0, looks = 0, grid = 0;
        Prim.Doubles combatSteps = new Prim.Doubles();
        for (int i = 0; i < s.moveT.n; i++) {
            if ((s.moveFlags.a[i] & MV_LOOK) == 0) continue;
            float y = s.moveYaw.a[i], p = s.movePitch.a[i];
            long t = s.moveT.a[i];
            boolean nearTp = PacketFeatures.anyIn(tpTimes, t - 1500, t + 200);
            if (!Float.isNaN(prevYaw) && !nearTp) {
                double dy = y - prevYaw, dp = p - prevPitch;
                if (Math.abs(dy) > 300) wraps++;
                if (Math.abs(dy) > 1e-4 || Math.abs(dp) > 1e-4) {
                    looks++;
                    double q = y / 45.0;
                    if (Math.abs(q - Math.rint(q)) < 1e-5 && Math.abs(dy) > 1e-3) grid++;
                }
                boolean inCombat = PacketFeatures.anyIn(combat, t - 1000, t + 3000);
                if (inCombat && Math.abs(dy) < 300) {
                    double mag = Math.abs(dy) + Math.abs(dp);
                    if (mag > 0.3 && !Double.isNaN(prevDy)) {
                        pairs++;
                        if (Math.abs(dy - prevDy) < 1e-3 && Math.abs(dp - prevDp) < 1e-3) equal++;
                    }
                    if (Math.abs(dy) > 0.01 && Math.abs(dy) < 30) combatSteps.add(Math.abs(dy));
                    if (Math.abs(dp) > 0.01 && Math.abs(dp) < 30) combatSteps.add(Math.abs(dp));
                }
                prevDy = dy;
                prevDp = dp;
            }
            prevYaw = y;
            prevPitch = p;
        }
        out.perHour("cmb.yaw_wrap", wraps, hours);
        out.rate("cmb.yaw_grid", grid, looks);
        out.rate("cmb.rot_const", equal, pairs);
        if (combatSteps.n >= 40) {
            double[] steps = combatSteps.toArray();
            double step = MathUtil.rotationGcd(steps);
            if (step > 0) {
                double onGrid = MathUtil.onGridRatio(steps, step);
                out.rate("cmb.gcd_off", (1 - onGrid) * steps.length, steps.length);
            }
        }
    }

    static Prim.Longs teleportTimes(SessionData s) {
        Prim.Longs q = new Prim.Longs();
        for (int i = 0; i < s.tpSentT.n; i++) q.add(s.tpSentT.a[i]);
        for (int i = 0; i < s.lifeT.n; i++) q.add(s.lifeT.a[i]);
        for (int i = 0; i < s.corrT.n; i++) q.add(s.corrT.a[i]);
        java.util.Arrays.sort(q.a, 0, q.n);
        return q;
    }

    static double wrap(double deg) {
        double d = deg % 360.0;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }
}
