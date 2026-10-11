package dev.bastionac.telemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * One decoded session (or the part of it inside a time window), as parallel
 * primitive arrays. Each movement sample also carries the input, environment,
 * friction block and speed attribute that were current when it arrived, so
 * the analyzers never have to re-walk the stream to know the context.
 */
public final class SessionData {

    public String headerJson = "";
    public String uuid = "";
    public String name = "";
    public long startMs;
    /** The file ended without its gzip trailer (still being written, or the server died). */
    public boolean truncated;
    public long records;

    // ---------------------------------------------------------------- movement
    public final Prim.Longs moveT = new Prim.Longs();
    public final Prim.Ints moveFlags = new Prim.Ints();
    /** Absolute position after the packet; the previous one repeated when the packet had none. */
    public final Prim.Doubles moveX = new Prim.Doubles(), moveY = new Prim.Doubles(), moveZ = new Prim.Doubles();
    /** NaN when the packet carried no rotation. */
    public final Prim.Floats moveYaw = new Prim.Floats(), movePitch = new Prim.Floats();
    public final Prim.Ints moveInput = new Prim.Ints();
    public final Prim.Ints moveEnv = new Prim.Ints();
    public final Prim.Ints moveGround = new Prim.Ints();
    public final Prim.Floats moveSpeed = new Prim.Floats();
    /** Index of the client tick (count of tick ends before this packet). */
    public final Prim.Ints moveTick = new Prim.Ints();
    /** Index of the server tick (count of server-tick markers before this packet). */
    public final Prim.Ints moveServerTick = new Prim.Ints();
    /** True once a PlayerInput packet was ever seen (1.21.2+ client speaking for itself). */
    public boolean hasInput;

    public final Prim.Longs tickEndT = new Prim.Longs();
    public final Prim.Longs serverTickT = new Prim.Longs();
    public final Prim.Longs inputT = new Prim.Longs();
    public final Prim.Ints inputBits = new Prim.Ints();

    // ---------------------------------------------------------------- server corrections
    public final Prim.Longs tpSentT = new Prim.Longs();
    public final Prim.Ints tpSentId = new Prim.Ints();
    public final Prim.Doubles tpX = new Prim.Doubles(), tpY = new Prim.Doubles(), tpZ = new Prim.Doubles();
    public final Prim.Longs tpConfT = new Prim.Longs();
    public final Prim.Ints tpConfId = new Prim.Ints();
    public final Prim.Ints tpConfValid = new Prim.Ints();
    public final Prim.Longs corrT = new Prim.Longs();
    public final Prim.Ints corrReason = new Prim.Ints();
    public final Prim.Doubles corrCX = new Prim.Doubles(), corrCY = new Prim.Doubles(), corrCZ = new Prim.Doubles();
    public final Prim.Doubles corrSX = new Prim.Doubles(), corrSY = new Prim.Doubles(), corrSZ = new Prim.Doubles();
    public final Prim.Ints corrBlock = new Prim.Ints();
    public final Prim.Longs velT = new Prim.Longs();
    public final Prim.Floats velX = new Prim.Floats(), velY = new Prim.Floats(), velZ = new Prim.Floats();
    public final Prim.Ints velExplosion = new Prim.Ints();

    // ---------------------------------------------------------------- state
    public final List<State> states = new ArrayList<>();

    public static final class State {
        public long t, serverTick;
        public float mspt, lastTickMs, timerBalance, timerWorst;
        public int ping;
        public double x, y, z;
        public float yaw, pitch, health, speedAttr, gravity, jumpStrength, stepHeight;
        public int food, gameMode, flags, world, blockAtFeet, vehicleType;
        public int[] effects = new int[0];

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    // ---------------------------------------------------------------- world
    public final Prim.Longs actionT = new Prim.Longs();
    public final Prim.Ints actionKind = new Prim.Ints();
    public final Prim.Longs actionPos = new Prim.Longs();
    public final Prim.Ints actionFace = new Prim.Ints();
    public final Prim.Ints actionState = new Prim.Ints();
    public final Prim.Floats actionBreakTicks = new Prim.Floats();
    public final Prim.Ints actionTool = new Prim.Ints();
    public final Prim.Ints actionExposed = new Prim.Ints();

    public final Prim.Longs brokenT = new Prim.Longs();
    public final Prim.Longs brokenPos = new Prim.Longs();
    public final Prim.Ints brokenState = new Prim.Ints();
    public final Prim.Ints brokenExposed = new Prim.Ints();
    /** World index (TelemetryFormat.worldIndex) the block was broken in. */
    public final Prim.Ints brokenWorld = new Prim.Ints();
    /** Y of the position stream when each STATE / move was taken is in the arrays above. */
    public int lastWorld;

    public final List<OreContext> oreContexts = new ArrayList<>();

    public record OreContext(long t, long pos, byte[] cells) {
        /** Cell at offsets −2..2 on each axis. */
        public int at(int dx, int dy, int dz) {
            return cells[(dx + 2) * 25 + (dy + 2) * 5 + (dz + 2)];
        }
    }

    public final Prim.Longs placeT = new Prim.Longs();
    public final Prim.Longs placePos = new Prim.Longs();
    public final Prim.Ints placeFace = new Prim.Ints();
    public final Prim.Floats placeHX = new Prim.Floats(), placeHY = new Prim.Floats(), placeHZ = new Prim.Floats();
    public final Prim.Ints placeItem = new Prim.Ints();

    public final Prim.Longs useT = new Prim.Longs();
    public final Prim.Longs swingT = new Prim.Longs();
    public final Prim.Longs slotT = new Prim.Longs();
    public final Prim.Ints slotValue = new Prim.Ints();
    public final Prim.Longs clickT = new Prim.Longs();
    public final Prim.Ints clickScreenOpen = new Prim.Ints();
    public final Prim.Longs closeT = new Prim.Longs();

    // ---------------------------------------------------------------- combat
    public final List<Attack> attacks = new ArrayList<>();

    public static final class Attack {
        public long t;
        public int targetId, targetType, flags, ping;
        public double eyeX, eyeY, eyeZ, cx, cy, cz;
        public float halfWidth, height, yaw, pitch, distance, aimError, cooldown, tvx, tvy, tvz;

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    public final Prim.Longs targetT = new Prim.Longs();
    public final Prim.Ints targetId = new Prim.Ints();
    public final Prim.Floats targetDX = new Prim.Floats(), targetDY = new Prim.Floats(), targetDZ = new Prim.Floats();
    public final Prim.Floats targetHW = new Prim.Floats(), targetH = new Prim.Floats();

    public final Prim.Longs damageT = new Prim.Longs();
    public final Prim.Floats damageAmount = new Prim.Floats();
    public final List<String> damageType = new ArrayList<>();

    // ---------------------------------------------------------------- network
    public final Prim.Longs pongT = new Prim.Longs();
    public final Prim.Ints pongRtt = new Prim.Ints();
    public final Prim.Longs keepAliveT = new Prim.Longs();
    public final Prim.Ints keepAliveRtt = new Prim.Ints();

    public String brand;
    public int protocol = -1;
    public String locale;
    public int viewDistance = -1;
    public final List<String> channels = new ArrayList<>();

    // ---------------------------------------------------------------- anti-cheat
    public final Prim.Longs flagT = new Prim.Longs();
    public final List<String> flagCheck = new ArrayList<>();
    public final Prim.Floats flagVl = new Prim.Floats();
    public final List<String> flagDetails = new ArrayList<>();
    public final Prim.Longs actT = new Prim.Longs();
    public final Prim.Ints actKind = new Prim.Ints();
    public final List<String> actText = new ArrayList<>();
    public final Prim.Longs lifeT = new Prim.Longs();
    public final Prim.Ints lifeKind = new Prim.Ints();
    public final Prim.Ints lifeWorld = new Prim.Ints();
    public final Prim.Longs noteT = new Prim.Longs();
    public final List<String> notes = new ArrayList<>();
    public final Prim.Longs vehicleT = new Prim.Longs();
    public final Prim.Ints vehicleMounted = new Prim.Ints();

    /** First and last timestamp seen in the window. */
    public long firstT = Long.MAX_VALUE, lastT = Long.MIN_VALUE;

    public long durationMs() {
        return lastT >= firstT ? lastT - firstT : 0;
    }
}
