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
        boolean tickProtocol = s.protocol < 0 || s.protocol >= 768;
        boolean tickClock = s.tickEndT.n > 200 && tickProtocol;
        double hours = Math.max(1e-6, s.durationMs() / 3_600_000.0);
        Prim.Longs events = quietEvents(s);

        if (tickClock) tickRate(s, out);
        // One episode is one crossing of 350 ms, however long the balance stays down there. Every
        // second used to count: 04.11 a stall left an honest client at -379 ms, and 97 "episodes"
        // in 104 seconds banned it. And the client must really have gained time around that moment
        // — a stall and the burst after it gain nothing, a timer keeps what it took.
        int episodes = 0;
        boolean below = false;
        for (SessionData.State st : s.states) {
            boolean now = st.timerWorst <= -350;
            // Short modern sessions retain their observed clock. Insufficient
            // duration for a rate estimate does not prove accumulated timer gain.
            if (now && !below && (!tickProtocol || gainedTime(s.tickEndT, st.t))) {
                episodes++;
                out.moment("pkt.timer_episodes", st.t);
            }
            below = now;
        }
        out.perHour("pkt.timer_episodes", episodes, hours);
        if (tickClock) extraMoves(s, out);
        // Old clients go quiet whenever they stand still: only a real tick clock can show a held-back stream.
        if (tickClock) blindStalls(s, out, s.tickEndT, events, hours);
        pingGap(s, out);
        phaseLoops(s, out, hours);
    }

    /** Half of the window an episode is checked in: wider than any stall and its burst. */
    private static final long GAIN_WINDOW_MS = 30_000;
    /** Ticks over real time that count as a gain: 300 ms, just under the 350 of an episode. */
    private static final int GAIN_TICKS = 6;

    /** Whether the client sent more ticks than real time allows in the minute around {@code t}. */
    static boolean gainedTime(dev.bastionac.telemetry.Prim.Longs ticks, long t) {
        int count = 0;
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        long boundary = Long.MAX_VALUE;
        for (int i = 0; i < ticks.n; i++) if (ticks.a[i] >= t - GAIN_WINDOW_MS && ticks.a[i] <= t + GAIN_WINDOW_MS) {
            boundary = ticks.a[i] + 1000;
            break;
        }
        for (int i = 0; i < ticks.n; i++) {
            long at = ticks.a[i];
            // Establish an observed clock after the first second. Login queues
            // can be spread over several milliseconds rather than exactly zero.
            if (at < boundary || at > t + GAIN_WINDOW_MS) continue;
            count++;
            first = Math.min(first, at);
            last = Math.max(last, at);
        }
        // The first arrival has no observed preceding interval. All markers in
        // that same TCP cohort may have accumulated before the window or login.
        // Start the clock at its final marker; later bursts retain their wait.
        for (int i = 0; i < ticks.n; i++) if (ticks.a[i] == first) count--;
        count++;
        if (count < 2) return false;
        return count - 1 - (last - first) / 50.0 >= GAIN_TICKS;
    }

    /**
     * Rate over the complete available file history through this window's end.
     * Neither internal sampling boundaries nor the first arriving tick may
     * remove the waiting time that explains a later burst. The reader keeps
     * prefix counts even for a cropped window, bounded by that window's end.
     *
     * A support unit requires ten seconds and 200 recorded tick markers.
     * Repeated aggregate samples encode coverage, not independent estimates; across files MEDIAN
     * weights each file's rate by its available duration. Brief acceleration
     * can be diluted by earlier play, which is the conservative tradeoff.
     */
    static void tickRate(SessionData s, FeatureSet out) {
        if (!s.clockHistoryAvailable || s.clockTicks < 2 || s.clockToMs <= s.startMs) return;
        long span = s.clockToMs - s.startMs;
        if (span < 10_000) return;
        // Subtract the inclusive first endpoint. All later ticks, including
        // the complete initial burst, retain the header-to-arrival wait.
        double rate = (s.clockTicks - 1) * 1000.0 / span;
        // A large empty gap (or an implausibly old numeric header) cannot
        // manufacture support or allocate more samples than the observed data.
        long support = Math.min(span / 10_000, s.clockTicks / 200);
        for (long units = support; units > 0; units--) out.sample("pkt.tick_rate", rate);
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
                out.moment("pkt.extra_moves", t0);
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
            if (answeredPromptly(s, a + 100, b - 50)) {
                count++;
                out.moment("pkt.blind_stall", a);
            }
        }
        out.perHour("pkt.blind_stall", count, hours);
    }

    /**
     * A ping answered in the gap at the client's usual speed: the connection was alive and only the
     * movement stopped. A client whose game froze (a lag spike) answers late, if at all — 04.11 and
     * 07.11 two players' stalls were "alive" by pings that took two seconds to come back.
     */
    static boolean answeredPromptly(SessionData s, long from, long to) {
        if (s.pongRtt.n == 0) return false;
        double[] all = new double[s.pongRtt.n];
        for (int i = 0; i < all.length; i++) all[i] = s.pongRtt.a[i];
        double usual = Stats.median(all);
        int alive=0;
        for (int i = MovementFeatures.lowerBound(s.pongT.a, s.pongT.n, from); i < s.pongT.n && s.pongT.a[i] <= to; i++) {
            // A response sent before this gap may spend its RTT queued together
            // with movement. It cannot establish an active main thread in the gap.
            long sent=s.pongT.a[i]-s.pongRtt.a[i];
            if (sent>=from && s.pongRtt.a[i] <= usual * 1.5 + 100 && ++alive>=2) return true;
        }
        return false;
    }

    /** Keep-alive round trip minus our own pings around it. */
    /**
     * How much later each keep-alive came back than the pings sent after it. A faked ping holds the
     * keep-alive alone, so pings sent after it overtake it; a stalled connection delivers them all in
     * one burst, the keep-alive no later than the pings behind it. Round trips alone said nothing: a
     * keep-alive goes out every 15 s, a ping every 2 s, and in the same stall the older packet simply
     * waited longer (08.11: a ban for "954 ms of faked ping", and 350 ms on another jittery player).
     */
    static void pingGap(SessionData s, FeatureSet out) {
        for (int i = 0; i < s.keepAliveT.n; i++) {
            long arrived = s.keepAliveT.a[i];
            long sent = arrived - s.keepAliveRtt.a[i];
            long overtaken = Long.MAX_VALUE;
            boolean sameBurst = false;
            int lo = MovementFeatures.lowerBound(s.pongT.a, s.pongT.n, sent);
            for (int j = lo; j < s.pongT.n && s.pongT.a[j] <= arrived + 50; j++) {
                long pongSent = s.pongT.a[j] - s.pongRtt.a[j];
                if (pongSent <= sent) continue;
                if (s.pongT.a[j] < arrived - 50) overtaken = Math.min(overtaken, s.pongT.a[j]);
                else sameBurst = true;
            }
            // A ping sent after it came back first: it was held back by that much.
            if (overtaken != Long.MAX_VALUE) {
                out.sample("pkt.ping_gap", arrived - overtaken);
                continue;
            }
            // It came in with a ping sent after it: the connection held them all.
            if (sameBurst) {
                out.sample("pkt.ping_gap", 0);
                continue;
            }
            // Nothing sent after it came back first: against the pings around it, as before.
            int a0 = MovementFeatures.lowerBound(s.pongT.a, s.pongT.n, arrived - 10_000);
            int cnt = 0;
            double[] near = new double[Math.max(0, Math.min(s.pongT.n - a0, 32))];
            for (int j = a0; j < s.pongT.n && s.pongT.a[j] <= arrived + 10_000 && cnt < near.length; j++) near[cnt++] = s.pongRtt.a[j];
            if (cnt < 2) continue;
            out.sample("pkt.ping_gap", s.keepAliveRtt.a[i] - Stats.median(java.util.Arrays.copyOf(near, cnt)));
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
            if (after >= 6) {
                episodes++;
                out.moment("pkt.phase_loops", t);
            }
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
