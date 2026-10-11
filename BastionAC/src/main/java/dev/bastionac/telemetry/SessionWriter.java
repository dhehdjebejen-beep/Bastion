package dev.bastionac.telemetry;

import java.util.List;

import static dev.bastionac.telemetry.TelemetryFormat.*;

/**
 * Encodes one player's session into the {@link TelemetryFormat} byte stream.
 *
 * <p>Pure Java on purpose: every method takes plain numbers, so the encoder
 * and {@link TelemetryReader} are tested against each other without a game.
 * {@link Telemetry} pulls those numbers out of Minecraft and calls in here.
 *
 * <p>Writers come from two threads — the server thread (almost everything)
 * and the connection's netty thread (round-trip times measured where they
 * happen) — so every method holds the writer's lock. It is never contended
 * for more than a few dozen bytes.
 */
public final class SessionWriter {

    public final String uuid;
    public final String name;
    public final long startMs;
    final String fileName;

    private final ByteSink sink = new ByteSink(16 * 1024);
    private long lastTs;
    private long qx, qy, qz;
    private boolean havePos;
    private int lastEnv = -1;
    private int lastGround = -1;
    private float lastSpeed = Float.NaN;
    private int lastInput = -1;
    /** Bytes handed to the I/O thread so far (the file is gzip, this is the raw size). */
    private long flushedRaw;
    private long records;
    /** A runaway client is not allowed to fill the disk: past this the stream stops taking movement. */
    private final long rawCap;
    private boolean capped;

    public SessionWriter(String uuid, String name, long startMs, String fileName, long rawCap) {
        this.uuid = uuid;
        this.name = name;
        this.startMs = startMs;
        this.fileName = fileName;
        this.lastTs = startMs;
        this.rawCap = rawCap;
    }

    /** The file header: magic, version and a JSON line. Called once, first. */
    public synchronized void header(String json) {
        sink.u8(MAGIC >>> 24).u8(MAGIC >>> 16).u8(MAGIC >>> 8).u8(MAGIC);
        sink.u8(VERSION);
        sink.str(json, 1 << 16);
    }

    private boolean begin(int type, long ts) {
        if (capped && (type == R_MOVE || type == R_TICK_END || type == R_SERVER_TICK || type == R_TARGET)) {
            return false;
        }
        if (flushedRaw + sink.size() > rawCap && !capped) {
            capped = true;
            sink.u8(R_NOTE).zigzag(ts - lastTs).str("session size cap reached: movement no longer recorded", 200);
            lastTs = ts;
            records++;
            return type != R_MOVE && type != R_TICK_END && type != R_SERVER_TICK && type != R_TARGET;
        }
        sink.u8(type).zigzag(ts - lastTs);
        lastTs = ts;
        records++;
        return true;
    }

    // ---------------------------------------------------------------- movement stream

    public synchronized void move(long ts, boolean hasPos, double x, double y, double z,
                                  boolean hasLook, float yaw, float pitch, boolean onGround, boolean hColl) {
        if (!begin(R_MOVE, ts)) return;
        int flags = (hasPos ? MV_POS : 0) | (hasLook ? MV_LOOK : 0) | (onGround ? MV_GROUND : 0) | (hColl ? MV_HCOLL : 0);
        sink.u8(flags);
        if (hasPos) {
            long nx = q(x), ny = q(y), nz = q(z);
            if (!havePos) {
                // First position of the stream: the delta from zero is the absolute value.
                qx = 0;
                qy = 0;
                qz = 0;
                havePos = true;
            }
            sink.zigzag(nx - qx).zigzag(ny - qy).zigzag(nz - qz);
            qx = nx;
            qy = ny;
            qz = nz;
        }
        if (hasLook) sink.f32(yaw).f32(pitch);
    }

    public synchronized void tickEnd(long ts) {
        begin(R_TICK_END, ts);
    }

    public synchronized void serverTick(long ts) {
        begin(R_SERVER_TICK, ts);
    }

    public synchronized void input(long ts, int bits) {
        if (bits == lastInput) return;
        lastInput = bits;
        if (!begin(R_INPUT, ts)) return;
        sink.u8(bits);
    }

    public synchronized void env(long ts, int bits) {
        if (bits == lastEnv) return;
        lastEnv = bits;
        if (!begin(R_ENV, ts)) return;
        sink.varint(bits);
    }

    public synchronized void ground(long ts, int stateRawId) {
        if (stateRawId == lastGround) return;
        lastGround = stateRawId;
        if (!begin(R_GROUND, ts)) return;
        sink.varint(stateRawId);
    }

    public synchronized void speed(long ts, float attr) {
        if (Float.compare(attr, lastSpeed) == 0) return;
        lastSpeed = attr;
        if (!begin(R_SPEED, ts)) return;
        sink.f32(attr);
    }

    public synchronized void teleportSent(long ts, int id, double x, double y, double z, int relBits) {
        if (!begin(R_TP_SENT, ts)) return;
        sink.varint(id).f64(x).f64(y).f64(z).u8(relBits);
    }

    public synchronized void teleportConfirm(long ts, int id, boolean valid) {
        if (!begin(R_TP_CONFIRM, ts)) return;
        sink.varint(id).u8(valid ? 1 : 0);
    }

    public synchronized void correction(long ts, int reason, double cx, double cy, double cz,
                                        double sx, double sy, double sz, int blockAtFeet) {
        if (!begin(R_CORRECTION, ts)) return;
        sink.u8(reason).f64(cx).f64(cy).f64(cz).f64(sx).f64(sy).f64(sz).varint(blockAtFeet);
    }

    public synchronized void velocity(long ts, double vx, double vy, double vz, boolean explosion) {
        if (!begin(R_VELOCITY, ts)) return;
        sink.f32((float) vx).f32((float) vy).f32((float) vz).u8(explosion ? 1 : 0);
    }

    public synchronized void vehicle(long ts, boolean mounted, int typeRawId, int entityId) {
        if (!begin(R_VEHICLE, ts)) return;
        sink.u8(mounted ? 1 : 0).varint(typeRawId).varint(Math.max(0, entityId));
    }

    // ---------------------------------------------------------------- state sample

    /** Server-side snapshot, once a second. */
    public synchronized void state(long ts, State s) {
        if (!begin(R_STATE, ts)) return;
        sink.varlong(s.serverTick).f32(s.msptAvg).f32(s.lastTickMs).varint(Math.max(0, s.ping))
                .f32(s.timerBalance).f32(s.timerWorst)
                .f64(s.x).f64(s.y).f64(s.z).f32(s.yaw).f32(s.pitch)
                .f32(s.health).u8(s.food).u8(s.gameMode).varint(s.flags)
                .f32(s.speedAttr).f32(s.gravity).f32(s.jumpStrength).f32(s.stepHeight)
                .u8(s.world).varint(s.blockAtFeet).varint(s.vehicleType);
        int n = s.effects == null ? 0 : Math.min(s.effects.length / 3, 32);
        sink.u8(n);
        for (int i = 0; i < n; i++) {
            sink.varint(s.effects[i * 3]).u8(s.effects[i * 3 + 1]).varint(s.effects[i * 3 + 2]);
        }
    }

    /** Everything a STATE record carries; filled by {@link Telemetry}. */
    public static final class State {
        public long serverTick;
        public float msptAvg, lastTickMs;
        public int ping;
        public float timerBalance, timerWorst;
        public double x, y, z;
        public float yaw, pitch, health;
        public int food, gameMode, flags;
        public float speedAttr, gravity, jumpStrength, stepHeight;
        public int world, blockAtFeet, vehicleType;
        /** Triples: effect raw id, amplifier, remaining ticks. */
        public int[] effects;
    }

    // ---------------------------------------------------------------- world interaction

    public synchronized void action(long ts, int action, long pos, int face, int seq, int stateRawId,
                                    float breakTicks, int toolRawId, int exposedFaces) {
        if (!begin(R_ACTION, ts)) return;
        sink.u8(action).i64(pos).u8(face).varint(seq).varint(stateRawId).f32(breakTicks)
                .varint(toolRawId).u8(exposedFaces);
    }

    public synchronized void broken(long ts, long pos, int stateRawId, int exposedFaces) {
        if (!begin(R_BROKEN, ts)) return;
        sink.i64(pos).varint(stateRawId).u8(exposedFaces);
    }

    /** {@code nibbles} holds 125 values 0–15 (x-major: x*25 + y*5 + z). */
    public synchronized void oreContext(long ts, long pos, byte[] nibbles) {
        if (!begin(R_ORE_CONTEXT, ts)) return;
        sink.i64(pos);
        for (int i = 0; i < 126; i += 2) {
            int a = i < nibbles.length ? nibbles[i] & 0xF : 0;
            int b = i + 1 < nibbles.length ? nibbles[i + 1] & 0xF : 0;
            sink.u8((a << 4) | b);
        }
    }

    public synchronized void place(long ts, int hand, long pos, int face, float hx, float hy, float hz,
                                   boolean inside, int itemRawId, int seq) {
        if (!begin(R_PLACE, ts)) return;
        sink.u8(hand).i64(pos).u8(face).f32(hx).f32(hy).f32(hz).u8(inside ? 1 : 0).varint(itemRawId).varint(seq);
    }

    public synchronized void useItem(long ts, int hand, int itemRawId, float yaw, float pitch) {
        if (!begin(R_USE_ITEM, ts)) return;
        sink.u8(hand).varint(itemRawId).f32(yaw).f32(pitch);
    }

    public synchronized void swing(long ts, int hand) {
        if (!begin(R_SWING, ts)) return;
        sink.u8(hand);
    }

    public synchronized void slot(long ts, int slot) {
        if (!begin(R_SLOT, ts)) return;
        sink.u8(slot);
    }

    public synchronized void click(long ts, int syncId, int slot, int button, int actionType, boolean screenOpen) {
        if (!begin(R_CLICK, ts)) return;
        sink.varint(Math.max(0, syncId)).zigzag(slot).u8(button).u8(actionType).u8(screenOpen ? 1 : 0);
    }

    public synchronized void close(long ts, int syncId) {
        if (!begin(R_CLOSE, ts)) return;
        sink.varint(Math.max(0, syncId));
    }

    // ---------------------------------------------------------------- combat

    /** One attack and the geometry around it. */
    public synchronized void attack(long ts, Attack a) {
        if (!begin(R_ATTACK, ts)) return;
        sink.varint(Math.max(0, a.targetId)).varint(a.targetType).u8(a.flags)
                .f64(a.eyeX).f64(a.eyeY).f64(a.eyeZ)
                .f64(a.cx).f64(a.cy).f64(a.cz).f32(a.halfWidth).f32(a.height)
                .f32(a.yaw).f32(a.pitch).f32(a.distance).f32(a.aimError).f32(a.cooldown)
                .f32(a.tvx).f32(a.tvy).f32(a.tvz).varint(Math.max(0, a.ping));
    }

    public static final class Attack {
        public static final int F_PLAYER = 1, F_SPRINTING = 2, F_ON_GROUND = 4, F_FALLING = 8, F_LOOK_HITS = 16,
                F_CANCELLED = 32;
        public int targetId, targetType, flags, ping;
        public double eyeX, eyeY, eyeZ, cx, cy, cz;
        public float halfWidth, height, yaw, pitch, distance, aimError, cooldown, tvx, tvy, tvz;
    }

    public synchronized void target(long ts, int id, float dx, float dy, float dz, float halfWidth, float height) {
        if (!begin(R_TARGET, ts)) return;
        sink.varint(Math.max(0, id)).f32(dx).f32(dy).f32(dz).f32(halfWidth).f32(height);
    }

    public synchronized void damage(long ts, float amount, String type, int attackerId) {
        if (!begin(R_DAMAGE, ts)) return;
        sink.f32(amount).str(type, 64).varint(Math.max(0, attackerId));
    }

    // ---------------------------------------------------------------- network & identity

    public synchronized void pong(long ts, int rttMs) {
        if (!begin(R_PONG, ts)) return;
        sink.varint(Math.max(0, rttMs));
    }

    public synchronized void keepAlive(long ts, int rttMs) {
        if (!begin(R_KEEPALIVE, ts)) return;
        sink.varint(Math.max(0, rttMs));
    }

    public synchronized void client(long ts, String brand, int protocol, String locale, int viewDistance,
                                    int mainArm, int modelParts, int particles, List<String> channels) {
        if (!begin(R_CLIENT, ts)) return;
        sink.str(brand, 128).varint(Math.max(0, protocol)).str(locale, 16).u8(viewDistance).u8(mainArm)
                .u8(modelParts).u8(particles);
        int n = channels == null ? 0 : Math.min(channels.size(), 64);
        sink.varint(n);
        for (int i = 0; i < n; i++) sink.str(channels.get(i), 96);
    }

    // ---------------------------------------------------------------- anti-cheat events

    public synchronized void flag(long ts, String check, double vl, String details) {
        if (!begin(R_FLAG, ts)) return;
        sink.str(check, 32).f32((float) vl).str(details, 240);
    }

    public synchronized void acAction(long ts, int kind, String text) {
        if (!begin(R_AC_ACTION, ts)) return;
        sink.u8(kind).str(text, 240);
    }

    public synchronized void life(long ts, int kind, int world, double x, double y, double z) {
        if (!begin(R_LIFE, ts)) return;
        sink.u8(kind).u8(world).f64(x).f64(y).f64(z);
        // A respawn or a dimension change is a new position stream.
        if (kind == LIFE_RESPAWN || kind == LIFE_WORLD) havePos = false;
    }

    public synchronized void note(long ts, String text) {
        if (!begin(R_NOTE, ts)) return;
        sink.str(text, 1000);
    }

    // ---------------------------------------------------------------- draining

    /** Bytes waiting to be written. */
    public synchronized int pending() {
        return sink.size();
    }

    /** Hands the pending bytes over to the I/O thread. */
    public synchronized byte[] drain() {
        byte[] out = sink.take();
        flushedRaw += out.length;
        return out;
    }

    public synchronized long rawBytes() {
        return flushedRaw + sink.size();
    }

    public synchronized long records() {
        return records;
    }

    public String fileName() {
        return fileName;
    }
}
