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
 * <h2>Nothing around the limbo spot is shown (1.13)</h2>
 * Hanging above the spawn was safe in the overworld, whose spawn every new
 * player sees anyway — but in the Nether limbo is above 0, 0, where many
 * servers keep their hub, and every chunk sent there carried the real blocks
 * for any client that reads chunk data instead of rendering it. Now a client
 * in limbo gets every chunk empty (air, no light, no block entities) and no
 * block, light or block-entity update at all; the real chunks it still has
 * in view are sent the moment the account is proven ({@link #leave}).
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

    /**
     * The vehicle a player logged out on, loaded from their file but kept out
     * of the world: the root with its passengers, the one the player sat on,
     * and the connection it belongs to.
     */
    private record Held(net.minecraft.entity.Entity root, UUID attach, ServerPlayerEntity owner) {}

    /** Vehicles waiting for their rider's password, by player. See {@link #deferVehicle}. */
    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();

    /** Chunks a client in limbo was sent empty, by player: what to send for real after the password. */
    private static final Map<UUID, it.unimi.dsi.fastutil.longs.LongSet> MASKED = new ConcurrentHashMap<>();
    /** Set while this mod sends its own block updates (the platform) to a client in limbo. */
    private static final ThreadLocal<Boolean> OWN_BLOCKS = ThreadLocal.withInitial(() -> false);

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
     * and tells the client where it is. Returns whether the vanilla loader
     * must be skipped.
     *
     * <h2>Held, not spawned (1.13)</h2>
     * Until 1.12 the vehicle was put into the world where it stood, without
     * its rider. Anybody who typed the name then walked away without the
     * password: the boat, the minecart with a chest, the donkey stayed in
     * the chunk — and vanilla writes {@code RootVehicle} only for a player who
     * is riding, so the owner's file no longer had it. A crash or a timeout
     * on the login screen did the same to the real owner.
     *
     * <p>Now the vehicle and its passengers are read into memory and kept out
     * of the world. Every save of the player writes them back into the file
     * ({@link #writeHeldVehicle}), so leaving, a kick, the autosave and a
     * crash after it all keep it where vanilla keeps it. After the password
     * it is spawned and the player seated, as vanilla does at the join.
     */
    public static boolean deferVehicle(ServerPlayerEntity p, net.minecraft.storage.ReadView view) {
        if (p == null || view == null || !REAL.containsKey(p.getUuid())) return false;
        var root = view.getOptionalReadView("RootVehicle");
        if (root.isEmpty()) return false;
        ServerWorld world = p.getEntityWorld();
        net.minecraft.storage.ReadView rv = root.get();
        // Loaded the way vanilla loads it, minus the world: the processor keeps
        // every entity of the tree instead of adding it.
        net.minecraft.entity.Entity vehicle = net.minecraft.entity.EntityType.loadEntityWithPassengers(
                rv.getReadView("Entity"), world, net.minecraft.entity.SpawnReason.LOAD, e -> e);
        if (vehicle == null) return true;
        UUID attach = rv.read("Attach", net.minecraft.util.Uuids.INT_STREAM_CODEC).orElse(vehicle.getUuid());
        HELD.put(p.getUuid(), new Held(vehicle, attach, p));
        BastionAuth.LOGGER.info("{} logged in riding {}: it is kept out of the world until the password",
                p.getGameProfile().name(), vehicle.getType().getUntranslatedName());
        return true;
    }

    /**
     * From {@code writeRootVehicle}: a player whose vehicle is held is saved
     * as riding it, exactly as vanilla would have saved them. Returns whether
     * it wrote, so the vanilla writer (which sees no vehicle) is skipped.
     */
    public static boolean writeHeldVehicle(ServerPlayerEntity p, net.minecraft.storage.WriteView view) {
        Held held = p == null ? null : HELD.get(p.getUuid());
        if (held == null || held.owner() != p || p.hasVehicle()) return false;
        net.minecraft.storage.WriteView root = view.get("RootVehicle");
        root.put("Attach", net.minecraft.util.Uuids.INT_STREAM_CODEC, held.attach());
        held.root().saveData(root.get("Entity"));
        return true;
    }

    /** Whether a vehicle waits for this player's password (tests and the audit). */
    public static boolean holdsVehicle(UUID id) {
        return HELD.containsKey(id);
    }

    /**
     * Puts a held vehicle into the world. {@code rider}: seated on the part
     * they sat on, or null to leave it standing empty (a death on the login
     * screen dismounts, as in vanilla).
     */
    private static void release(Held held, ServerPlayerEntity rider) {
        ServerWorld world = held.owner().getEntityWorld();
        if (!world.spawnNewEntityAndPassengers(held.root())) {
            BastionAuth.LOGGER.warn("Could not put back the vehicle of {}: an entity with its id already exists",
                    held.owner().getGameProfile().name());
            return;
        }
        if (rider == null || rider.hasVehicle()) return;
        net.minecraft.entity.Entity seat = held.root().streamSelfAndPassengers()
                .filter(e -> e.getUuid().equals(held.attach())).findFirst().orElse(held.root());
        rider.startRiding(seat, true, false);
    }

    /** Once a tick. Nothing to do any more since the vehicle is held; kept for the caller. */
    public static void tick(net.minecraft.server.MinecraftServer server) {
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
        sendMaskedForReal(p);
        // The vehicle it came with goes into the world now, with the player on it.
        Held held = HELD.get(id);
        if (held != null && held.owner() == p) {
            HELD.remove(id);
            release(held, p);
        }
    }

    // ------------------------------------------------------------------ chunks

    /**
     * From {@code ChunkDataSender.sendChunkData}: a client in limbo gets the
     * chunk empty. Returns whether it was sent here (the vanilla packet is
     * then skipped).
     */
    public static boolean sendEmptyChunk(net.minecraft.server.network.ServerPlayNetworkHandler handler,
                                         ServerWorld world, net.minecraft.world.chunk.WorldChunk chunk) {
        ServerPlayerEntity p = handler == null ? null : handler.player;
        if (p == null || chunk == null || !REAL.containsKey(p.getUuid())) return false;
        var voidBiome = world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME)
                .getOrThrow(net.minecraft.world.biome.BiomeKeys.THE_VOID);
        net.minecraft.world.chunk.EmptyChunk empty = new net.minecraft.world.chunk.EmptyChunk(world, chunk.getPos(), voidBiome);
        handler.sendPacket(new net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket(
                empty, net.minecraft.world.chunk.light.LightingProvider.DEFAULT, null, null));
        MASKED.computeIfAbsent(p.getUuid(), k -> it.unimi.dsi.fastutil.longs.LongSets.synchronize(
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet())).add(chunk.getPos().toLong());
        return true;
    }

    /**
     * From the outbound gate: what a client in limbo must not receive — the
     * changes to the blocks it was shown as air. The platform under the limbo
     * spot is this mod's own and goes through.
     */
    public static boolean drops(ServerPlayerEntity p, net.minecraft.network.packet.Packet<?> packet) {
        if (p == null || packet == null || OWN_BLOCKS.get() || !REAL.containsKey(p.getUuid())) return false;
        return packet instanceof net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket
                || packet instanceof net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket
                || packet instanceof net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket
                || packet instanceof net.minecraft.network.packet.s2c.play.BlockEventS2CPacket
                || packet instanceof net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket
                || packet instanceof net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket
                || packet instanceof net.minecraft.network.packet.s2c.play.ChunkBiomeDataS2CPacket
                || packet instanceof net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
    }

    /**
     * After the move down: the chunks the client was shown empty and still
     * has in view are queued again, now with the real blocks. The view is
     * brought to the new position first — a chunk queued and then dropped
     * from the view would be forgotten by the server and stay empty on the
     * client.
     */
    private static void sendMaskedForReal(ServerPlayerEntity p) {
        it.unimi.dsi.fastutil.longs.LongSet masked = MASKED.remove(p.getUuid());
        if (masked == null || masked.isEmpty()) return;
        ServerWorld w = p.getEntityWorld();
        var chunks = w.getChunkManager();
        chunks.updatePosition(p);
        int resent = 0;
        synchronized (masked) {
            for (long packed : masked) {
                int cx = net.minecraft.util.math.ChunkPos.getPackedX(packed);
                int cz = net.minecraft.util.math.ChunkPos.getPackedZ(packed);
                if (!chunks.chunkLoadingManager.isTracked(p, cx, cz)) continue;
                net.minecraft.world.chunk.WorldChunk chunk = chunks.getWorldChunk(cx, cz);
                if (chunk == null) continue;
                p.networkHandler.chunkDataSender.add(chunk);
                resent++;
            }
        }
        if (resent > 0) {
            BastionAuth.LOGGER.debug("{}: {} chunks shown empty in limbo sent for real", p.getGameProfile().name(), resent);
        }
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
        OWN_BLOCKS.set(true);
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket(
                            new net.minecraft.util.math.BlockPos(bx + dx, by, bz + dz), barrier));
                }
            }
        } finally {
            OWN_BLOCKS.set(false);
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

    /**
     * Drops what is known without moving anybody — the position has become
     * real again (a respawn). A held vehicle is left standing where it was:
     * dying dismounts in vanilla too.
     */
    public static void forget(UUID id) {
        REAL.remove(id);
        SKY.remove(id);
        MASKED.remove(id);
        Held held = HELD.remove(id);
        if (held != null) release(held, null);
    }

    /**
     * Leaving without the password: the real position goes back for good,
     * without a packet. A held vehicle stays held until the final save has
     * written it into the player's file ({@link #afterRemove}).
     */
    public static void onRemove(ServerPlayerEntity p) {
        if (p == null) return;
        double[] real = REAL.remove(p.getUuid());
        SKY.remove(p.getUuid());
        MASKED.remove(p.getUuid());
        if (real != null) p.refreshPositionAndAngles(real[0], real[1], real[2], (float) real[3], (float) real[4]);
    }

    /** After the final save: the held vehicle lives on in the file only. */
    public static void afterRemove(ServerPlayerEntity p) {
        if (p == null) return;
        Held held = HELD.get(p.getUuid());
        if (held != null && held.owner() == p) HELD.remove(p.getUuid());
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
