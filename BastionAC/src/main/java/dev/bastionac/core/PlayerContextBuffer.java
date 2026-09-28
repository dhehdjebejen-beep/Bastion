package dev.bastionac.core;

import dev.bastionac.BastionAC;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * Player Context Buffer: a fixed-size off-heap ring of compact binary
 * records describing the player's recent input stream — the forensic window
 * a retrospective review of an alert actually needs.
 *
 * <p><b>Record format (28 bytes).</b> One record per position/rotation
 * packet, packed so a full server of rings never pressures the GC and the
 * window spans minutes instead of megabytes:
 * <pre>
 *   long   at         — absolute millisecond timestamp          (8)
 *   float  yaw, pitch — look direction at the packet           (4+4)
 *   short  dx, dy, dz — position delta in centiblocks           (2+2+2)
 *                       (±327 m, 0.01 m grain)
 *   byte   flags      — onGround&lt;&lt;0 | sprinting&lt;&lt;1 | sneaking&lt;&lt;2 |
 *                       usingItem&lt;&lt;3 | inLiquid&lt;&lt;4              (1)
 *   byte   kind       — 0 move · 1 attack · 2 action · 3 place · 4 other (1)
 *   int    aux        — full payload: target entity id for attacks,
 *                       stable digest for textual details          (4)
 * </pre>
 * At 20 packets/s this is ~56 KB/min — the 224 KB ring holds 8192 records,
 * about four minutes of context per player, ~22× less memory than the old
 * 5 MB text ring, and carries the look direction (aim forensics) the old
 * buffer never recorded. The separate {@code kind} and 32-bit {@code aux}
 * replace a broken packing scheme that truncated kinds to 2 bits and
 * payloads to 6, silently turning every "other" record into a "place" and
 * clipping entity ids past 63.
 *
 * <p><b>Why not raw packets.</b> The raw {@code ByteBuf} of a live packet is
 * owned by the netty pipeline and must not be retained; the decoded fields
 * above carry everything the checks and the review need, with no reference
 * leaks by construction.
 *
 * <p><b>Read path.</b> On the first alert of an episode the window is frozen
 * into a gzip'd {@code .dump.gz} under {@code logs/bastionac/dumps/} — one
 * line per record for human/grep review — and the ring keeps rolling.
 */
public final class PlayerContextBuffer {

    /** Bytes per record — see the class javadoc for the layout. */
    static final int RECORD_BYTES = 28;
    /** Ring capacity per player, bytes. 8192 records ≈ 4 minutes at 20 pps. */
    private static final int CAPACITY_BYTES = 224 * 1024;
    /** Never let a misconfigured capacity break the record grain. */
    private static final int CAPACITY = (CAPACITY_BYTES / RECORD_BYTES) * RECORD_BYTES;

    // Record kinds.
    static final byte KIND_MOVE = 0;
    static final byte KIND_ATTACK = 1;
    static final byte KIND_ACTION = 2;
    static final byte KIND_PLACE = 3;
    static final byte KIND_OTHER = 4;

    // Flag bits.
    static final byte F_ON_GROUND = 1 << 0;
    static final byte F_SPRINTING = 1 << 1;
    static final byte F_SNEAKING = 1 << 2;
    static final byte F_USING_ITEM = 1 << 3;
    static final byte F_IN_LIQUID = 1 << 4;

    /** Dumps directory, resolved lazily against the game dir. */
    private static volatile Path dumpDir;

    private static final Map<UUID, Ring> RINGS = new ConcurrentHashMap<>();

    /** Off-heap ring: a pooled direct buffer with a write cursor. */
    private static final class Ring {
        final ByteBuf buf;
        /** Write offset; wraps at CAPACITY. Always a multiple of RECORD_BYTES. */
        int writerIndex;
        /** True once the ring has wrapped at least once (read side needs it). */
        boolean wrapped;

        Ring() {
            // Direct, off-heap, pooled. Released on forget().
            this.buf = PooledByteBufAllocator.DEFAULT.directBuffer(CAPACITY, CAPACITY);
        }

        /**
         * Writes one fixed-size record. synchronized because the write comes
         * from the server thread only, but the snapshot path may read; the
         * record copy keeps the lock hold to a few dozen bytes.
         */
        synchronized void write(long at, float yaw, float pitch,
                                short dx, short dy, short dz, byte flags, byte kind, int aux) {
            int off = writerIndex;
            buf.setLong(off, at);
            buf.setFloat(off + 8, yaw);
            buf.setFloat(off + 12, pitch);
            buf.setShort(off + 16, dx);
            buf.setShort(off + 18, dy);
            buf.setShort(off + 20, dz);
            buf.setByte(off + 22, flags);
            buf.setByte(off + 23, kind);
            buf.setInt(off + 24, aux);
            off += RECORD_BYTES;
            if (off >= CAPACITY) {
                off = 0;
                wrapped = true;
            }
            writerIndex = off;
        }

        /** Full current window as text lines (oldest-first). */
        synchronized String drain() {
            if (!wrapped && writerIndex == 0) return "";
            StringBuilder sb = new StringBuilder();
            if (wrapped) {
                // After the wrap, the oldest record starts at the cursor.
                for (int off = writerIndex; off < CAPACITY; off += RECORD_BYTES) {
                    appendRecord(sb, off);
                }
            }
            for (int off = 0; off < writerIndex; off += RECORD_BYTES) {
                appendRecord(sb, off);
            }
            return sb.toString();
        }

        private void appendRecord(StringBuilder sb, int off) {
            long at = buf.getLong(off);
            float yaw = buf.getFloat(off + 8);
            float pitch = buf.getFloat(off + 12);
            short dx = buf.getShort(off + 16);
            short dy = buf.getShort(off + 18);
            short dz = buf.getShort(off + 20);
            byte flags = buf.getByte(off + 22);
            byte kind = buf.getByte(off + 23);
            int aux = buf.getInt(off + 24);
            sb.append(at).append('|').append(kindName(kind)).append('|')
                    .append(String.format(java.util.Locale.ROOT, "yaw=%.1f,pitch=%.1f", yaw, pitch))
                    .append(String.format(java.util.Locale.ROOT, ",d=(%.2f,%.2f,%.2f)", dx / 100.0, dy / 100.0, dz / 100.0))
                    .append(",g=").append((flags & F_ON_GROUND) != 0 ? 1 : 0)
                    .append((flags & F_SPRINTING) != 0 ? ",sprint" : "")
                    .append((flags & F_SNEAKING) != 0 ? ",sneak" : "")
                    .append((flags & F_USING_ITEM) != 0 ? ",use" : "")
                    .append((flags & F_IN_LIQUID) != 0 ? ",liquid" : "");
            if (aux != 0) sb.append(",aux=").append(aux);
            sb.append('\n');
        }

        synchronized void release() {
            if (buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    private static String kindName(byte kind) {
        return switch (kind) {
            case KIND_MOVE -> "move";
            case KIND_ATTACK -> "attack";
            case KIND_ACTION -> "action";
            case KIND_PLACE -> "place";
            default -> "other";
        };
    }

    // ------------------------------------------------------------------ api

    public static void init(Path gameDir) {
        dumpDir = gameDir.resolve("logs").resolve("bastionac").resolve("dumps");
        try {
            Files.createDirectories(dumpDir);
        } catch (IOException e) {
            BastionAC.LOGGER.warn("PlayerContextBuffer: could not create dump dir", e);
        }
    }

    private static Ring ring(UUID uuid) {
        return RINGS.computeIfAbsent(uuid, u -> new Ring());
    }

    /** Called on join: allocates the player's ring. */
    public static void attach(ServerPlayerEntity player) {
        if (player == null) return;
        ring(player.getUuid());
    }

    /** Called on disconnect: frees the off-heap memory at once. */
    public static void forget(UUID id) {
        Ring r = RINGS.remove(id);
        if (r != null) r.release();
    }

    // ------------------------------------------------------------------ record paths

    /**
     * Records a movement/rotation packet sample. Called from the move hook
     * for every position- or rotation-changing packet.
     */
    public static void recordMove(ServerPlayerEntity player, float yaw, float pitch,
                                  double prevX, double prevY, double prevZ,
                                  double newX, double newY, double newZ,
                                  boolean onGround, boolean sprinting, boolean sneaking,
                                  boolean usingItem, boolean inLiquid) {
        if (player == null) return;
        recordMove(player.getUuid(), yaw, pitch, prevX, prevY, prevZ, newX, newY, newZ,
                onGround, sprinting, sneaking, usingItem, inLiquid);
    }

    /** UUID-keyed variant so tests can feed the buffer without Minecraft. */
    public static void recordMove(UUID uuid, float yaw, float pitch,
                                  double prevX, double prevY, double prevZ,
                                  double newX, double newY, double newZ,
                                  boolean onGround, boolean sprinting, boolean sneaking,
                                  boolean usingItem, boolean inLiquid) {
        Ring r = RINGS.get(uuid);
        if (r == null) return;
        byte flags = 0;
        if (onGround) flags |= F_ON_GROUND;
        if (sprinting) flags |= F_SPRINTING;
        if (sneaking) flags |= F_SNEAKING;
        if (usingItem) flags |= F_USING_ITEM;
        if (inLiquid) flags |= F_IN_LIQUID;
        r.write(System.currentTimeMillis(), yaw, pitch,
                centi(newX - prevX), centi(newY - prevY), centi(newZ - prevZ),
                flags, KIND_MOVE, 0);
    }

    /** Records an attack on the targeted entity. Full entity id preserved. */
    public static void recordAttack(ServerPlayerEntity player, int targetEntityId) {
        if (player == null) return;
        recordAttack(player.getUuid(), player.getYaw(), player.getPitch(), targetEntityId);
    }

    /** UUID-keyed variant for tests. */
    public static void recordAttack(UUID uuid, float yaw, float pitch, int targetEntityId) {
        Ring r = RINGS.get(uuid);
        if (r == null) return;
        r.write(System.currentTimeMillis(), yaw, pitch,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, KIND_ATTACK, targetEntityId);
    }

    /** Records a generic packet event with a short numeric payload. */
    public static void recordEvent(ServerPlayerEntity player, String packetType, String details) {
        if (player == null) return;
        recordEvent(player.getUuid(), player.getYaw(), player.getPitch(), packetType, details);
    }

    /** UUID-keyed variant for tests. */
    public static void recordEvent(UUID uuid, float yaw, float pitch, String packetType, String details) {
        Ring r = RINGS.get(uuid);
        if (r == null) return;
        byte kind = kindFromName(packetType);
        int aux = hashAux(details);
        r.write(System.currentTimeMillis(), yaw, pitch,
                (short) 0, (short) 0, (short) 0, (byte) 0, kind, aux);
    }

    private static byte kindFromName(String packetType) {
        if (packetType == null) return KIND_OTHER;
        return switch (packetType) {
            case "move" -> KIND_MOVE;
            case "attack" -> KIND_ATTACK;
            case "action" -> KIND_ACTION;
            case "place" -> KIND_PLACE;
            default -> KIND_OTHER;
        };
    }

    /**
     * Stable 32-bit digest of a detail string, for record-level forensics.
     * Non-zero for non-empty input so a payload is distinguishable from
     * "nothing" in a dump line.
     */
    static int hashAux(String details) {
        if (details == null || details.isEmpty()) return 0;
        int h = 0;
        for (int i = 0; i < details.length(); i++) {
            h = h * 31 + details.charAt(i);
        }
        // Fold into a strictly positive 31-bit value.
        return (h & 0x7FFFFFFF) == 0 ? 1 : (h & 0x7FFFFFFF);
    }

    private static short centi(double delta) {
        double v = Math.max(-327.67, Math.min(327.67, delta)) * 100.0;
        return (short) Math.round(v);
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * Freezes the current window into a compressed dump file. Returns the
     * path written, or null on failure.
     */
    public static Path snapshot(ServerPlayerEntity player, String reason) {
        if (player == null) return null;
        return snapshot(player.getUuid(), safeName(player), reason);
    }

    /** UUID-keyed variant for tests. */
    public static Path snapshot(UUID uuid, String safeName, String reason) {
        Ring r = RINGS.get(uuid);
        if (r == null) return null;
        String window = r.drain();
        if (window.isEmpty()) return null;
        Path dir = dumpDir;
        if (dir == null) return null;
        Path out = dir.resolve(safeName + "-" + System.currentTimeMillis() + ".dump.gz");
        try (GZIPOutputStream gz = new GZIPOutputStream(
                Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            gz.write(("reason=" + reason + "\n").getBytes(StandardCharsets.UTF_8));
            gz.write(window.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            BastionAC.LOGGER.warn("PlayerContextBuffer: dump failed for {}: {}", safeName, e.toString());
            return null;
        }
        return out;
    }

    private static String safeName(ServerPlayerEntity player) {
        return player.getGameProfile().name().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** Exposed for tests: how many bytes the ring holds (full records only). */
    public static int usedBytes(UUID id) {
        Ring r = RINGS.get(id);
        if (r == null) return -1;
        synchronized (r) {
            return r.wrapped ? CAPACITY : r.writerIndex;
        }
    }

    /** Exposed for tests: record count in the ring. */
    public static int recordCount(UUID id) {
        Ring r = RINGS.get(id);
        if (r == null) return -1;
        synchronized (r) {
            return r.wrapped ? CAPACITY / RECORD_BYTES : r.writerIndex / RECORD_BYTES;
        }
    }

    /** Exposed for tests: drains the window as text without touching disk. */
    public static String drainForTests(UUID id) {
        Ring r = RINGS.get(id);
        return r == null ? "" : r.drain();
    }

    /** Exposed for tests: allocates a ring for a raw uuid. */
    static void attach(UUID uuid) {
        ring(uuid);
    }

    private PlayerContextBuffer() {}
}
