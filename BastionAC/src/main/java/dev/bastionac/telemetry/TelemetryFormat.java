package dev.bastionac.telemetry;

/**
 * The session file, {@code logs/bastionac/sessions/<date>/<uuid>__<name>__<start>.btl.gz}.
 *
 * <pre>
 * gzip(
 *   "BTL2"            4 bytes magic
 *   version           u8
 *   header            varint length + UTF-8 JSON (uuid, name, start, versions…)
 *   record*           u8 type, zigzag ms since the previous record, payload
 * )
 * </pre>
 *
 * Positions are absolute but quantised to 1/4096 block and stored as the
 * zigzag delta from the previous quantised position of the same stream, so a
 * walking player costs two or three bytes per axis and nothing drifts: the
 * reader adds the deltas back to the exact quantised value. Rotations stay
 * IEEE floats, raw as the client sent them — the sensitivity-grid analysis
 * needs the exact bits. Times are the packet's arrival on the wire (see
 * {@link dev.bastionac.core.ArrivalClock}) for client packets and the server
 * clock for server events.
 *
 * <p>The record list below is the whole contract between the writer and
 * {@link TelemetryReader}; {@code tools/btl_dump.py} decodes the same.
 */
public final class TelemetryFormat {

    public static final int MAGIC = 0x42544C32;   // "BTL2"
    public static final int VERSION = 1;
    /** Position grain: 1/4096 block. */
    public static final double Q = 4096.0;

    // ---------------------------------------------------------------- records

    /** Movement packet. u8 flags (MV_*); [pos: 3× zigzag Δq]; [look: f32 yaw, f32 pitch]. */
    public static final int R_MOVE = 1;
    /** ClientTickEnd. No payload. */
    public static final int R_TICK_END = 2;
    /** PlayerInput. u8 key bits (IN_*). */
    public static final int R_INPUT = 3;
    /** Server teleport sent. varint id, f64 x y z, u8 relative-axis bits. */
    public static final int R_TP_SENT = 4;
    /** TeleportConfirm. varint id, u8 valid. */
    public static final int R_TP_CONFIRM = 5;
    /** Vanilla refused a move and teleported back. u8 reason, f64 claimed xyz, f64 server xyz, varint block at the claimed feet. */
    public static final int R_CORRECTION = 6;
    /** Knockback sent. f32 vx vy vz, u8 explosion. */
    public static final int R_VELOCITY = 7;
    /** Once a second: the server's view of the player. See {@link SessionWriter#state}. */
    public static final int R_STATE = 8;
    /** Environment of the claimed position, when it changes. varint ENV_* bits. */
    public static final int R_ENV = 9;
    /** PlayerAction. u8 action, i64 pos, u8 face, varint seq, varint state, f32 break ticks, varint tool, u8 exposed faces. */
    public static final int R_ACTION = 10;
    /** A block the server let the player break. i64 pos, varint state, u8 exposed faces. */
    public static final int R_BROKEN = 11;
    /** InteractBlock. u8 hand, i64 pos, u8 face, f32 hit xyz (in-block), u8 inside, varint item, varint seq. */
    public static final int R_PLACE = 12;
    /** InteractItem. u8 hand, varint item, f32 yaw, f32 pitch. */
    public static final int R_USE_ITEM = 13;
    /** Attack. See {@link SessionWriter#attack}. */
    public static final int R_ATTACK = 14;
    /** HandSwing. u8 hand. */
    public static final int R_SWING = 15;
    /** UpdateSelectedSlot. u8 slot. */
    public static final int R_SLOT = 16;
    /** ClickSlot. varint sync, zigzag slot, u8 button, u8 action, u8 screen open (server side). */
    public static final int R_CLICK = 17;
    /** A check flag. str check, f32 vl, str details. */
    public static final int R_FLAG = 18;
    /** The anti-cheat acted. u8 kind (ACT_*), str text. */
    public static final int R_AC_ACTION = 19;
    /** Our own ping answered. varint rtt ms. */
    public static final int R_PONG = 20;
    /** Vanilla keep-alive answered. varint rtt ms. */
    public static final int R_KEEPALIVE = 21;
    /** Damage taken. f32 amount, str type, varint attacker id. */
    public static final int R_DAMAGE = 22;
    /** Life event. u8 kind (LIFE_*), u8 world, f64 x y z. */
    public static final int R_LIFE = 23;
    /** A combat target's position this tick. varint id, f32 dx dy dz from the attacker's eye, f32 half width, f32 height. */
    public static final int R_TARGET = 24;
    /** Client identity. str brand, varint protocol, str locale, u8 view distance, u8 main arm, u8 model parts, u8 particles, varint n + n× str channel. */
    public static final int R_CLIENT = 25;
    /** Free text (analyzer markers, admin notes). str. */
    public static final int R_NOTE = 26;
    /** A server tick ended. No payload. */
    public static final int R_SERVER_TICK = 27;
    /** Movement-speed attribute, when it changes. f32. */
    public static final int R_SPEED = 28;
    /** Mount / dismount. u8 mounted, varint vehicle type, varint vehicle id. */
    public static final int R_VEHICLE = 29;
    /** The block that sets friction (0.5 below the feet), when it changes. varint state. */
    public static final int R_GROUND = 30;
    /** 5×5×5 around a broken valuable ore, one nibble per block (ORE_CTX_*), x-major. i64 pos, 63 bytes. */
    public static final int R_ORE_CONTEXT = 31;
    /** The client closed a screen. varint sync. */
    public static final int R_CLOSE = 32;

    // ---------------------------------------------------------------- bits

    public static final int MV_POS = 1, MV_LOOK = 2, MV_GROUND = 4, MV_HCOLL = 8;

    public static final int IN_FORWARD = 1, IN_BACK = 2, IN_LEFT = 4, IN_RIGHT = 8,
            IN_JUMP = 16, IN_SNEAK = 32, IN_SPRINT = 64;

    public static final int ENV_SUPPORT = 1, ENV_ENTITY_SUPPORT = 1 << 1, ENV_GROUND_BELOW = 1 << 2,
            ENV_FLOOR_FACE = 1 << 3, ENV_LIQUID = 1 << 4, ENV_CLIMBABLE = 1 << 5, ENV_WEB = 1 << 6,
            ENV_POWDER = 1 << 7, ENV_BUBBLE = 1 << 8, ENV_SLIME = 1 << 9, ENV_BED = 1 << 10,
            ENV_ICE = 1 << 11, ENV_CUSHION = 1 << 12, ENV_USING_ITEM = 1 << 13, ENV_SNEAKING = 1 << 14,
            ENV_PUSHABLE_NEAR = 1 << 15, ENV_GLIDING = 1 << 16, ENV_VEHICLE = 1 << 17,
            ENV_SPRINTING = 1 << 18, ENV_LEVITATION = 1 << 19, ENV_SLOW_FALLING = 1 << 20,
            ENV_HONEY_OR_SOUL = 1 << 21, ENV_SCAFFOLDING = 1 << 22, ENV_BERRY = 1 << 23,
            ENV_RIPTIDE = 1 << 24, ENV_FLYING = 1 << 25;

    /** STATE flags. */
    public static final int ST_VEHICLE = 1, ST_GLIDING = 1 << 1, ST_SWIMMING = 1 << 2, ST_SLEEPING = 1 << 3,
            ST_ON_GROUND = 1 << 4, ST_WATER = 1 << 5, ST_LAVA = 1 << 6, ST_CLIMBING = 1 << 7,
            ST_SPRINTING = 1 << 8, ST_SNEAKING = 1 << 9, ST_USING_ITEM = 1 << 10, ST_BLOCKING = 1 << 11,
            ST_STAFF_FROZEN = 1 << 12, ST_AUTH_FROZEN = 1 << 13, ST_CHUNK_LOADED = 1 << 14,
            ST_FLYING = 1 << 15, ST_INVULNERABLE = 1 << 16, ST_SCREEN_OPEN = 1 << 17,
            ST_TELEPORT_PENDING = 1 << 18, ST_STAFF = 1 << 19;

    public static final int ACT_SETBACK = 1, ACT_CANCEL = 2, ACT_KICK = 3, ACT_BAN = 4, ACT_FREEZE = 5,
            ACT_RESYNC = 6, ACT_DUMP = 7, ACT_SIGNAL = 8;

    public static final int LIFE_DEATH = 1, LIFE_RESPAWN = 2, LIFE_WORLD = 3, LIFE_GAMEMODE = 4,
            LIFE_AUTHENTICATED = 5;

    public static final int CORR_COLLIDE = 1, CORR_OTHER = 2;

    /** Ore-context nibbles. */
    public static final int CTX_OPEN = 0, CTX_STONE = 1, CTX_DIAMOND = 2, CTX_ORE = 3, CTX_SOLID = 4, CTX_LIQUID = 5;

    /** World index in LIFE / STATE. */
    public static int worldIndex(String id) {
        return switch (id) {
            case "minecraft:overworld" -> 0;
            case "minecraft:the_nether" -> 1;
            case "minecraft:the_end" -> 2;
            default -> 3;
        };
    }

    public static long q(double v) {
        return Math.round(v * Q);
    }

    private TelemetryFormat() {}
}
