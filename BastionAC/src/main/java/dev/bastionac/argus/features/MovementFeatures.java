package dev.bastionac.argus.features;

import dev.bastionac.telemetry.SessionData;

import static dev.bastionac.telemetry.TelemetryFormat.*;

/**
 * Physics replay: every client tick held against vanilla's own movement
 * formulas (LivingEntity.travelMidAir, 1.21.11), using only what the
 * telemetry recorded — positions, ground flags, held keys, the friction
 * block and the speed attribute.
 *
 * <pre>
 *   Δ(t)   = V(t−1) + a(t)                      the move of tick t
 *   V(t−1) = Δ(t−1) · f                         f = slipperiness·0.91 on ground, 0.91 in air
 *   |a(t)| ≤ speed·0.216/slip³  (ground)         0.026 in air, +0.2 on a sprint-jump tick
 *   Δy(t)  = (Δy(t−1) − 0.08) · 0.98             in free air
 * </pre>
 *
 * Nothing here needs the world's collision shapes: collisions only ever take
 * speed away, so the checks are one-sided (a gain beyond the bound, a rise
 * above the gravity curve) and every tick where something legitimately adds
 * motion — knockback, liquids, ladders, slime, vehicles, elytra, teleports,
 * other entities pushing — is left out instead of modelled.
 *
 * <p>On 1.21.2+ clients the keys held are known (PlayerInput), which gives
 * the cleanest facts of all: a vanilla client does not jump without the jump
 * key (auto-jump presses it too) and does not gain speed with no key held.
 */
public final class MovementFeatures {

    /** Friction of the block a player stands on, by block-state raw id. */
    public interface BlockInfo {
        float slipperiness(int stateRawId);
    }

    /** Environment bits that make a tick unjudgeable. */
    static final int EXCLUDE = ENV_LIQUID | ENV_CLIMBABLE | ENV_WEB | ENV_POWDER | ENV_BUBBLE | ENV_SLIME | ENV_BED
            | ENV_HONEY_OR_SOUL | ENV_SCAFFOLDING | ENV_BERRY | ENV_GLIDING | ENV_VEHICLE | ENV_RIPTIDE | ENV_FLYING
            | ENV_LEVITATION | ENV_PUSHABLE_NEAR | ENV_ENTITY_SUPPORT;
    /** Vertical checks additionally need normal gravity. */
    static final int EXCLUDE_VERTICAL = EXCLUDE | ENV_SLOW_FALLING;

    static final double AIR_ACCEL = 0.026;     // 0.02, ×1.3 sprinting
    static final double SPRINT_JUMP = 0.2;
    static final double GRAVITY = 0.08;
    static final double TOL_GAIN = 0.0035;      // the 0.003 velocity zeroing plus float noise
    static final double TOL_TURN = 0.006;
    static final double TOL_VERT = 0.0105;
    static final int INPUT_MOVE = IN_FORWARD | IN_BACK | IN_LEFT | IN_RIGHT;

    /** Diagnostics: when set, every violation is described here (offline analysis, tests). */
    public static volatile java.util.function.Consumer<String> debug;

    private MovementFeatures() {}

    private static void dbg(Ticks tk, int c, String what, double v) {
        var d = debug;
        if (d != null) {
            d.accept(String.format(java.util.Locale.ROOT, "%d %s %.4f | y %.4f %.4f %.4f | g %b %b %b | env %x",
                    tk.t[c], what, v, tk.y[c - 2], tk.y[c - 1], tk.y[c], tk.ground[c - 2], tk.ground[c - 1], tk.ground[c], tk.env[c]));
        }
    }

    /** One reconstructed client tick. */
    private static final class Ticks {
        int n;
        double[] x, y, z;
        boolean[] ground, hcoll, valid;
        int[] env, input, groundBlock;
        float[] speed, yaw;
        long[] t;

        Ticks(int cap) {
            x = new double[cap];
            y = new double[cap];
            z = new double[cap];
            ground = new boolean[cap];
            hcoll = new boolean[cap];
            valid = new boolean[cap];
            env = new int[cap];
            input = new int[cap];
            groundBlock = new int[cap];
            speed = new float[cap];
            yaw = new float[cap];
            t = new long[cap];
        }
    }

    public static void analyze(SessionData s, FeatureSet out, BlockInfo blocks) {
        int n = s.moveT.n;
        if (n < 10) return;
        boolean[] bad = excluded(s);
        boolean tickMode = s.tickEndT.n > 20 && (s.protocol < 0 || s.protocol >= 768);
        Ticks tk = tickMode ? byClientTick(s, bad) : byPacket(s, bad);
        judge(s, tk, out, blocks);
        teleports(s, out);
    }

    /** One position packet carrying a player on foot farther sideways than this (× speed attribute / sprint) is a teleport. */
    static final double TELEPORT_H = 2.0;

    /**
     * Teleports on foot — ClickTP and friends (3.4): one position packet more
     * than {@link #TELEPORT_H} blocks sideways from the previous one, with
     * nothing around it that moves a player (velocity, an explosion, a vehicle,
     * elytra, riptide, flight, creative, a respawn). The server's own
     * teleports only excuse what comes after them: the physics replay sets
     * aside the moves just before a setback too, and that hid exactly the
     * jump that earned the setback.
     */
    static void teleports(SessionData s, FeatureSet out) {
        int n = s.moveT.n;
        long[] t = s.moveT.a;
        boolean[] skip = new boolean[n];
        markAround(skip, t, n, s.velT, 100, 1500);
        for (int i = 0; i < s.velT.n; i++) {
            if (s.velExplosion.a[i] != 0) markRange(skip, t, n, s.velT.a[i] - 100, s.velT.a[i] + 3500);
        }
        markAround(skip, t, n, s.tpSentT, 0, 1500);
        markAround(skip, t, n, s.tpConfT, 0, 500);
        markAround(skip, t, n, s.lifeT, 200, 5000);
        markAround(skip, t, n, s.vehicleT, 200, 1500);
        int si = 0;
        for (int i = 0; i < n; i++) {
            if (!s.states.isEmpty()) {
                while (si + 1 < s.states.size() && s.states.get(si + 1).t <= t[i]) si++;
                SessionData.State st = s.states.get(si);
                if (st.gameMode == 1 || st.gameMode == 3 || st.has(ST_FLYING) || st.has(ST_VEHICLE)
                        || st.has(ST_GLIDING) || st.has(ST_SLEEPING)) {
                    skip[i] = true;
                }
            }
            int env = s.moveEnv.a[i];
            if ((env & (ENV_FLYING | ENV_VEHICLE)) != 0) skip[i] = true;
            // Elytra and riptide carry on for a while after the flag drops.
            if ((env & (ENV_GLIDING | ENV_RIPTIDE)) != 0) markRange(skip, t, n, t[i], t[i] + 1500);
        }
        int count = 0;
        boolean have = false;
        double px = 0, pz = 0;
        for (int i = 0; i < n; i++) {
            if ((s.moveFlags.a[i] & MV_POS) == 0) continue;
            double x = s.moveX.a[i], z = s.moveZ.a[i];
            if (have && !skip[i]) {
                double limit = TELEPORT_H * Math.max(1.0, s.moveSpeed.a[i] / 0.13);
                if (Math.hypot(x - px, z - pz) > limit) {
                    count++;
                    out.moment("move.teleport", t[i]);
                }
            }
            px = x;
            pz = z;
            have = true;
        }
        out.perHour("move.teleport", count, Math.max(1e-6, s.durationMs() / 3_600_000.0));
    }

    // ---------------------------------------------------------------- exclusions

    /** Moves too close to anything that legitimately moves a player. */
    static boolean[] excluded(SessionData s) {
        int n = s.moveT.n;
        boolean[] bad = new boolean[n];
        long[] t = s.moveT.a;
        markAround(bad, t, n, s.velT, 100, 1500);
        markAround(bad, t, n, s.tpSentT, 100, 700);
        markAround(bad, t, n, s.tpConfT, 100, 500);
        markAround(bad, t, n, s.corrT, 100, 700);
        markAround(bad, t, n, s.lifeT, 200, 5000);
        markAround(bad, t, n, s.vehicleT, 200, 1500);
        // An explosion (wind charge, TNT) carries a player far longer than a hit.
        for (int i = 0; i < s.velT.n; i++) {
            if (s.velExplosion.a[i] != 0) markRange(bad, t, n, s.velT.a[i] - 100, s.velT.a[i] + 3500);
        }
        // Slime / bed bounces: the flight right after the contact.
        for (int i = 0; i < n; i++) {
            if ((s.moveEnv.a[i] & (ENV_SLIME | ENV_BED)) != 0) markRange(bad, t, n, t[i], t[i] + 1200);
        }
        // Creative / spectator / flying / sleeping / riding per the 1 Hz state.
        int si = 0;
        for (int i = 0; i < n; i++) {
            while (si + 1 < s.states.size() && s.states.get(si + 1).t <= t[i]) si++;
            if (s.states.isEmpty()) break;
            SessionData.State st = s.states.get(si);
            if (st.gameMode == 1 || st.gameMode == 3 || st.has(ST_FLYING) || st.has(ST_SLEEPING)
                    || st.has(ST_VEHICLE) || st.has(ST_GLIDING) || st.has(ST_AUTH_FROZEN)
                    || st.has(ST_STAFF_FROZEN) || st.has(ST_LAVA) || st.has(ST_WATER)) {
                bad[i] = true;
            }
        }
        return bad;
    }

    private static void markAround(boolean[] bad, long[] t, int n, dev.bastionac.telemetry.Prim.Longs ev, long before, long after) {
        for (int i = 0; i < ev.n; i++) markRange(bad, t, n, ev.a[i] - before, ev.a[i] + after);
    }

    private static void markRange(boolean[] bad, long[] t, int n, long from, long to) {
        int lo = lowerBound(t, n, from);
        for (int i = lo; i < n && t[i] <= to; i++) bad[i] = true;
    }

    static int lowerBound(long[] a, int n, long key) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    // ---------------------------------------------------------------- tick reconstruction

    /**
     * 1.21.2+: one sample per client tick. A tick with no packet is a tick in
     * which the client moved less than 2·10⁻⁴ (vanilla does not report less)
     * — the position carries over. A tick with two position packets is a
     * teleport answer or a cheat; either way it is not judged.
     */
    static Ticks byClientTick(SessionData s, boolean[] bad) {
        int n = s.moveT.n;
        int first = s.moveTick.a[0], last = s.moveTick.a[n - 1];
        int len = Math.max(1, Math.min(last - first + 1, 2_000_000));
        Ticks tk = new Ticks(len);
        tk.n = len;
        int[] posCount = new int[len];
        boolean[] tickBad = new boolean[len];
        double px = s.moveX.a[0], py = s.moveY.a[0], pz = s.moveZ.a[0];
        boolean g = (s.moveFlags.a[0] & MV_GROUND) != 0;
        int env = s.moveEnv.a[0], input = s.moveInput.a[0], gb = s.moveGround.a[0];
        float speed = s.moveSpeed.a[0];
        float yaw = firstYaw(s);
        int mi = 0;
        boolean lastBad = false;
        for (int k = 0; k < len; k++) {
            int tick = first + k;
            boolean hc = false;
            long tt = mi < n ? s.moveT.a[Math.min(mi, n - 1)] : s.moveT.a[n - 1];
            while (mi < n && s.moveTick.a[mi] == tick) {
                int f = s.moveFlags.a[mi];
                if ((f & MV_POS) != 0) {
                    posCount[k]++;
                    px = s.moveX.a[mi];
                    py = s.moveY.a[mi];
                    pz = s.moveZ.a[mi];
                }
                g = (f & MV_GROUND) != 0;
                hc |= (f & MV_HCOLL) != 0;
                env = s.moveEnv.a[mi];
                input = s.moveInput.a[mi];
                gb = s.moveGround.a[mi];
                speed = s.moveSpeed.a[mi];
                if ((f & MV_LOOK) != 0) yaw = s.moveYaw.a[mi];
                tt = s.moveT.a[mi];
                if (bad[mi]) tickBad[k] = true;
                lastBad = bad[mi];
                mi++;
            }
            // A tick without a packet is the last packet's state carried on:
            // hovering in creative flight sends nothing, and those silent ticks
            // were judged as a survival player defying gravity (3.4 — an
            // operator flying in creative read as 65% "rise").
            if (posCount[k] == 0 && lastBad) tickBad[k] = true;
            tk.x[k] = px;
            tk.y[k] = py;
            tk.z[k] = pz;
            tk.ground[k] = g;
            tk.hcoll[k] = hc;
            tk.env[k] = env;
            tk.input[k] = input;
            tk.groundBlock[k] = gb;
            tk.speed[k] = speed;
            tk.yaw[k] = yaw;
            tk.t[k] = tt;
            tk.valid[k] = posCount[k] <= 1 && !tickBad[k];
        }
        return tk;
    }

    /**
     * Older clients (no tick ends of their own): every position packet is one
     * tick while the player keeps moving — vanilla reports a position every
     * tick it moves. A gap breaks the chain.
     */
    static Ticks byPacket(SessionData s, boolean[] bad) {
        int n = s.moveT.n;
        Ticks tk = new Ticks(n);
        int k = 0;
        long prevT = Long.MIN_VALUE;
        float yaw = firstYaw(s);
        for (int i = 0; i < n; i++) {
            if ((s.moveFlags.a[i] & MV_LOOK) != 0) yaw = s.moveYaw.a[i];
            if ((s.moveFlags.a[i] & MV_POS) == 0) continue;
            tk.yaw[k] = yaw;
            tk.x[k] = s.moveX.a[i];
            tk.y[k] = s.moveY.a[i];
            tk.z[k] = s.moveZ.a[i];
            tk.ground[k] = (s.moveFlags.a[i] & MV_GROUND) != 0;
            tk.hcoll[k] = (s.moveFlags.a[i] & MV_HCOLL) != 0;
            tk.env[k] = s.moveEnv.a[i];
            tk.input[k] = s.moveInput.a[i];
            tk.groundBlock[k] = s.moveGround.a[i];
            tk.speed[k] = s.moveSpeed.a[i];
            tk.t[k] = s.moveT.a[i];
            boolean contiguous = prevT == Long.MIN_VALUE || s.moveT.a[i] - prevT <= 250;
            tk.valid[k] = !bad[i] && contiguous;
            prevT = s.moveT.a[i];
            k++;
        }
        tk.n = k;
        return tk;
    }

    // ---------------------------------------------------------------- the judgement

    static void judge(SessionData s, Ticks tk, FeatureSet out, BlockInfo blocks) {
        // Keys only from a client that sends them itself (1.21.2+). For an older one ViaVersion
        // may make up an empty PlayerInput — every one of its jumps would look keyless.
        boolean keys = s.hasInput && (s.protocol < 0 || s.protocol >= 768);
        long airJudged = 0, airGain = 0, turnJudged = 0, airTurn = 0, groundJudged = 0, groundGain = 0;
        long vertJudged = 0, rise = 0, fallJudged = 0, fallFast = 0;
        long jumps = 0, jumpNoKey = 0, noKeyJudged = 0, noKeyGain = 0, sprintJudged = 0, sprintNoFwd = 0;
        long dirJudged = 0, dirOff = 0;

        for (int c = 2; c < tk.n; c++) {
            int b = c - 1, a = c - 2;
            if (!tk.valid[a] || !tk.valid[b] || !tk.valid[c]) continue;
            if (((tk.env[a] | tk.env[b] | tk.env[c]) & EXCLUDE) != 0) continue;
            if (tk.hcoll[b] || tk.hcoll[c]) continue;

            double d1x = tk.x[b] - tk.x[a], d1y = tk.y[b] - tk.y[a], d1z = tk.z[b] - tk.z[a];
            double d2x = tk.x[c] - tk.x[b], d2y = tk.y[c] - tk.y[b], d2z = tk.z[c] - tk.z[b];
            boolean ga = tk.ground[a], gb = tk.ground[b], gc = tk.ground[c];

            // Friction that turned the move of tick b into the velocity tick c starts with.
            double slipA = ga ? slip(blocks, tk.groundBlock[a]) : 1.0;
            double f = slipA * 0.91;
            double vx = zero(d1x * f), vz = zero(d1z * f);
            double vh = Math.hypot(vx, vz);
            double d2h = Math.hypot(d2x, d2z);

            boolean jumpTick = gb && !gc && isJumpVelocity(d2y);
            double slipB = gb ? slip(blocks, tk.groundBlock[b]) : 1.0;
            // The speed attribute is the server's idea of the player's sprint. When the client
            // holds the sprint key but its START_SPRINTING has not reached the server (Via, a
            // reordered packet, a test harness), the attribute lags by exactly the sprint bonus:
            // trust the key and allow it (×1.3) rather than read the client's sprint as a hack.
            double speed = Math.max(0.1, tk.speed[c]);
            if ((tk.env[c] & ENV_SPRINTING) == 0 && (tk.input[c] & IN_SPRINT) != 0) speed *= 1.3;
            double accel = gb ? speed * 0.21600002 / (slipB * slipB * slipB) : AIR_ACCEL;
            if (jumpTick) accel += SPRINT_JUMP;

            // ---- horizontal gain (one-sided: collisions only take speed away)
            double gain = d2h - (vh + accel);
            if (gb) {
                if (d2h > 0.01 || vh > 0.01) {
                    groundJudged++;
                    if (gain > TOL_GAIN) {
                        groundGain++;
                        out.moment("move.ground_gain", tk.t[c]);
                        dbg(tk, c, "ground_gain", gain);
                    }
                }
            } else if (!gc || !gb) {
                if (d2h > 0.01 || vh > 0.01) {
                    airJudged++;
                    if (gain > TOL_GAIN) {
                        airGain++;
                        out.moment("move.air_gain", tk.t[c]);
                        dbg(tk, c, "air_gain", gain);
                    }
                }
                // ---- direction change in the air (strafe): the whole correction vector
                if (!gb && !gc && d2h > 0.08) {
                    turnJudged++;
                    double turn = Math.hypot(d2x - vx, d2z - vz) - accel;
                    if (turn > TOL_TURN) {
                        airTurn++;
                        out.moment("move.air_turn", tk.t[c]);
                    }
                }
            }

            // ---- vertical: free fall between two airborne ticks
            if (!gb && !gc && ((tk.env[a] | tk.env[b] | tk.env[c]) & EXCLUDE_VERTICAL) == 0) {
                boolean unloadedChunkFall = Math.abs(d2y + 0.098) < 0.0015 && Math.abs(d1y + 0.098) < 0.0015;
                if (!unloadedChunkFall) {
                    double pred = zero((d1y - GRAVITY) * 0.98);
                    double r = d2y - pred;
                    vertJudged++;
                    if (r > TOL_VERT) {
                        rise++;
                        out.moment("move.rise", tk.t[c]);
                        dbg(tk, c, "rise", r);
                    }
                    if (d1y <= 0) {
                        fallJudged++;
                        if (r < -TOL_VERT) {
                            fallFast++;
                            out.moment("move.fall_fast", tk.t[c]);
                        }
                    }
                }
            }

            // ---- the keys (1.21.2+ clients)
            if (keys) {
                int in = tk.input[c];
                if (jumpTick) {
                    jumps++;
                    if ((in & IN_JUMP) == 0) {
                        jumpNoKey++;
                        out.moment("move.jump_nokey", tk.t[c]);
                    }
                }
                if (gb && gc && (in & INPUT_MOVE) == 0 && (d2h > 0.005 || vh > 0.005)) {
                    noKeyJudged++;
                    if (d2h > vh + 0.01) {
                        noKeyGain++;
                        out.moment("move.move_nokey", tk.t[c]);
                    }
                }
                if (gb && (in & IN_SPRINT) != 0) {
                    sprintJudged++;
                    if ((in & IN_FORWARD) == 0 && d2h > 0.2 && d2h > vh + TOL_GAIN) {
                        sprintNoFwd++;
                        out.moment("move.sprint_noforward", tk.t[c]);
                    }
                }
                // ---- the push of this tick must point where the keys point (Strafe / Bhop)
                if (gb && gc && !jumpTick && (in & INPUT_MOVE) != 0) {
                    double ax = d2x - vx, az = d2z - vz;
                    double am = Math.hypot(ax, az);
                    double[] want = inputDirection(in, tk.yaw[c]);
                    if (am > 0.03 && want != null) {
                        dirJudged++;
                        double cos = (ax * want[0] + az * want[1]) / am;
                        if (cos < 0.64) {   // more than ~50° off what the keys ask for
                            dirOff++;
                            out.moment("move.input_dir", tk.t[c]);
                        }
                    }
                }
            }
        }

        out.rate("move.air_gain", airGain, airJudged);
        out.rate("move.air_turn", airTurn, turnJudged);
        out.rate("move.ground_gain", groundGain, groundJudged);
        out.rate("move.rise", rise, vertJudged);
        out.rate("move.fall_fast", fallFast, fallJudged);
        if (keys) {
            out.rate("move.jump_nokey", jumpNoKey, jumps);
            out.rate("move.move_nokey", noKeyGain, noKeyJudged);
            out.rate("move.sprint_noforward", sprintNoFwd, sprintJudged);
            out.rate("move.input_dir", dirOff, dirJudged);
        }
    }

    /**
     * The unit direction vanilla pushes a player for these keys at this yaw
     * (Entity.movementInputToVelocity): forward is +z rotated by yaw, left is +x.
     * Null when the keys cancel out.
     */
    static double[] inputDirection(int in, float yawDeg) {
        double fwd = ((in & IN_FORWARD) != 0 ? 1 : 0) - ((in & IN_BACK) != 0 ? 1 : 0);
        double side = ((in & IN_LEFT) != 0 ? 1 : 0) - ((in & IN_RIGHT) != 0 ? 1 : 0);
        double len = Math.hypot(fwd, side);
        if (len < 1e-9) return null;
        fwd /= len;
        side /= len;
        double r = Math.toRadians(yawDeg);
        double sin = Math.sin(r), cos = Math.cos(r);
        return new double[]{side * cos - fwd * sin, fwd * cos + side * sin};
    }

    private static float firstYaw(SessionData s) {
        for (int i = 0; i < s.moveT.n; i++) {
            if ((s.moveFlags.a[i] & MV_LOOK) != 0) return s.moveYaw.a[i];
        }
        return 0f;
    }

    /** A take-off of vanilla's jump velocity: 0.42, plus 0.1 per Jump Boost level. */
    static boolean isJumpVelocity(double dy) {
        for (int k = 0; k <= 5; k++) {
            if (Math.abs(dy - (0.42 + 0.1 * k)) < 0.004) return true;
        }
        return false;
    }

    /** Vanilla zeroes any velocity component below 0.003 at the start of the tick. */
    static double zero(double v) {
        return Math.abs(v) < 0.003 ? 0 : v;
    }

    private static double slip(BlockInfo blocks, int raw) {
        if (raw < 0 || blocks == null) return 0.6;
        float s = blocks.slipperiness(raw);
        return s <= 0 ? 0.6 : s;
    }
}
