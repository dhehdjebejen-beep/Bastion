package dev.bastionac.telemetry;

import dev.bastionac.BastionAC;
import dev.bastionac.config.ACConfig;
import dev.bastionac.core.PlayerData;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static dev.bastionac.telemetry.TelemetryFormat.*;

/**
 * Telemetry v2 — everything the server can see about a player, recorded for
 * the analyzer and for debugging the anti-cheat afterwards.
 *
 * <p>The old context ring keeps four minutes of coarse samples and only
 * reaches disk when a check alerts. That is enough to read an alert, not to
 * judge a player: a false positive and a cheat produce the same alert line,
 * and the difference is in what happened around it — which keys were held,
 * whether the server had just moved the player, what the ping did, where the
 * ore was. This records the whole session, compactly, in its own file.
 *
 * <p>Costs: a few bytes per packet on the server thread (no allocation on the
 * hot path beyond the writer's buffer), gzip and disk on a dedicated
 * low-priority thread, about half a megabyte per player-hour on disk. The
 * folder is held under {@code telemetry.maxTotalMb} by deleting the oldest
 * sessions first; files an open Argus case still needs are kept.
 *
 * <p>Every hook swallows its own exceptions: losing a record is acceptable,
 * breaking a packet handler is not.
 */
public final class Telemetry {

    private static final Map<UUID, SessionWriter> OPEN = new ConcurrentHashMap<>();
    private static final Map<UUID, Track> TRACK = new ConcurrentHashMap<>();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int PING_BASE = 0xBAD00000;
    private static int pingCounter;
    private static volatile Supplier<Set<String>> pinned = Set::of;
    private static volatile long lastErrorLogMs;

    /** Per-player bookkeeping that is not part of the encoded stream. */
    private static final class Track {
        volatile int pingId;
        volatile long pingSentMs;
        /** The last three transaction round trips (ms) and when the newest came back; -1 = none yet. */
        final int[] rtt = {-1, -1, -1};
        volatile long rttAtMs;
        boolean mounted;
        /** Recently attacked entities: id → last attack tick. */
        final Map<Integer, Long> targets = new LinkedHashMap<>();
        /** Exposure of the block about to be broken (BEFORE event → AFTER event). */
        long pendingBreakPos = Long.MIN_VALUE;
        int pendingBreakExposed;
        boolean clientSent;
    }

    private Telemetry() {}

    // ---------------------------------------------------------------- lifecycle

    public static void init(Path gameDir) {
        TelemetryIO.init(gameDir.resolve("logs").resolve("bastionac").resolve("sessions"));
        runJanitor();
    }

    /** Argus says which files it still needs. */
    public static void setPinnedSupplier(Supplier<Set<String>> supplier) {
        pinned = supplier == null ? Set::of : supplier;
    }

    public static void runJanitor() {
        ACConfig.TelemetryCfg c = BastionAC.config().telemetry;
        TelemetryIO.janitor(c.retentionDays, (long) c.maxTotalMb * 1024L * 1024L, pinned);
    }

    public static boolean enabled() {
        return BastionAC.config().telemetry.enabled;
    }

    public static void shutdown() {
        for (UUID id : new ArrayList<>(OPEN.keySet())) closeWriter(id);
        TelemetryIO.drain(5_000);
    }

    /** Join: a new session file. */
    public static void attach(ServerPlayerEntity p) {
        if (!enabled() || p == null) return;
        try {
            UUID id = p.getUuid();
            closeWriter(id);   // a stale writer from a half-finished disconnect
            String name = p.getGameProfile().name();
            long now = System.currentTimeMillis();
            String safe = name.replaceAll("[^A-Za-z0-9_]", "_");
            String file = id + "__" + safe + "__" + LocalDateTime.now().format(STAMP) + ".btl.gz";
            long cap = (long) Math.max(10, BastionAC.config().telemetry.maxSessionMb) * 1024L * 1024L * 4L;
            SessionWriter w = new SessionWriter(id.toString(), name, now, file, cap);
            com.google.gson.JsonObject h = new com.google.gson.JsonObject();
            h.addProperty("uuid", id.toString());
            h.addProperty("name", name);
            h.addProperty("start", now);
            h.addProperty("ac", BastionAC.VERSION);
            h.addProperty("mc", "1.21.11");
            h.addProperty("protocol", dev.bastionac.core.ClientVersion.protocolOf(id));
            w.header(h.toString());
            w.life(now, LIFE_WORLD, worldOf(p), p.getX(), p.getY(), p.getZ());
            OPEN.put(id, w);
            TRACK.put(id, new Track());
            TelemetryIO.open(w, w.drain());
        } catch (Throwable t) {
            error("attach", t);
        }
    }

    public static void detach(UUID id) {
        closeWriter(id);
        TRACK.remove(id);
    }

    private static void closeWriter(UUID id) {
        SessionWriter w = OPEN.remove(id);
        if (w == null) return;
        w.note(System.currentTimeMillis(), "session end");
        TelemetryIO.close(w, w.drain());
    }

    /** The writer of an online player, or null. */
    public static SessionWriter of(UUID id) {
        return OPEN.get(id);
    }

    private static SessionWriter w(ServerPlayerEntity p) {
        return p == null ? null : OPEN.get(p.getUuid());
    }

    /** File name of the player's current session, or null. */
    public static String currentFile(UUID id) {
        SessionWriter w = OPEN.get(id);
        return w == null ? null : w.fileName();
    }

    /** Pushes everything the player's writer holds to disk now (the analyzer is about to read it). */
    public static void flushNow(UUID id) {
        SessionWriter w = OPEN.get(id);
        if (w != null) TelemetryIO.append(w, w.drain());
    }

    public static Collection<SessionWriter> openWriters() {
        return OPEN.values();
    }

    // ---------------------------------------------------------------- per tick

    public static void tick(MinecraftServer srv, long tick) {
        if (OPEN.isEmpty()) return;
        ACConfig.TelemetryCfg cfg = BastionAC.config().telemetry;
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
            SessionWriter w = OPEN.get(p.getUuid());
            if (w == null) continue;
            try {
                w.serverTick(now);
                Track tr = TRACK.get(p.getUuid());
                int phase = (int) ((tick + (p.getUuid().hashCode() & 0x7FFF)) % 20);
                if (phase == 0) state(srv, p, w, tick, now);
                if (tr != null) {
                    vehicle(p, w, tr, now);
                    if (cfg.recordTargets && !tr.targets.isEmpty()) targets(p, w, tr, tick, now);
                    if (cfg.pingIntervalTicks > 0
                            && (tick + (p.getUuid().hashCode() & 0x7FFF)) % cfg.pingIntervalTicks == 0) {
                        ping(p, tr, now);
                    }
                }
                if ((tick + (p.getUuid().hashCode() & 0x7FFF)) % 100 == 0 || w.pending() > 256 * 1024) {
                    TelemetryIO.append(w, w.drain());
                }
            } catch (Throwable t) {
                error("tick", t);
            }
        }
        if (tick % (20 * 60 * 15) == 0) runJanitor();
    }

    private static void state(MinecraftServer srv, ServerPlayerEntity p, SessionWriter w, long tick, long now) {
        SessionWriter.State s = new SessionWriter.State();
        PlayerData d = BastionAC.data(p);
        s.serverTick = tick;
        s.msptAvg = srv.getAverageTickTime();
        s.lastTickMs = BastionAC.lastTickMs();
        s.ping = p.networkHandler == null ? -1 : p.networkHandler.getLatency();
        if (d != null) {
            s.timerBalance = (float) d.timer.balance();
            s.timerWorst = (float) d.timer.takeWorst();
        }
        s.x = p.getX();
        s.y = p.getY();
        s.z = p.getZ();
        s.yaw = p.getYaw();
        s.pitch = p.getPitch();
        s.health = p.getHealth();
        s.food = p.getHungerManager().getFoodLevel();
        s.gameMode = p.interactionManager.getGameMode().getIndex();
        int f = 0;
        if (p.hasVehicle()) f |= ST_VEHICLE;
        if (p.isGliding()) f |= ST_GLIDING;
        if (p.isSwimming()) f |= ST_SWIMMING;
        if (p.isSleeping()) f |= ST_SLEEPING;
        if (p.isOnGround()) f |= ST_ON_GROUND;
        if (p.isTouchingWater()) f |= ST_WATER;
        if (p.isInLava()) f |= ST_LAVA;
        if (p.isClimbing()) f |= ST_CLIMBING;
        if (p.isSprinting()) f |= ST_SPRINTING;
        if (p.isSneaking()) f |= ST_SNEAKING;
        if (p.isUsingItem()) f |= ST_USING_ITEM;
        if (p.isBlocking()) f |= ST_BLOCKING;
        if (dev.bastionac.core.FreezeManager.isStaffFrozen(p.getUuid())) f |= ST_STAFF_FROZEN;
        if (BastionAC.isAuthFrozen(p)) f |= ST_AUTH_FROZEN;
        World world = p.getEntityWorld();
        if (world.isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)) f |= ST_CHUNK_LOADED;
        if (p.getAbilities().flying) f |= ST_FLYING;
        if (p.isInvulnerable()) f |= ST_INVULNERABLE;
        if (p.currentScreenHandler != p.playerScreenHandler) f |= ST_SCREEN_OPEN;
        if (d != null && d.teleportPending) f |= ST_TELEPORT_PENDING;
        if (dev.bastionac.core.Alerts.isAdmin(p)) f |= ST_STAFF;
        s.flags = f;
        s.speedAttr = (float) p.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        s.gravity = (float) p.getAttributeValue(EntityAttributes.GRAVITY);
        s.jumpStrength = (float) p.getAttributeValue(EntityAttributes.JUMP_STRENGTH);
        s.stepHeight = (float) p.getAttributeValue(EntityAttributes.STEP_HEIGHT);
        s.world = worldOf(p);
        s.blockAtFeet = Block.getRawIdFromState(world.getBlockState(BlockPos.ofFloored(p.getX(), p.getY() - 0.5000001, p.getZ())));
        Entity v = p.getVehicle();
        s.vehicleType = v == null ? 0 : Registries.ENTITY_TYPE.getRawId(v.getType()) + 1;
        Collection<StatusEffectInstance> effects = p.getStatusEffects();
        int[] e = new int[effects.size() * 3];
        int i = 0;
        for (StatusEffectInstance inst : effects) {
            e[i++] = Registries.STATUS_EFFECT.getRawId(inst.getEffectType().value());
            e[i++] = Math.min(255, Math.max(0, inst.getAmplifier()));
            e[i++] = Math.max(0, inst.getDuration());
        }
        s.effects = e;
        w.state(now, s);
    }

    private static void vehicle(ServerPlayerEntity p, SessionWriter w, Track tr, long now) {
        boolean mounted = p.hasVehicle();
        if (mounted == tr.mounted) return;
        tr.mounted = mounted;
        Entity v = p.getVehicle();
        w.vehicle(now, mounted, v == null ? 0 : Registries.ENTITY_TYPE.getRawId(v.getType()), v == null ? 0 : v.getId());
    }

    /** Where the entities this player recently hit are, every tick for five seconds after each hit. */
    private static void targets(ServerPlayerEntity p, SessionWriter w, Track tr, long tick, long now) {
        Vec3d eye = p.getEyePos();
        Iterator<Map.Entry<Integer, Long>> it = tr.targets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            if (tick - e.getValue() > 100) {
                it.remove();
                continue;
            }
            Entity t = p.getEntityWorld().getEntityById(e.getKey());
            if (t == null || t.isRemoved()) {
                it.remove();
                continue;
            }
            Box b = t.getBoundingBox();
            Vec3d c = b.getCenter();
            w.target(now, t.getId(), (float) (c.x - eye.x), (float) (c.y - eye.y), (float) (c.z - eye.z),
                    (float) (b.getLengthX() / 2), (float) b.getLengthY());
        }
    }

    private static void ping(ServerPlayerEntity p, Track tr, long now) {
        if (p.networkHandler == null) return;
        int id = PING_BASE | (pingCounter++ & 0xFFFFF);
        tr.pingId = id;
        tr.pingSentMs = now;
        p.networkHandler.sendPacket(new CommonPingS2CPacket(id));
    }

    /** True for the ping ids the telemetry sends. */
    public static boolean isOurPing(int id) {
        return (id & 0xFFF00000) == PING_BASE;
    }

    // ---------------------------------------------------------------- movement hooks

    public static void move(ServerPlayerEntity p, PlayerMoveC2SPacket packet, long arrivalMs) {
        SessionWriter w = w(p);
        if (w == null) return;
        try {
            boolean pos = packet.changesPosition(), look = packet.changesLook();
            w.move(arrivalMs, pos, packet.getX(p.getX()), packet.getY(p.getY()), packet.getZ(p.getZ()),
                    look, packet.getYaw(0f), packet.getPitch(0f), packet.isOnGround(), packet.horizontalCollision());
        } catch (Throwable t) {
            error("move", t);
        }
    }

    public static void tickEnd(ServerPlayerEntity p, long arrivalMs) {
        SessionWriter w = w(p);
        if (w != null) w.tickEnd(arrivalMs);
    }

    public static int inputBits(PlayerInput in) {
        return (in.forward() ? IN_FORWARD : 0) | (in.backward() ? IN_BACK : 0) | (in.left() ? IN_LEFT : 0)
                | (in.right() ? IN_RIGHT : 0) | (in.jump() ? IN_JUMP : 0) | (in.sneak() ? IN_SNEAK : 0)
                | (in.sprint() ? IN_SPRINT : 0);
    }

    public static void input(ServerPlayerEntity p, PlayerInput in, long arrivalMs) {
        SessionWriter w = w(p);
        if (w != null) w.input(arrivalMs, inputBits(in));
    }

    /** From the movement check, after its environment scan. */
    public static void env(ServerPlayerEntity p, int bits, int groundRawId, float speedAttr) {
        SessionWriter w = w(p);
        if (w == null) return;
        long now = System.currentTimeMillis();
        w.env(now, bits);
        w.ground(now, groundRawId);
        w.speed(now, speedAttr);
    }

    public static void teleportSent(ServerPlayerEntity p, int id, double x, double y, double z, int relBits) {
        SessionWriter w = w(p);
        if (w != null) w.teleportSent(System.currentTimeMillis(), id, x, y, z, relBits);
    }

    public static void teleportConfirm(ServerPlayerEntity p, int id, boolean valid, long arrivalMs) {
        SessionWriter w = w(p);
        if (w != null) w.teleportConfirm(arrivalMs, id, valid);
    }

    public static void correction(ServerPlayerEntity p, int reason, double cx, double cy, double cz, int blockAtFeet) {
        SessionWriter w = w(p);
        if (w != null) {
            w.correction(System.currentTimeMillis(), reason, cx, cy, cz, p.getX(), p.getY(), p.getZ(), blockAtFeet);
        }
    }

    public static void velocity(ServerPlayerEntity p, Vec3d v, boolean explosion) {
        SessionWriter w = w(p);
        if (w != null) w.velocity(System.currentTimeMillis(), v.x, v.y, v.z, explosion);
    }

    // ---------------------------------------------------------------- world hooks

    public static void action(ServerPlayerEntity p, PlayerActionC2SPacket packet, long arrivalMs) {
        SessionWriter w = w(p);
        if (w == null) return;
        try {
            World world = p.getEntityWorld();
            BlockPos pos = packet.getPos();
            BlockState state = world.getBlockState(pos);
            float ticks = 0f;
            if (packet.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
                float delta = state.calcBlockBreakingDelta(p, world, pos);
                ticks = delta > 0 ? 1f / delta : 0f;
            }
            w.action(arrivalMs, packet.getAction().ordinal(), pos.asLong(), packet.getDirection().getIndex(),
                    packet.getSequence(), Block.getRawIdFromState(state), ticks,
                    Registries.ITEM.getRawId(p.getMainHandStack().getItem()),
                    dev.bastionac.core.XrayTracker.exposedFaces(world, pos));
        } catch (Throwable t) {
            error("action", t);
        }
    }

    /** Before the block goes: its exposure is still measurable. */
    public static void beforeBreak(ServerPlayerEntity p, BlockPos pos) {
        Track tr = p == null ? null : TRACK.get(p.getUuid());
        if (tr == null) return;
        try {
            tr.pendingBreakPos = pos.asLong();
            tr.pendingBreakExposed = dev.bastionac.core.XrayTracker.exposedFaces(p.getEntityWorld(), pos);
            if (BastionAC.config().telemetry.recordOreContext) {
                BlockState state = p.getEntityWorld().getBlockState(pos);
                if (dev.bastionac.core.XrayTracker.classify(state) != dev.bastionac.core.XrayTracker.OreFamily.OTHER) {
                    SessionWriter w = w(p);
                    if (w != null) w.oreContext(System.currentTimeMillis(), pos.asLong(), oreContext(p.getEntityWorld(), pos));
                }
            }
        } catch (Throwable t) {
            error("beforeBreak", t);
        }
    }

    public static void broken(ServerPlayerEntity p, BlockPos pos, BlockState state) {
        SessionWriter w = w(p);
        Track tr = p == null ? null : TRACK.get(p.getUuid());
        if (w == null || tr == null) return;
        int exposed = tr.pendingBreakPos == pos.asLong() ? tr.pendingBreakExposed : 0x3F;
        tr.pendingBreakPos = Long.MIN_VALUE;
        w.broken(System.currentTimeMillis(), pos.asLong(), Block.getRawIdFromState(state), exposed);
    }

    /** 5×5×5 categories around a block, x-major. */
    static byte[] oreContext(World world, BlockPos center) {
        byte[] out = new byte[125];
        BlockPos.Mutable m = new BlockPos.Mutable();
        int i = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    out[i++] = (byte) dev.bastionac.core.XrayTracker.contextOf(world.getBlockState(m));
                }
            }
        }
        return out;
    }

    public static void place(ServerPlayerEntity p, PlayerInteractBlockC2SPacket packet, long arrivalMs) {
        SessionWriter w = w(p);
        if (w == null) return;
        try {
            var hit = packet.getBlockHitResult();
            BlockPos pos = hit.getBlockPos();
            Vec3d hv = hit.getPos();
            w.place(arrivalMs, packet.getHand().ordinal(), pos.asLong(), hit.getSide().getIndex(),
                    (float) (hv.x - pos.getX()), (float) (hv.y - pos.getY()), (float) (hv.z - pos.getZ()),
                    hit.isInsideBlock(), Registries.ITEM.getRawId(p.getStackInHand(packet.getHand()).getItem()),
                    packet.getSequence());
        } catch (Throwable t) {
            error("place", t);
        }
    }

    public static void useItem(ServerPlayerEntity p, PlayerInteractItemC2SPacket packet, long arrivalMs) {
        SessionWriter w = w(p);
        if (w == null) return;
        try {
            w.useItem(arrivalMs, packet.getHand().ordinal(),
                    Registries.ITEM.getRawId(p.getStackInHand(packet.getHand()).getItem()),
                    packet.getYaw(), packet.getPitch());
        } catch (Throwable t) {
            error("useItem", t);
        }
    }

    public static void swing(ServerPlayerEntity p, int hand, long arrivalMs) {
        SessionWriter w = w(p);
        if (w != null) w.swing(arrivalMs, hand);
    }

    public static void slot(ServerPlayerEntity p, int slot, long arrivalMs) {
        SessionWriter w = w(p);
        if (w != null) w.slot(arrivalMs, slot);
    }

    public static void click(ServerPlayerEntity p, ClickSlotC2SPacket packet, long arrivalMs) {
        SessionWriter w = w(p);
        if (w == null) return;
        try {
            w.click(arrivalMs, packet.syncId(), packet.slot(), packet.button(), packet.actionType().ordinal(),
                    p.currentScreenHandler != p.playerScreenHandler);
        } catch (Throwable t) {
            error("click", t);
        }
    }

    public static void close(ServerPlayerEntity p, int syncId, long arrivalMs) {
        SessionWriter w = w(p);
        if (w != null) w.close(arrivalMs, syncId);
    }

    // ---------------------------------------------------------------- combat hooks

    public static void attack(ServerPlayerEntity p, Entity target, long arrivalMs) {
        SessionWriter w = w(p);
        Track tr = p == null ? null : TRACK.get(p.getUuid());
        if (w == null || tr == null || target == null) return;
        try {
            SessionWriter.Attack a = new SessionWriter.Attack();
            Vec3d eye = p.getEyePos();
            Box b = target.getBoundingBox();
            Vec3d c = b.getCenter();
            float yaw = p.getYaw(), pitch = p.getPitch();
            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            double lx = -Math.sin(yr) * Math.cos(pr), ly = -Math.sin(pr), lz = Math.cos(yr) * Math.cos(pr);
            double tx = c.x - eye.x, ty = c.y - eye.y, tz = c.z - eye.z;
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            double cos = tl < 1e-6 ? 1 : (lx * tx + ly * ty + lz * tz) / tl;
            a.aimError = (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
            double nx = Math.max(b.minX, Math.min(eye.x, b.maxX));
            double ny = Math.max(b.minY, Math.min(eye.y, b.maxY));
            double nz = Math.max(b.minZ, Math.min(eye.z, b.maxZ));
            a.distance = (float) Math.sqrt((nx - eye.x) * (nx - eye.x) + (ny - eye.y) * (ny - eye.y) + (nz - eye.z) * (nz - eye.z));
            boolean hits = b.raycast(eye, eye.add(lx * 8, ly * 8, lz * 8)).isPresent() || b.contains(eye);
            a.targetId = target.getId();
            a.targetType = Registries.ENTITY_TYPE.getRawId(target.getType());
            a.flags = (target instanceof ServerPlayerEntity ? SessionWriter.Attack.F_PLAYER : 0)
                    | (p.isSprinting() ? SessionWriter.Attack.F_SPRINTING : 0)
                    | (p.isOnGround() ? SessionWriter.Attack.F_ON_GROUND : 0)
                    | (p.fallDistance > 0 ? SessionWriter.Attack.F_FALLING : 0)
                    | (hits ? SessionWriter.Attack.F_LOOK_HITS : 0);
            a.eyeX = eye.x;
            a.eyeY = eye.y;
            a.eyeZ = eye.z;
            a.cx = c.x;
            a.cy = c.y;
            a.cz = c.z;
            a.halfWidth = (float) (b.getLengthX() / 2);
            a.height = (float) b.getLengthY();
            a.yaw = yaw;
            a.pitch = pitch;
            a.cooldown = p.getAttackCooldownProgress(0.5f);
            Vec3d tv = target.getVelocity();
            a.tvx = (float) tv.x;
            a.tvy = (float) tv.y;
            a.tvz = (float) tv.z;
            // The ping lag compensation may trust (a spoofed keep-alive does not
            // inflate it): the reach analysis sets high-ping hits aside by it.
            a.ping = lagCompPing(p);
            w.attack(arrivalMs, a);
            synchronized (tr.targets) {
                tr.targets.remove(target.getId());
                tr.targets.put(target.getId(), BastionAC.serverTick());
                while (tr.targets.size() > 3) tr.targets.remove(tr.targets.keySet().iterator().next());
            }
        } catch (Throwable t) {
            error("attack", t);
        }
    }

    public static void damage(ServerPlayerEntity p, float amount, String type, int attackerId) {
        SessionWriter w = w(p);
        if (w != null) w.damage(System.currentTimeMillis(), amount, type, attackerId);
    }

    // ---------------------------------------------------------------- network hooks (netty thread)

    public static void pong(ServerPlayerEntity p, int id) {
        Track tr = p == null ? null : TRACK.get(p.getUuid());
        SessionWriter w = w(p);
        if (tr == null || w == null || id != tr.pingId) return;
        long now = System.currentTimeMillis();
        int rtt = (int) Math.min(60_000, now - tr.pingSentMs);
        w.pong(now, rtt);
        synchronized (tr.rtt) {
            tr.rtt[2] = tr.rtt[1];
            tr.rtt[1] = tr.rtt[0];
            tr.rtt[0] = rtt;
        }
        tr.rttAtMs = now;
    }

    /**
     * The ping lag compensation may trust. Vanilla's latency comes from
     * keep-alives, and keep-alives are exactly what a PingSpoof module holds
     * back: an honest 40 ms that claims 900 ms buys a near-second-deep reach
     * window — Backtrack with a licence. Our own transactions are answered in
     * order with the game packets, so the larger of the last three caps the
     * keep-alive figure. Only ever lowers the claim; unknown or stale → vanilla.
     */
    public static int lagCompPing(ServerPlayerEntity p) {
        int latency = p.networkHandler == null ? 0 : p.networkHandler.getLatency();
        Track tr = TRACK.get(p.getUuid());
        if (tr == null || System.currentTimeMillis() - tr.rttAtMs > 10_000) return latency;
        int worst = -1;
        synchronized (tr.rtt) {
            for (int r : tr.rtt) worst = Math.max(worst, r);
        }
        return worst < 0 ? latency : Math.min(latency, worst + 50);
    }

    public static void keepAlive(ServerPlayerEntity p, int rttMs) {
        SessionWriter w = w(p);
        if (w != null) w.keepAlive(System.currentTimeMillis(), rttMs);
    }

    /** Brand known (a few seconds in): the identity record. */
    public static void clientInfo(ServerPlayerEntity p, String brand) {
        SessionWriter w = w(p);
        Track tr = p == null ? null : TRACK.get(p.getUuid());
        if (w == null || tr == null || tr.clientSent) return;
        tr.clientSent = true;
        try {
            var o = p.getClientOptions();
            List<String> channels = new ArrayList<>();
            try {
                var ids = net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.getSendable(p);
                if (ids != null) for (var c : ids) channels.add(c.toString());
            } catch (Throwable ignored) {
            }
            w.client(System.currentTimeMillis(), brand == null ? "?" : brand,
                    dev.bastionac.core.ClientVersion.protocolOf(p.getUuid()),
                    o == null ? "" : o.language(), o == null ? 0 : o.viewDistance(),
                    o == null ? 0 : o.mainArm().ordinal(), o == null ? 0 : o.playerModelParts(),
                    o == null ? 0 : o.particleStatus().ordinal(), channels);
        } catch (Throwable t) {
            error("client", t);
        }
    }

    // ---------------------------------------------------------------- anti-cheat hooks

    public static void flag(ServerPlayerEntity p, String check, double vl, String details) {
        SessionWriter w = w(p);
        if (w != null) w.flag(System.currentTimeMillis(), check, vl, details);
    }

    public static void acAction(UUID id, int kind, String text) {
        SessionWriter w = id == null ? null : OPEN.get(id);
        if (w != null) w.acAction(System.currentTimeMillis(), kind, text);
    }

    public static void life(ServerPlayerEntity p, int kind) {
        SessionWriter w = w(p);
        if (w != null) w.life(System.currentTimeMillis(), kind, worldOf(p), p.getX(), p.getY(), p.getZ());
    }

    public static void note(UUID id, String text) {
        SessionWriter w = id == null ? null : OPEN.get(id);
        if (w != null) w.note(System.currentTimeMillis(), text);
    }

    // ---------------------------------------------------------------- helpers

    static int worldOf(ServerPlayerEntity p) {
        return worldIndex(p.getEntityWorld().getRegistryKey().getValue().toString());
    }

    private static void error(String where, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogMs > 60_000) {
            lastErrorLogMs = now;
            BastionAC.LOGGER.warn("Telemetry {} failed (further errors muted for a minute): {}", where, t.toString());
        }
    }
}
