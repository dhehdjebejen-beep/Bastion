package dev.bastionac.argus.features;

import dev.bastionac.telemetry.SessionData;

import static dev.bastionac.telemetry.TelemetryFormat.*;

/** The remaining motion of a recorded server impulse, in client ticks, not arrival time. */
public final class ServerImpulse {
    private ServerImpulse() {}

    /**
     * Keep the existing network grace, then follow the recorded impulse until the first landing
     * or vanilla drag has extinguished it. Tick indices also work when a connection releases a
     * whole burst at once. A server teleport/life transition ends the old trajectory.
     * No impulse is inferred from a player's position: that would forgive a speed cheat itself.
     */
    public static boolean[] excluded(SessionData s) {
        int n = s.moveT.n;
        boolean[] result = new boolean[n];
        boolean tickMode = s.tickEndT.n > 20 && (s.protocol < 0 || s.protocol >= 768);
        // EntityVelocityUpdate replaces the client's velocity; an explosion adds to it.
        // A later absolute update therefore ends every earlier trajectory, including a
        // cancelled upward impulse whose airborne drag would otherwise last for seconds.
        long[] replacedAt = new long[s.velT.n];
        long nextAbsolute = Long.MAX_VALUE;
        for (int v = s.velT.n - 1; v >= 0; v--) {
            replacedAt[v] = nextAbsolute;
            if (s.velExplosion.a[v] == 0) nextAbsolute = s.velT.a[v];
        }
        for (int v = 0; v < s.velT.n; v++) {
            long sent = s.velT.a[v];
            long grace = s.velExplosion.a[v] == 0 ? 1500 : 3500;
            int first = MovementFeatures.lowerBound(s.moveT.a, n, sent - 100);
            int state = 0;
            while (state + 1 < s.states.size() && s.states.get(state + 1).t <= sent) state++;
            int ping = s.states.isEmpty() ? 0 : Math.max(0, s.states.get(state).ping);
            // The return of the changed position takes approximately one RTT. While waiting,
            // grounded packets already in flight cannot count as the impulse's landing.
            long arrival = sent + Math.min(1500, ping);
            double horizontal = Math.hypot(s.velX.a[v], s.velZ.a[v]);
            double vertical = Math.max(0, s.velY.a[v]);
            boolean launched = false, ended = false;
            int lastTick = -1;
            int teleport = MovementFeatures.lowerBound(s.tpSentT.a, s.tpSentT.n, sent + 1);
            int life = MovementFeatures.lowerBound(s.lifeT.a, s.lifeT.n, sent + 1);
            long reset = Math.min(teleport < s.tpSentT.n ? s.tpSentT.a[teleport] : Long.MAX_VALUE,
                    life < s.lifeT.n ? s.lifeT.a[life] : Long.MAX_VALUE);
            reset = Math.min(reset, replacedAt[v]);
            for (int i = first; i < n; i++) {
                long t = s.moveT.a[i];
                boolean graceApplies = t <= sent + grace;
                if (t >= reset) ended = true;
                if (t < arrival) {
                    if (graceApplies) result[i] = true;
                    continue;
                }
                int flags = s.moveFlags.a[i];
                boolean ground = (flags & MV_GROUND) != 0;
                if (!ended && (flags & MV_POS) != 0) {
                    int steps = tickMode ? (lastTick < 0 ? 0 : Math.max(0, s.moveTick.a[i] - lastTick)) : 1;
                    lastTick = s.moveTick.a[i];
                    // Air drag is the conservative bound while in flight. On ice the footprint
                    // bit is safer than the centre's ground block, which can be air at an edge.
                    double drag = ground ? ((s.moveEnv.a[i] & ENV_ICE) != 0 ? 0.989 : 0.6) * 0.91 : 0.91;
                    horizontal *= Math.pow(drag, steps);
                    vertical *= Math.pow(0.98, steps);
                    if (!ground) launched = true;
                    if ((ground && (launched || !graceApplies))
                            || (horizontal < 0.003 && vertical < 0.003)) ended = true;
                }
                if (graceApplies || !ended) result[i] = true;
                else break;
            }
        }
        return result;
    }
}
