package dev.bastionac.core;

import dev.bastionac.BastionAC;
import dev.bastionac.config.ACConfig;
import dev.bastionac.mixin.ChunkDeltaUpdateAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;

/**
 * Anti-X-ray and anti-storage-ESP by starving the client (3.6).
 *
 * <p><b>Buried blocks.</b> An ore or a storage block with no open face is sent
 * as the stone around it. The rule is one question asked of the world, never
 * of the client: does this block touch anything a player could see it
 * through? It is asked every time a block leaves for a client — the chunk,
 * a single update, a section update, block-entity data — so the server's own
 * correction after a forged dig or click says "stone" too. Revealing follows
 * the world: a block that stops covering re-sends its six neighbours.
 *
 * <p><b>Far storage.</b> A chest in a cave, a furnace in a cellar, a barrel in
 * a ruin all touch air, so the first rule shows them — and storage ESP draws
 * every one in the view distance. Every block a storage ESP looks for is
 * therefore sent disguised to a player who is farther than
 * {@code storageRevealRadius}, and within it until the player has a clear line
 * of sight to it or comes within {@code storageAlwaysVisibleRadius}. The
 * reveal is the server's own decision from the player's position, sent once;
 * the block entity (its contents, the chest lid) goes with it.
 *
 * <p>A neighbour in a chunk that is not loaded counts as covering: hiding
 * wrongly costs a stone-coloured face until the next update, revealing wrongly
 * would be the leak.
 */
public final class OreShield {

    private static final Set<Block> ORES = Set.of(
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS);

    private static final Set<Block> NETHER_KIND = Set.of(
            Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS);

    /** Everything a storage ESP highlights (shulker boxes come from their tag). */
    private static final Set<Block> STORAGE = Set.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST,
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BREWING_STAND,
            Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.CRAFTER,
            Blocks.DECORATED_POT, Blocks.CHISELED_BOOKSHELF);

    private static final Direction[] DIRS = Direction.values();

    /** The section array of the chunk being serialised, so its size and its bytes come from the same copy. */
    private static final ThreadLocal<Object[]> PENDING = new ThreadLocal<>();
    /** The player the chunk being serialised goes to (set around vanilla's per-player chunk send). */
    private static final ThreadLocal<ServerPlayerEntity> RECIPIENT = new ThreadLocal<>();
    /** player → storage positions sent to them disguised by distance, waiting for a reveal. */
    private static final Map<UUID, Set<BlockPos>> FAR = new HashMap<>();
    /** player → the world those positions are in. */
    private static final Map<UUID, World> FAR_WORLD = new HashMap<>();

    private OreShield() {}

    // ------------------------------------------------------------------ rule

    static ACConfig.General general() {
        ACConfig cfg = BastionAC.config();
        return cfg == null || !BastionAC.enabled() ? null : cfg.general;
    }

    static boolean oresOn() {
        ACConfig.General g = general();
        return g != null && g.hideEnclosedOres;
    }

    static boolean containersOn() {
        ACConfig.General g = general();
        return g != null && g.hideEnclosedContainers;
    }

    static boolean farOn() {
        ACConfig.General g = general();
        return g != null && g.hideFarStorage;
    }

    static boolean anyOn() {
        ACConfig.General g = general();
        return g != null && (g.hideEnclosedOres || g.hideEnclosedContainers || g.hideFarStorage);
    }

    static boolean isStorage(BlockState state) {
        return STORAGE.contains(state.getBlock()) || state.isIn(BlockTags.SHULKER_BOXES);
    }

    /** Whether this block is of a kind the shield hides when buried. */
    public static boolean isHiddenKind(BlockState state) {
        if (ORES.contains(state.getBlock())) return oresOn();
        if (isStorage(state)) return containersOn();
        return false;
    }

    /** Whether a neighbour hides this face: an opaque full cube, or another block the shield hides. */
    static boolean covers(BlockState state) {
        return state.isOpaqueFullCube() || isHiddenKind(state);
    }

    /** What a hidden block is sent as: the stone that would be around it. */
    static BlockState disguise(BlockState state, World world, int y) {
        Block b = state.getBlock();
        if (NETHER_KIND.contains(b) || world.getRegistryKey() == World.NETHER) return Blocks.NETHERRACK.getDefaultState();
        if (world.getRegistryKey() == World.END) return Blocks.END_STONE.getDefaultState();
        String id = net.minecraft.registry.Registries.BLOCK.getId(b).getPath();
        if (id.startsWith("deepslate_") || y < 0) return Blocks.DEEPSLATE.getDefaultState();
        return Blocks.STONE.getDefaultState();
    }

    /**
     * The world's block at a position, without loading anything: null when its
     * chunk is not loaded (or off the server thread, where the chunk manager
     * answers nothing).
     */
    static BlockState peek(World world, WorldChunk hint, int x, int y, int z) {
        if (y < world.getBottomY()) return Blocks.BEDROCK.getDefaultState();
        if (y >= world.getTopYInclusive() + 1) return Blocks.AIR.getDefaultState();
        int cx = x >> 4, cz = z >> 4;
        WorldChunk chunk = hint != null && hint.getPos().x == cx && hint.getPos().z == cz
                ? hint
                : world.getChunkManager().getWorldChunk(cx, cz);
        if (chunk == null) return null;
        return chunk.getBlockState(new BlockPos(x, y, z));
    }

    /** Whether the hidden-kind block at this position has every face covered. */
    static boolean buried(World world, WorldChunk hint, int x, int y, int z) {
        for (Direction d : DIRS) {
            BlockState n = peek(world, hint, x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ());
            if (n != null && !covers(n)) return false;
        }
        return true;
    }

    /** Most blocks a vein walk visits before it gives up and keeps the block hidden. */
    static final int VEIN_LIMIT = 48;

    /** Coal and deepslate coal are one vein: ores of the same kind, with or without the deepslate prefix. */
    static boolean sameVein(Block a, Block b) {
        if (a == b) return true;
        if (!ORES.contains(a) || !ORES.contains(b)) return false;
        String ia = net.minecraft.registry.Registries.BLOCK.getId(a).getPath().replace("deepslate_", "");
        String ib = net.minecraft.registry.Registries.BLOCK.getId(b).getPath().replace("deepslate_", "");
        return ia.equals(ib);
    }

    /**
     * {@link #buried} for the whole vein. A hidden block covers its neighbours, so a
     * vein used to come out one block at a time as it was mined — each ore appearing
     * only once the one in front of it was broken, which looked broken on stream.
     * Vanilla shows the whole vein the moment any of it is in the open; so does this:
     * a block is buried only while no block of its connected vein has an open face.
     */
    static boolean veinBuried(World world, WorldChunk hint, int x, int y, int z) {
        BlockState start = peek(world, hint, x, y, z);
        if (start == null) return true;
        Block kind = start.getBlock();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        BlockPos origin = new BlockPos(x, y, z);
        queue.add(origin);
        seen.add(origin.asLong());
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction d : DIRS) {
                int nx = p.getX() + d.getOffsetX(), ny = p.getY() + d.getOffsetY(), nz = p.getZ() + d.getOffsetZ();
                BlockState n = peek(world, hint, nx, ny, nz);
                if (n == null) continue;
                if (!covers(n)) return false;
                if (sameVein(n.getBlock(), kind) && seen.size() < VEIN_LIMIT) {
                    BlockPos np = new BlockPos(nx, ny, nz);
                    if (seen.add(np.asLong())) queue.add(np);
                }
            }
        }
        return true;
    }

    /** Every block of the connected vein through {@code pos}, up to {@link #VEIN_LIMIT}. */
    static List<BlockPos> vein(World world, BlockPos pos, BlockState state) {
        List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(pos.toImmutable());
        seen.add(pos.asLong());
        while (!queue.isEmpty() && out.size() < VEIN_LIMIT) {
            BlockPos p = queue.poll();
            out.add(p);
            for (Direction d : DIRS) {
                BlockPos np = p.offset(d);
                BlockState n = peek(world, null, np.getX(), np.getY(), np.getZ());
                if (n != null && sameVein(n.getBlock(), state.getBlock()) && seen.add(np.asLong())) queue.add(np);
            }
        }
        return out;
    }

    /**
     * Whether this storage block is too far, or out of sight, for this player
     * right now. Null player (a chunk built for nobody in particular) = no.
     */
    /** Records a far disguise for this player; a world change drops what was recorded in the old one. */
    static void noteFar(ServerPlayerEntity to, BlockPos pos) {
        World w = to.getEntityWorld();
        if (FAR_WORLD.put(to.getUuid(), w) != w) FAR.remove(to.getUuid());
        FAR.computeIfAbsent(to.getUuid(), k -> new HashSet<>()).add(pos.toImmutable());
    }

    static boolean farFor(ServerPlayerEntity to, World world, BlockPos pos) {
        if (to == null || !farOn() || to.getEntityWorld() != world) return false;
        ACConfig.General g = general();
        Vec3d eye = to.getEyePos();
        Vec3d c = Vec3d.ofCenter(pos);
        double d2 = eye.squaredDistanceTo(c);
        double near = g.storageAlwaysVisibleRadius, reveal = g.storageRevealRadius;
        if (d2 <= near * near) return false;
        if (d2 > reveal * reveal) return true;
        return !(world instanceof ServerWorld sw) || !EspShield.clear(sw, eye, c);
    }

    /** What this player is told is at this position, given what is really there; records a far disguise. */
    static BlockState sent(ServerPlayerEntity to, World world, BlockPos pos, BlockState real) {
        if (isHiddenKind(real) && veinBuried(world, null, pos.getX(), pos.getY(), pos.getZ())) {
            return disguise(real, world, pos.getY());
        }
        if (isStorage(real) && farFor(to, world, pos)) {
            if (to != null) noteFar(to, pos);
            return disguise(real, world, pos.getY());
        }
        return real;
    }

    // ------------------------------------------------------------------ chunk packet

    /** Called around vanilla's per-player chunk send. */
    public static void beginChunkSend(ServerPlayerEntity to) {
        RECIPIENT.set(to);
    }

    public static void endChunkSend() {
        RECIPIENT.remove();
    }

    /** Sections for the chunk packet: a disguised copy wherever something is hidden, the original elsewhere. */
    public static ChunkSection[] sectionsForSize(WorldChunk chunk) {
        Object[] result = safeDisguised(chunk, RECIPIENT.get());
        PENDING.set(new Object[]{chunk, result[0], result[1]});
        return (ChunkSection[]) result[0];
    }

    /** The same array the size was measured on: size and bytes must agree to the byte. */
    public static ChunkSection[] sectionsForWrite(WorldChunk chunk) {
        Object[] p = PENDING.get();
        if (p != null && p[0] == chunk) return (ChunkSection[]) p[1];
        Object[] result = safeDisguised(chunk, RECIPIENT.get());
        PENDING.set(new Object[]{chunk, result[0], result[1]});
        return (ChunkSection[]) result[0];
    }

    private static boolean failureLogged;

    /**
     * {@link #disguised}, but a failure never costs the player their world:
     * the chunk goes out as it is, and the first failure is logged.
     */
    static Object[] safeDisguised(WorldChunk chunk, ServerPlayerEntity to) {
        try {
            return disguised(chunk, to);
        } catch (RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                BastionAC.LOGGER.error("[SHIELD] chunk {} sent unshielded", chunk.getPos(), e);
            }
            return new Object[]{chunk.getSectionArray(), new HashSet<BlockPos>()};
        }
    }

    /**
     * Returns {sections, hidden positions}: a copy of each section that holds
     * something disguised, the original otherwise.
     */
    static Object[] disguised(WorldChunk chunk, ServerPlayerEntity to) {
        ChunkSection[] sections = chunk.getSectionArray();
        Set<BlockPos> hidden = new HashSet<>();
        if (!anyOn()) return new Object[]{sections, hidden};
        World world = chunk.getWorld();
        int baseX = chunk.getPos().getStartX(), baseZ = chunk.getPos().getStartZ();
        Map<Integer, List<int[]>> bySection = new HashMap<>();

        if (oresOn() || containersOn()) {
            for (int i = 0; i < sections.length; i++) {
                ChunkSection s = sections[i];
                if (s == null || s.isEmpty() || !s.hasAny(OreShield::isHiddenKind)) continue;
                int baseY = ChunkSectionPos.getBlockCoord(chunk.sectionIndexToCoord(i));
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!isHiddenKind(s.getBlockState(x, y, z))) continue;
                            if (!buriedInSection(world, chunk, s, baseX, baseY, baseZ, x, y, z)) continue;
                            if (!veinBuried(world, chunk, baseX + x, baseY + y, baseZ + z)) continue;
                            bySection.computeIfAbsent(i, k -> new ArrayList<>()).add(new int[]{x, y, z});
                            hidden.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
        if (to != null && farOn()) {
            // Storage is rare (a handful per chunk): its block entities say where it is.
            for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                if (hidden.contains(pos)) continue;
                BlockState st = chunk.getBlockState(pos);
                if (!isStorage(st) || !farFor(to, world, pos)) continue;
                int i = chunk.getSectionIndex(pos.getY());
                if (i < 0 || i >= sections.length) continue;
                bySection.computeIfAbsent(i, k -> new ArrayList<>())
                        .add(new int[]{pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15});
                BlockPos imm = pos.toImmutable();
                hidden.add(imm);
                noteFar(to, imm);
            }
        }
        if (bySection.isEmpty()) return new Object[]{sections, hidden};
        ChunkSection[] out = sections.clone();
        for (Map.Entry<Integer, List<int[]>> e : bySection.entrySet()) {
            int i = e.getKey();
            ChunkSection s = sections[i];
            int baseY = ChunkSectionPos.getBlockCoord(chunk.sectionIndexToCoord(i));
            ChunkSection copy = s.copy();
            for (int[] p : e.getValue()) {
                BlockState st = s.getBlockState(p[0], p[1], p[2]);
                copy.setBlockState(p[0], p[1], p[2], disguise(st, world, baseY + p[1]), false);
            }
            out[i] = copy;
        }
        return new Object[]{out, hidden};
    }

    /** As {@link #buried}, reading the section itself for the neighbours inside it. */
    private static boolean buriedInSection(World world, WorldChunk chunk, ChunkSection s,
                                           int baseX, int baseY, int baseZ, int x, int y, int z) {
        for (Direction d : DIRS) {
            int nx = x + d.getOffsetX(), ny = y + d.getOffsetY(), nz = z + d.getOffsetZ();
            BlockState n = (nx | ny | nz) >= 0 && nx < 16 && ny < 16 && nz < 16
                    ? s.getBlockState(nx, ny, nz)
                    : peek(world, chunk, baseX + nx, baseY + ny, baseZ + nz);
            if (n != null && !covers(n)) return false;
        }
        return true;
    }

    /** Block entities for the chunk packet, without the ones whose block goes out disguised. */
    @SuppressWarnings("unchecked")
    public static Map<BlockPos, BlockEntity> blockEntities(WorldChunk chunk, Map<BlockPos, BlockEntity> all) {
        Object[] p = PENDING.get();
        PENDING.remove();
        Set<BlockPos> hidden = p != null && p[0] == chunk ? (Set<BlockPos>) p[2] : (Set<BlockPos>) safeDisguised(chunk, RECIPIENT.get())[1];
        if (hidden.isEmpty() || all.isEmpty()) return all;
        Map<BlockPos, BlockEntity> out = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockEntity> e : all.entrySet()) {
            if (!hidden.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ------------------------------------------------------------------ single blocks

    /** Block packets as this recipient may see them (called from the info shield's send hook). */
    public static Packet<?> rewrite(ServerPlayerEntity to, Packet<?> packet) {
        if (!anyOn()) return packet;
        World world = to.getEntityWorld();
        if (packet instanceof BlockUpdateS2CPacket bu) {
            BlockState shown = sent(to, world, bu.getPos(), bu.getState());
            return shown == bu.getState() ? packet : new BlockUpdateS2CPacket(bu.getPos(), shown);
        }
        if (packet instanceof ChunkDeltaUpdateS2CPacket delta) {
            ChunkDeltaUpdateAccessor acc = (ChunkDeltaUpdateAccessor) delta;
            ChunkSectionPos sp = acc.bastionac$sectionPos();
            short[] positions = acc.bastionac$positions();
            BlockState[] states = acc.bastionac$states();
            // The same packet object goes to every player watching the
            // section. The buried rule is the world's and the same for all, so
            // it is written into the shared array; a far disguise is this
            // player's alone and turns the packet into single updates for them.
            List<Packet<? super ClientPlayPacketListener>> personal = null;
            for (int i = 0; i < states.length; i++) {
                BlockPos pos = sp.unpackBlockPos(positions[i]);
                if (isHiddenKind(states[i]) && buried(world, null, pos.getX(), pos.getY(), pos.getZ())) {
                    states[i] = disguise(states[i], world, pos.getY());
                } else if (isStorage(states[i]) && farFor(to, world, pos)) {
                    if (personal == null) personal = new ArrayList<>();
                    noteFar(to, pos);
                }
            }
            if (personal == null) return packet;
            for (int i = 0; i < states.length; i++) {
                BlockPos pos = sp.unpackBlockPos(positions[i]);
                BlockState shown = isStorage(states[i]) && farFor(to, world, pos) ? disguise(states[i], world, pos.getY()) : states[i];
                personal.add(new BlockUpdateS2CPacket(pos, shown));
            }
            return new BundleS2CPacket(personal);
        }
        if (packet instanceof BlockEntityUpdateS2CPacket be) {
            BlockPos pos = be.getPos();
            BlockState real = peek(world, null, pos.getX(), pos.getY(), pos.getZ());
            if (real == null || !(isHiddenKind(real) || isStorage(real))) return packet;
            BlockState shown = sent(to, world, pos, real);
            return shown == real ? packet : new BlockUpdateS2CPacket(pos, shown);
        }
        if (packet instanceof BlockEventS2CPacket ev) {
            // A chest lid opening is a block event: it would mark a hidden chest.
            BlockPos pos = ev.getPos();
            BlockState real = peek(world, null, pos.getX(), pos.getY(), pos.getZ());
            if (real == null || !(isHiddenKind(real) || isStorage(real))) return packet;
            BlockState shown = sent(to, world, pos, real);
            return shown == real ? packet : new BlockUpdateS2CPacket(pos, shown);
        }
        return packet;
    }

    // ------------------------------------------------------------------ reveal

    /**
     * A block changed in the world. If it stopped covering, every hidden-kind
     * neighbour is sent again; the filter decides what each one now shows.
     */
    public static void onBlockChanged(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!(oresOn() || containersOn())) return;
        if (!covers(oldState) || covers(newState)) return;
        for (Direction d : DIRS) {
            int x = pos.getX() + d.getOffsetX(), y = pos.getY() + d.getOffsetY(), z = pos.getZ() + d.getOffsetZ();
            BlockState n = peek(world, null, x, y, z);
            if (n != null && isHiddenKind(n)) {
                // The whole vein comes into the open with it, not just this block.
                for (BlockPos member : vein(world, new BlockPos(x, y, z), n)) {
                    world.getChunkManager().markForUpdate(member);
                }
            }
        }
    }

    /** Every {@code storageCheckIntervalTicks}: far storage that came into reach or sight is sent as it is. */
    public static void tick(MinecraftServer server, long tick) {
        ACConfig.General g = general();
        if (g == null || FAR.isEmpty()) return;
        if (tick % Math.max(1, g.storageCheckIntervalTicks) != 0) return;
        for (Iterator<Map.Entry<UUID, Set<BlockPos>>> it = FAR.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Set<BlockPos>> e = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            World world = p.getEntityWorld();
            if (FAR_WORLD.get(e.getKey()) != world) {
                FAR_WORLD.remove(e.getKey());
                it.remove();
                continue;
            }
            for (Iterator<BlockPos> pit = e.getValue().iterator(); pit.hasNext(); ) {
                BlockPos pos = pit.next();
                BlockState real = peek(world, null, pos.getX(), pos.getY(), pos.getZ());
                if (real == null || !isStorage(real)) {
                    pit.remove();   // gone, or its chunk unloaded: a later send judges it again
                    continue;
                }
                if (farOn() && farFor(p, world, pos)) continue;
                pit.remove();
                if (buried(world, null, pos.getX(), pos.getY(), pos.getZ()) && containersOn()) continue;
                p.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, real));
                BlockEntity be = world.getBlockEntity(pos);
                if (be != null) {
                    Packet<ClientPlayPacketListener> data = be.toUpdatePacket();
                    if (data != null) p.networkHandler.sendPacket(data);
                }
            }
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    /** The client dropped a chunk: what was waiting in it is judged again when it is sent again. */
    public static void onChunkUnload(ServerPlayerEntity player, ChunkPos pos) {
        Set<BlockPos> set = FAR.get(player.getUuid());
        if (set == null) return;
        set.removeIf(p -> (p.getX() >> 4) == pos.x && (p.getZ() >> 4) == pos.z);
    }

    /** A player left or changed worlds. */
    public static void forget(ServerPlayerEntity player) {
        FAR.remove(player.getUuid());
        FAR_WORLD.remove(player.getUuid());
    }
}
