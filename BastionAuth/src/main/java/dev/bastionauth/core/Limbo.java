package dev.bastionauth.core;

import dev.bastionauth.BastionAuth;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a player waits for the password: high above the spawn of their own
 * world, not where they logged out.
 *
 * <h2>The leak</h2>
 * Before the password the client was put exactly where the account had logged
 * out. Typing a name was enough to read the coordinates off F3 and to receive
 * the chunks around somebody's base. A client that walked around on the login
 * screen was snapped back after the password, which BastionAC then read as a
 * 22-block jump.
 *
 * <h2>The fix</h2>
 * Every connection is moved, before the first packet with a position is sent,
 * to the limbo of its world: the world spawn (0, 0 outside the overworld) above
 * the build limit. The real position is kept here and given back the moment
 * the account is proven — with the anticheat told to expect the jump. A
 * resumed session goes back at once.
 *
 * <h2>Nothing is ever saved in limbo</h2>
 * The world stays the same (only x, y, z change), and the real position is put
 * back for every save of the player — the autosave as well as the one on
 * disconnect — so a crash, a kick or a timeout on the login screen never
 * leaves anybody standing in the sky.
 */
public final class Limbo {

    /** x, y, z, yaw, pitch of the real position, by player. */
    private static final Map<UUID, double[]> REAL = new ConcurrentHashMap<>();
    /** The limbo position the player was put at, by player. */
    private static final Map<UUID, double[]> SKY = new ConcurrentHashMap<>();

    /** A vehicle loaded without its rider: whom to seat on what, and until which tick to keep looking. */
    private record Seat(UUID entity, long untilTick) {}

    /** Riders whose vehicle waits where it stands, by player. */
    private static final Map<UUID, Seat> SEATS = new ConcurrentHashMap<>();
    private static volatile long ticks;

    private Limbo() {}

    private static boolean enabled() {
        AuthManager m = BastionAuth.manager();
        return m != null && m.config().protection.hideLocation;
    }

    public static boolean inLimbo(UUID id) {
        return REAL.containsKey(id);
    }

    /** Above the spawn of the player's own world: nothing there belongs to anybody. */
    private static double[] skyOf(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        double x = 0.5, z = 0.5;
        if (w.getRegistryKey() == World.OVERWORLD) {
            var spawn = w.getSpawnPoint().getPos();
            x = spawn.getX() + 0.5;
            z = spawn.getZ() + 0.5;
        }
        AuthManager m = BastionAuth.manager();
        int below = m == null ? 2 : Math.max(1, Math.min(32, m.config().protection.limboBelowTop));
        return new double[]{x, w.getTopYInclusive() - below, z, p.getYaw(), 30.0};
    }

    /**
     * At the very start of {@code onPlayerConnect}: the saved position is
     * already loaded, nothing has been sent yet. The player is moved without a
     * packet — the join packets then carry the limbo position.
     */
    public static void atConnect(ServerPlayerEntity p) {
        if (!enabled() || p == null) return;
        UUID id = p.getUuid();
        if (REAL.containsKey(id)) return;
        double[] real = {p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch()};
        double[] sky = skyOf(p);
        REAL.put(id, real);
        SKY.put(id, sky);
        p.refreshPositionAndAngles(sky[0], sky[1], sky[2], (float) sky[3], (float) sky[4]);
    }

    /** For a player already in the world (/logout, a respawn on the login screen): teleport up. */
    public static void enter(ServerPlayerEntity p) {
        if (!enabled() || p == null) return;
        UUID id = p.getUuid();
        if (REAL.containsKey(id)) return;
        double[] real = {p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch()};
        double[] sky = skyOf(p);
        REAL.put(id, real);
        SKY.put(id, sky);
        expectServerMove(p);
        p.teleport(p.getEntityWorld(), sky[0], sky[1], sky[2], Set.of(), (float) sky[3], (float) sky[4], false);
    }

    /**
     * Right after the join the game loads the vehicle the player logged out
     * on and seats them on it — which moves the player to the real position
     * and tells the client where it is. In limbo the vehicle is loaded where
     * it stands, the way vanilla loads it, but without its rider; the rider is
     * seated again after the password ({@link #tick}). Returns whether the
     * vanilla loader must be skipped.
     */
    public static boolean deferVehicle(ServerPlayerEntity p, net.minecraft.storage.ReadView view) {
        if (p == null || view == null || !REAL.containsKey(p.getUuid())) return false;
        var root = view.getOptionalReadView("RootVehicle");
        if (root.isEmpty()) return false;
        ServerWorld world = p.getEntityWorld();
        net.minecraft.storage.ReadView rv = root.get();
        net.minecraft.entity.Entity vehicle = net.minecraft.entity.EntityType.loadEntityWithPassengers(
                rv.getReadView("Entity"), world, net.minecraft.entity.SpawnReason.LOAD,
                e -> world.tryLoadEntity(e) ? e : null);
        if (vehicle == null) return true;
        UUID attach = rv.read("Attach", net.minecraft.util.Uuids.INT_STREAM_CODEC).orElse(null);
        SEATS.put(p.getUuid(), new Seat(attach != null ? attach : vehicle.getUuid(), Long.MAX_VALUE));
        BastionAuth.LOGGER.info("{} logged in riding {}: it waits at its place until the password",
                p.getGameProfile().name(), vehicle.getType().getUntranslatedName());
        return true;
    }

    /** Once a tick: seats proven players back on the vehicle they arrived with. */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        long now = ++ticks;
        if (SEATS.isEmpty()) return;
        for (Map.Entry<UUID, Seat> e : SEATS.entrySet()) {
            UUID id = e.getKey();
            Seat seat = e.getValue();
            if (REAL.containsKey(id) || seat.untilTick() == Long.MAX_VALUE) continue;   // still waiting for the password
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null || now > seat.untilTick()) {
                SEATS.remove(id);
                continue;
            }
            net.minecraft.entity.Entity vehicle = p.getEntityWorld().getEntity(seat.entity());
            if (vehicle == null) continue;   // its chunk is still loading
            SEATS.remove(id);
            if (vehicle.isRemoved() || vehicle.squaredDistanceTo(p) > 16 * 16 || p.hasVehicle()) continue;
            p.startRiding(vehicle, true, false);
        }
    }

    /** The account is proven: back to where it really is. */
    public static void leave(ServerPlayerEntity p) {
        if (p == null) return;
        UUID id = p.getUuid();
        double[] real = REAL.remove(id);
        double[] sky = SKY.remove(id);
        if (real == null) return;
        clearPlatform(p, sky);
        expectServerMove(p);
        p.teleport(p.getEntityWorld(), real[0], real[1], real[2], Set.of(), (float) real[3], (float) real[4], false);
        // The vehicle it came with: look for it for the next five seconds.
        Seat seat = SEATS.get(p.getUuid());
        if (seat != null) SEATS.put(p.getUuid(), new Seat(seat.entity(), ticks + 100));
    }

    /**
     * An invisible floor under the limbo spot, on the client only — the world
     * is never touched. In the air Minecraft moves a player at a fixed 0.02 a
     * tick whatever the movement-speed attribute says, so without something
     * to stand on the frozen player could still drift; standing, the zero
     * speed of {@link StateMask} holds. Sent again on the mask's schedule, as
     * the chunk may not have reached the client the first time.
     */
    public static void platform(ServerPlayerEntity p) {
        double[] sky = p == null ? null : SKY.get(p.getUuid());
        if (sky == null) return;
        net.minecraft.block.BlockState barrier = net.minecraft.block.Blocks.BARRIER.getDefaultState();
        int bx = (int) Math.floor(sky[0]), by = (int) Math.floor(sky[1]) - 1, bz = (int) Math.floor(sky[2]);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket(
                        new net.minecraft.util.math.BlockPos(bx + dx, by, bz + dz), barrier));
            }
        }
    }

    /** Takes the invisible floor away again: the client gets the real blocks back. */
    private static void clearPlatform(ServerPlayerEntity p, double[] sky) {
        if (sky == null) return;
        ServerWorld w = p.getEntityWorld();
        int bx = (int) Math.floor(sky[0]), by = (int) Math.floor(sky[1]) - 1, bz = (int) Math.floor(sky[2]);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(bx + dx, by, bz + dz);
                if (!w.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket(pos, w.getBlockState(pos)));
            }
        }
    }

    /** The anchor the freeze holds the player at: the limbo spot while in limbo. */
    public static double[] sky(UUID id) {
        return SKY.get(id);
    }

    /**
     * Before a save: the real position, silently. Returns whether it swapped,
     * so {@link #afterSave} puts the limbo position back.
     */
    public static boolean beforeSave(ServerPlayerEntity p) {
        double[] real = p == null ? null : REAL.get(p.getUuid());
        if (real == null) return false;
        p.refreshPositionAndAngles(real[0], real[1], real[2], (float) real[3], (float) real[4]);
        return true;
    }

    public static void afterSave(ServerPlayerEntity p) {
        double[] sky = p == null ? null : SKY.get(p.getUuid());
        if (sky == null) return;
        p.refreshPositionAndAngles(sky[0], sky[1], sky[2], (float) sky[3], (float) sky[4]);
    }

    /** Drops what is known without moving anybody — the position has become real again (a respawn). */
    public static void forget(UUID id) {
        REAL.remove(id);
        SKY.remove(id);
        SEATS.remove(id);
    }

    /** Leaving without the password: the real position goes back for good, without a packet. */
    public static void onRemove(ServerPlayerEntity p) {
        if (p == null) return;
        double[] real = REAL.remove(p.getUuid());
        SKY.remove(p.getUuid());
        // The vehicle stays where it stands and is saved with its chunk.
        SEATS.remove(p.getUuid());
        if (real != null) p.refreshPositionAndAngles(real[0], real[1], real[2], (float) real[3], (float) real[4]);
    }

    // ------------------------------------------------------------------ anticheat

    private static volatile boolean acResolved;
    private static volatile Method acExpect;

    /** Tells BastionAC the next jump in position is the server's doing. */
    private static void expectServerMove(ServerPlayerEntity p) {
        if (!acResolved) {
            acResolved = true;
            try {
                acExpect = Class.forName("dev.bastionac.BastionAC")
                        .getMethod("expectServerMove", ServerPlayerEntity.class, int.class);
            } catch (Throwable ignored) {
                acExpect = null;
            }
        }
        Method m = acExpect;
        if (m == null) return;
        try {
            m.invoke(null, p, 60);
        } catch (Throwable ignored) {
        }
    }
}
