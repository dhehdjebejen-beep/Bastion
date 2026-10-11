package dev.bastionac.argus.features;

import dev.bastionac.telemetry.Prim;
import dev.bastionac.telemetry.SessionData;

import static dev.bastionac.telemetry.TelemetryFormat.*;

/**
 * Timing and protocol shape: how fast the client ticks, whether it ever
 * packs two moves into one tick, whether it stops moving while its
 * connection is plainly alive (Blink), and whether its keep-alives are
 * slower than everything else it answers (a faked ping).
 */
public final class PacketFeatures {

    private PacketFeatures() {}

    public static void analyze(SessionData s, FeatureSet out) {
        boolean tickClock = s.tickEndT.n > 200 && (s.protocol < 0 || s.protocol >= 768);
        double hours = Math.max(1e-6, s.durationMs() / 3_600_000.0);
        Prim.Longs events = quietEvents(s);

        if (tickClock) tickRate(s, out);
        // A second in which the client overdrew its lag credit by 350 ms or more.
        // With a 3-second credit an honest connection never gets there; a timer does it again and again.
        int episodes = 0;
        for (SessionData.State st : s.states) if (st.timerWorst <= -350) episodes++;
        out.perHour("pkt.timer_episodes", episodes, hours);
        if (tickClock) extraMoves(s, out);
        // Old clients go quiet whenever they stand still: only a real tick clock can show a held-back stream.
        if (tickClock) blindStalls(s, out, s.tickEndT, events, hours);
        pingGap(s, out);
        phaseLoops(s, out, hours);
    }

    /** Every 10-second stretch without a pause: ticks per second. A vanilla client is locked to 20. */
    static void tickRate(SessionData s, FeatureSet out) {
        long[] t = s.tickEndT.a;
        int n = s.tickEndT.n;
        int start = 0;
        for (int i = 1; i < n; i++) {
            if (t[i] - t[i - 1] > 1000) {   // a pause ends the stretch
                start = i;
                continue;
            }
            if (t[i] - t[start] >= 10_000) {
                double rate = (i - start) * 1000.0 / (t[i] - t[start]);
                out.sample("pkt.tick_rate", rate);
                start = i;
            }
        }
    }

    /** Ticks with two or more positions and no teleport confirm to explain the extra one. */
    static void extraMoves(SessionData s, FeatureSet out) {
        int n = s.moveT.n;
        if (n == 0) return;
        long ticks = 0, extra = 0;
        int i = 0;
        while (i < n) {
            int tick = s.moveTick.a[i];
            int pos = 0;
            long t0 = s.moveT.a[i], t1 = t0;
            while (i < n && s.moveTick.a[i] == tick) {
                if ((s.moveFlags.a[i] & MV_POS) != 0) pos++;
                t1 = s.moveT.a[i];
                i++;
            }
            ticks++;
            if (pos >= 2 && !anyIn(s.tpConfT, t0 - 120, t1 + 120) && !anyIn(s.tpSentT, t0 - 2000, t1 + 50)) {
                extra++;
            }
        }
        out.rate("pkt.extra_moves", extra, ticks);
    }

    /**
     * The client stopped ticking for over 0.6 s while our pings kept being
     * answered — and a ping is answered on the client's main thread, the
     * same thread that ticks. A frozen game cannot answer; a dead network
     * cannot deliver. Only a client that holds its movement back can do both.
     */
    static void blindStalls(SessionData s, FeatureSet out, Prim.Longs clock, Prim.Longs quiet, double hours) {
        long[] t = clock.a;
        int count = 0;
        for (int i = 1; i < clock.n; i++) {
            long a = t[i - 1], b = t[i];
            if (b - a < 600) continue;
            if (anyIn(quiet, a - 10_000, b + 500)) continue;   // respawn, teleport, world change: loading screens
            if (anyIn(s.pongT, a + 100, b - 50)) count++;
        }
        out.perHour("pkt.blind_stall", count, hours);
    }

    /** Keep-alive round trip minus our own pings around it. */
    static void pingGap(SessionData s, FeatureSet out) {
        for (int i = 0; i < s.keepAliveT.n; i++) {
            long t = s.keepAliveT.a[i];
            int lo = MovementFeatures.lowerBound(s.pongT.a, s.pongT.n, t - 10_000);
            int cnt = 0;
            double[] near = new double[Math.max(0, Math.min(s.pongT.n - lo, 32))];
            for (int j = lo; j < s.pongT.n && s.pongT.a[j] <= t + 10_000 && cnt < near.length; j++) {
                near[cnt++] = s.pongRtt.a[j];
            }
            if (cnt < 2) continue;
            double[] v = java.util.Arrays.copyOf(near, cnt);
            out.sample("pkt.ping_gap", s.keepAliveRtt.a[i] - Stats.median(v));
        }
    }

    /** A block re-send followed by six more refusals into a block: the client walks through blocks it knows. */
    static void phaseLoops(SessionData s, FeatureSet out, double hours) {
        int episodes = 0;
        for (int i = 0; i < s.actT.n; i++) {
            if (s.actKind.a[i] != ACT_RESYNC) continue;
            long t = s.actT.a[i];
            int after = 0;
            for (int j = MovementFeatures.lowerBound(s.corrT.a, s.corrT.n, t + 300);
                 j < s.corrT.n && s.corrT.a[j] <= t + 6000; j++) {
                if (s.corrReason.a[j] == CORR_COLLIDE) after++;
            }
            if (after >= 6) episodes++;
        }
        out.perHour("pkt.phase_loops", episodes, hours);
    }

    /** Times around which a pause is the game's own doing (loading screens). */
    static Prim.Longs quietEvents(SessionData s) {
        Prim.Longs q = new Prim.Longs();
        for (int i = 0; i < s.lifeT.n; i++) q.add(s.lifeT.a[i]);
        for (int i = 0; i < s.tpSentT.n; i++) q.add(s.tpSentT.a[i]);
        java.util.Arrays.sort(q.a, 0, q.n);
        return q;
    }

    static Prim.Longs positionTimes(SessionData s) {
        Prim.Longs p = new Prim.Longs();
        for (int i = 0; i < s.moveT.n; i++) p.add(s.moveT.a[i]);
        return p;
    }

    static boolean anyIn(Prim.Longs sorted, long from, long to) {
        int i = MovementFeatures.lowerBound(sorted.a, sorted.n, from);
        return i < sorted.n && sorted.a[i] <= to;
    }
}
