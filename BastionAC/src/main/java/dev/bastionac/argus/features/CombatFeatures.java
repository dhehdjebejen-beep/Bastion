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

    /** Hits above this ping (ms) do not count towards the reach statistic. */
    static final int REACH_MAX_PING = 150;

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
            // Reach is measured against the target's box as the server has it
            // now; at a high ping the player legitimately hit where it was a
            // few ticks ago. Those hits are set aside instead of excusing every
            // hit by a ping allowance (the realtime check does the lag
            // compensation with the hitbox history).
            if (a.has(dev.bastionac.telemetry.SessionWriter.Attack.F_PLAYER) && a.ping <= REACH_MAX_PING) {
                out.sample("cmb.reach", a.distance);
            }
            if (flickBefore(s, a.t) > 25.0) snaps++;
        }
        out.rate("cmb.aim_center", center, centerJudged);
        out.rate("cmb.blind_hit", blind, s.attacks.size());
        out.rate("cmb.snap", snaps, s.attacks.size());

        // Clicking rhythm: intervals inside streaks (a second's pause ends a
        // streak) and inside the rhythm itself. A pause of 0.8 s between two
        // flurries used to land in the sample too, and one such pause in thirty
        // clicks drowned the evenness of an autoclicker completely (3.3).
        Prim.Doubles gaps = new Prim.Doubles();
        for (int i = 1; i < s.attacks.size(); i++) {
            long dt = s.attacks.get(i).t - s.attacks.get(i - 1).t;
            if (dt > 0 && dt < 1000) gaps.add(dt);
        }
        if (gaps.n >= 4) {
            double cut = RHYTHM_CUT * Stats.median(gaps.toArray());
            for (int i = 0; i < gaps.n; i++) {
                if (gaps.a[i] > cut) continue;
                out.sample("cmb.click_rhythm", gaps.a[i]);
                out.sample("cmb.rhythm_kurt", gaps.a[i]);
            }
        }
        reachOver(s, out);
    }

    /** A click gap longer than this many median gaps is a pause, not the rhythm. */
    static final double RHYTHM_CUT = 2.5;
    /** Past this distance (plus how far the target could have backed off) a vanilla hand cannot have hit. */
    static final double REACH_LINE = 3.1;

    /**
     * Share of hits on players past {@link #REACH_LINE}. The 95th percentile
     * rests on its last few hits; this counts every one of them, so a steady
     * +0.4 blocks shows after thirty hits instead of three hundred (3.3).
     *
     * <p>Every ping counts. The distance is measured to where the target is on
     * the server; the player swung at where it was half a round trip plus the
     * client's interpolation earlier — so the allowance is how far the target
     * moved <em>away</em> from the player in that time (how fast the gap was
     * opening, read from the target's track). A target walking sideways or
     * towards the player gives no allowance at any ping; one sprinting away
     * gives a lot. Setting every high-ping hit aside, as the percentile does,
     * let a lagging (or fake-lagging) client reach as far as it liked. The
     * first hit of a fight has no track yet and is left out.
     */
    static void reachOver(SessionData s, FeatureSet out) {
        long over = 0, judged = 0;
        for (SessionData.Attack a : s.attacks) {
            if (!a.has(dev.bastionac.telemetry.SessionWriter.Attack.F_PLAYER)) continue;
            double away = recession(s, a);
            // No track of the target yet (the first hit of a fight): how fast it
            // was backing off is unknown, and a target running off can measure
            // far past 3 blocks at any ping — that hit is not judged. In a
            // fight that lasts, every later hit has its track.
            if (Double.isNaN(away)) continue;
            judged++;
            if (a.distance > reachAllowance(away, a.ping)) over++;
        }
        out.rate("cmb.reach_over", over, judged);
    }

    /** The farthest an honest hit could measure, given the ping and how fast the target backed off (blocks/tick). */
    static double reachAllowance(double away, int ping) {
        double ticks = (Math.max(0, ping) / 2.0 + INTERP_MS) / 50.0;
        return REACH_LINE + Math.max(0, away) * ticks + Math.max(0, ping) * 0.0005;
    }

    /**
     * How fast the target was moving away from the attacker just before the
     * hit, in blocks per tick, from the per-tick track the server keeps of
     * every recently hit target (the target's own velocity is useless for
     * players: the server never simulates them). The track is relative to the
     * attacker's eyes, so the attacker backing off counts too — that only
     * widens the allowance. NaN when there is no track of this target yet.
     */
    static double recession(SessionData s, SessionData.Attack a) {
        int hi = MovementFeatures.lowerBound(s.targetT.a, s.targetT.n, a.t + 1) - 1;
        int last = -1;
        double best = Double.NaN;
        for (int i = hi; i >= 0 && s.targetT.a[i] >= a.t - 400; i--) {
            if (s.targetId.a[i] != a.targetId) continue;
            if (last < 0) {
                last = i;
                continue;
            }
            long dt = s.targetT.a[last] - s.targetT.a[i];
            if (dt < 40) continue;
            double r1 = len(s, last), r0 = len(s, i);
            double rate = (r1 - r0) / (dt / 50.0);
            best = Double.isNaN(best) ? rate : Math.max(best, rate);
            last = i;
        }
        return best;
    }

    private static double len(SessionData s, int i) {
        double x = s.targetDX.a[i], y = s.targetDY.a[i], z = s.targetDZ.a[i];
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** What the client's entity interpolation adds to the age of the target it sees. */
    static final double INTERP_MS = 100;

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
            // No common grid at all is the strongest form of the signal, not
            // a reason to skip it (bestGridFit, 3.1).
            double onGrid = MathUtil.bestGridFit(steps).ratio();
            if (!Double.isNaN(onGrid)) out.rate("cmb.gcd_off", (1 - onGrid) * steps.length, steps.length);
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
