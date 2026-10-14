package dev.bastionac.argus.features;

import dev.bastionac.core.MathUtil;
import dev.bastionac.telemetry.Prim;
import dev.bastionac.telemetry.SessionData;

import static dev.bastionac.telemetry.TelemetryFormat.MV_LOOK;
import static dev.bastionac.telemetry.TelemetryFormat.MV_POS;

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

        long center = 0, centerJudged = 0, snaps = 0;
        for (SessionData.Attack a : s.attacks) {
            if (a.distance > 1.5f) {
                centerJudged++;
                if (a.aimError < 1.2f) center++;
            }
            if (a.distance > 1.0f) out.sample("cmb.aim_error", a.aimError);
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
        lagCompensatedAim(s, out);
        trackLag(s, out);
    }

    /**
     * How far the hit missed the target <em>as the player saw it</em>: the
     * server measures against where the target is now, but the player aimed
     * at where it was a round trip plus the interpolation earlier — and on a
     * 100 ms ping a moving target has gone several degrees by then. That
     * made a KillAura on an ordinary ping miss "like a human" by the plain
     * aim error (3.3, found in Argus Lab). Here the error is the smallest
     * against the target's track over the window the player could have seen
     * it in (a round trip plus the interpolation ago, give or take a tick): a
     * module that aims at the target it sees lands at ≈ 0°.
     */
    static void lagCompensatedAim(SessionData s, FeatureSet out) {
        long centre = 0, judged = 0, blind = 0, blindJudged = 0;
        for (SessionData.Attack a : s.attacks) {
            if (a.distance <= 1.5f) continue;
            double lc = lagCompensatedError(s, a);
            // "Behind the back" against where the player saw the target. On a
            // high ping a target can run past the player's shoulder before
            // the hit reaches the server: without its track only a low-ping
            // hit is judged (3.3, found in Argus Lab).
            if (!Double.isNaN(lc) || a.ping <= REACH_MAX_PING) {
                blindJudged++;
                if ((Double.isNaN(lc) ? a.aimError : lc) > 60) blind++;
            }
            if (Double.isNaN(lc)) continue;   // no track yet: the first hit of a fight
            judged++;
            if (lc < 1.0) centre++;
            out.sample("cmb.aim_lc_error", lc);
        }
        out.rate("cmb.aim_lc_center", centre, judged);
        out.rate("cmb.blind_hit", blind, blindJudged);
    }

    /** The smallest aim error against where the player could have seen the target, or NaN without a track. */
    static double lagCompensatedError(SessionData s, SessionData.Attack a) {
        int now = lastPos(s, a.t);
        if (now < 0) return Double.NaN;
        // The eyes' offset from the reported position (standing or sneaking).
        double ex = a.eyeX - s.moveX.a[now], ey = a.eyeY - s.moveY.a[now], ez = a.eyeZ - s.moveZ.a[now];
        // A client turns before it moves in a tick: the aim may have been
        // taken from the position one move earlier (a step at 3 blocks is
        // several degrees of parallax).
        int before = lastPos(s, s.moveT.a[now] - 1);
        int[] eyes = before < 0 ? new int[]{now} : new int[]{now, before};
        int ping = Math.max(0, a.ping);
        long seen = a.t - ping - (long) INTERP_MS;
        long from = seen - LC_SLACK_MS, to = Math.min(a.t, seen + LC_SLACK_MS);
        double yr = Math.toRadians(a.yaw), pr = Math.toRadians(a.pitch);
        double lx = -Math.sin(yr) * Math.cos(pr), ly = -Math.sin(pr), lz = Math.cos(yr) * Math.cos(pr);
        double best = Double.NaN;
        for (int i = MovementFeatures.lowerBound(s.targetT.a, s.targetT.n, from);
             i < s.targetT.n && s.targetT.a[i] <= to; i++) {
            if (s.targetId.a[i] != a.targetId) continue;
            int then = lastPos(s, s.targetT.a[i]);
            if (then < 0) continue;
            // Where the target was, in the world; looked at from where the eyes are.
            double wx = s.moveX.a[then] + ex + s.targetDX.a[i];
            double wy = s.moveY.a[then] + ey + s.targetDY.a[i];
            double wz = s.moveZ.a[then] + ez + s.targetDZ.a[i];
            for (int e : eyes) {
                double dx = wx - (s.moveX.a[e] + ex), dy = wy - (s.moveY.a[e] + ey), dz = wz - (s.moveZ.a[e] + ez);
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-6) continue;
                double cos = (lx * dx + ly * dy + lz * dz) / len;
                double err = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
                best = Double.isNaN(best) ? err : Math.min(best, err);
            }
        }
        return best;
    }

    /** Give or take this much around "a round trip plus the interpolation ago". */
    static final long LC_SLACK_MS = 60;

    /** The stretch of play before a hit one reaction value is read from. */
    static final long LAG_WINDOW_MS = 2500;

    /**
     * Reaction time of the aim. In the 2.5 seconds before a hit, the
     * target's turning (from its track — its own moves only, see below) and
     * the player's turning are two series; their changes (a jink of the
     * target is a spike, and so is the answer to it) are correlated at every
     * lag, counted from "a round trip plus the interpolation" — the moment
     * the player could first have seen the jink. A hand needs 150–300 ms (3–6
     * ticks) for a jink it could not foresee; an aim module — a full aura, a
     * smooth one, an aim assist — answers the target it sees at once.
     *
     * <p>One window alone is too short (its correlations scatter by ±0.15), so
     * {@link #POOL} windows are pooled into one profile; each profile gives a
     * reaction lag (the best lag, {@code cmb.track_lag}) and the strength of
     * the answer with no reaction at all ({@code cmb.track_instant}) (3.3).
     */
    static void trackLag(SessionData s, FeatureSet out) {
        long next = Long.MIN_VALUE;
        Prim.Ints idx = new Prim.Ints();
        Profile prof = new Profile();
        for (SessionData.Attack a : s.attacks) {
            if (a.t < next) continue;
            idx.n = 0;
            for (int i = MovementFeatures.lowerBound(s.targetT.a, s.targetT.n, a.t - LAG_WINDOW_MS);
                 i < s.targetT.n && s.targetT.a[i] <= a.t; i++) {
                if (s.targetId.a[i] == a.targetId) idx.add(i);
            }
            int n = idx.n;
            if (n < 35) continue;
            long span = s.targetT.a[idx.a[n - 1]] - s.targetT.a[idx.a[0]];
            if (span > (n - 1) * 70L) continue;   // gaps in the track: not a tick-by-tick series
            // The bearing changes the target's own motion made, seen from where
            // the player stood at each tick. The player's own strafing turns the
            // bearing too, but a player knows where it is going and turns for
            // that without any reaction time — counted in, it would make every
            // strafing hand look like a module.
            double[] dt = new double[n - 1], own = new double[n];
            boolean ok = true;
            double prevX = 0, prevZ = 0;
            for (int k = 0; k < n; k++) {
                int i = idx.a[k];
                int m = lastLook(s, s.targetT.a[i]), p = lastPos(s, s.targetT.a[i]);
                if (m < 0 || p < 0) {
                    ok = false;
                    break;
                }
                own[k] = s.moveYaw.a[m];
                double px = s.moveX.a[p], pz = s.moveZ.a[p];
                double tx = px + s.targetDX.a[i], tz = pz + s.targetDZ.a[i];
                if (k > 0) dt[k - 1] = wrap(yawTo(px, pz, tx, tz) - yawTo(px, pz, prevX, prevZ));
                prevX = tx;
                prevZ = tz;
            }
            if (!ok) continue;
            unwrap(own);
            double expect = (Math.max(0, a.ping) + INTERP_MS) / 50.0;
            prof.add(diff(dt), diff(diff(own)), (int) Math.round(expect), expect);
            next = a.t + LAG_WINDOW_MS;
            if (prof.windows >= POOL) prof.emit(out);
        }
        if (prof.windows >= POOL / 2) prof.emit(out);
    }

    /** Windows pooled into one reaction profile. */
    static final int POOL = 6;
    /** Lags looked at, counted from the first moment the player could have seen the target's move. */
    static final int REL_MIN = -2, REL_MAX = 14;

    /** Correlation sums of the target's and the player's turning changes, per lag, over several windows. */
    static final class Profile {
        final double[] sxy = new double[REL_MAX - REL_MIN + 1];
        final double[] sxx = new double[sxy.length], syy = new double[sxy.length];
        int windows;
        double offset;

        void add(double[] at, double[] aw, int l0, double expect) {
            for (int r = REL_MIN; r <= REL_MAX; r++) {
                int lag = l0 + r;
                if (lag < 0) continue;
                int n = Math.min(at.length, aw.length - lag);
                if (n < 25) continue;
                double ma = 0, mb = 0;
                for (int k = 0; k < n; k++) {
                    ma += at[k];
                    mb += aw[k + lag];
                }
                ma /= n;
                mb /= n;
                int j = r - REL_MIN;
                for (int k = 0; k < n; k++) {
                    double x = at[k] - ma, y = aw[k + lag] - mb;
                    sxy[j] += x * y;
                    sxx[j] += x * x;
                    syy[j] += y * y;
                }
            }
            offset += l0 - expect;
            windows++;
        }

        void emit(FeatureSet out) {
            int best = -1;
            double bestCorr = -1, instant = 0;
            for (int j = 0; j < sxy.length; j++) {
                if (sxx[j] <= 1e-12 || syy[j] <= 1e-12) continue;
                double c = sxy[j] / Math.sqrt(sxx[j] * syy[j]);
                if (c > bestCorr) {
                    bestCorr = c;
                    best = j;
                }
                int r = j + REL_MIN;
                if (r >= -1 && r <= 1) instant = Math.max(instant, c);
            }
            if (best >= 0 && bestCorr >= 0.15) {
                out.sample("cmb.track_lag", best + REL_MIN + offset / windows);
                out.sample("cmb.track_instant", instant);
            }
            java.util.Arrays.fill(sxy, 0);
            java.util.Arrays.fill(sxx, 0);
            java.util.Arrays.fill(syy, 0);
            windows = 0;
            offset = 0;
        }
    }

    /** The last move with a position at or before {@code t}, or −1. */
    static int lastPos(SessionData s, long t) {
        int i = MovementFeatures.lowerBound(s.moveT.a, s.moveT.n, t + 1) - 1;
        for (int k = 0; k < 10 && i >= 0; k++, i--) {
            if ((s.moveFlags.a[i] & MV_POS) != 0) return i;
        }
        return -1;
    }

    /** The yaw that looks from (x, z) at (tx, tz), in degrees. */
    static double yawTo(double x, double z, double tx, double tz) {
        return Math.toDegrees(Math.atan2(-(tx - x), tz - z));
    }

    /** The last move with a rotation at or before {@code t}, or −1. */
    static int lastLook(SessionData s, long t) {
        int i = MovementFeatures.lowerBound(s.moveT.a, s.moveT.n, t + 1) - 1;
        for (int k = 0; k < 10 && i >= 0; k++, i--) {
            if ((s.moveFlags.a[i] & MV_LOOK) != 0) return i;
        }
        return -1;
    }

    static void unwrap(double[] v) {
        for (int i = 1; i < v.length; i++) v[i] = v[i - 1] + wrap(v[i] - v[i - 1]);
    }

    static double[] diff(double[] v) {
        double[] d = new double[Math.max(0, v.length - 1)];
        for (int i = 0; i < d.length; i++) d[i] = v[i + 1] - v[i];
        return d;
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
     * the server; the player swung at where it was a whole round trip plus the
     * client's interpolation earlier (the target's state took half a trip to
     * reach the client, the swing half a trip to come back) — so the
     * allowance is how far the target moved <em>away</em> from the player in
     * that time (how much the gap opened, read from the target's track).
     * A target walking sideways or towards the player gives no allowance at
     * any ping; one sprinting away gives a lot. Setting every high-ping hit aside, as the percentile does,
     * let a lagging (or fake-lagging) client reach as far as it liked. The
     * first hit of a fight has no track yet and is left out.
     */
    static void reachOver(SessionData s, FeatureSet out) {
        long over = 0, judged = 0;
        for (SessionData.Attack a : s.attacks) {
            if (!a.has(dev.bastionac.telemetry.SessionWriter.Attack.F_PLAYER)) continue;
            double opened = opened(s, a);
            // No track of the target yet (the first hit of a fight): how far it
            // was backing off is unknown, and a target running off can measure
            // far past 3 blocks at any ping — that hit is not judged. In a
            // fight that lasts, every later hit has its track.
            if (Double.isNaN(opened)) continue;
            judged++;
            if (a.distance > REACH_LINE + Math.max(0, opened) + Math.max(0, a.ping) * 0.0005) over++;
            // The distance the swing was made at, as well as the server can
            // tell: now, less what the target backed off since it was seen.
            out.sample("cmb.reach_lc", a.distance - Math.max(0, opened));
        }
        out.rate("cmb.reach_over", over, judged);
    }

    /**
     * How much farther the target was at the hit than when the player could
     * have seen it (a round trip plus the interpolation earlier, give or take
     * {@link #LC_SLACK_MS}; the most of it): what an honest swing may measure
     * past the 3 blocks it was made at. From the server's per-tick track of
     * every recently hit target — the target's own velocity is useless for
     * players, the server never simulates them. The track is relative to the
     * attacker's eyes, so the attacker backing off counts too, which only
     * widens the allowance. NaN when there is no track of this target.
     */
    static double opened(SessionData s, SessionData.Attack a) {
        int last = -1;
        for (int i = MovementFeatures.lowerBound(s.targetT.a, s.targetT.n, a.t + 1) - 1;
             i >= 0 && s.targetT.a[i] >= a.t - 200; i--) {
            if (s.targetId.a[i] == a.targetId) {
                last = i;
                break;
            }
        }
        if (last < 0) return Double.NaN;
        long seen = a.t - Math.max(0, a.ping) - (long) INTERP_MS;
        double nearest = Double.NaN;
        for (int i = MovementFeatures.lowerBound(s.targetT.a, s.targetT.n, seen - LC_SLACK_MS);
             i < s.targetT.n && s.targetT.a[i] <= seen + LC_SLACK_MS; i++) {
            if (s.targetId.a[i] != a.targetId) continue;
            double r = len(s, i);
            nearest = Double.isNaN(nearest) ? r : Math.min(nearest, r);
        }
        return Double.isNaN(nearest) ? Double.NaN : len(s, last) - nearest;
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
