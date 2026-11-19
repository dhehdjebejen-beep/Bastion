package dev.bastionclaims.core;

import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Before the fire fix a fire inside a protected claim had its tick cancelled
 * and was never ticked again, so the fires lava and lightning had already
 * left on spawn and in claims would stand forever. When a chunk loads, every
 * such fire in it gets one scheduled tick; {@code FireBlockMixin} then puts it
 * out. Sections without fire are skipped by their palette, so this costs
 * nothing for ordinary chunks.
 *
 * <p>The load itself only reads the chunk's own sections. The world is not
 * touched until the next server tick: asking the world for a block of a chunk
 * that is still loading waits for that very load and hangs the server.
 */
public final class StaleFireSweep {
    private StaleFireSweep() {}

    private record Found(ServerWorld world, List<BlockPos> fires) {}

    private static final Queue<Found> QUEUE = new ArrayDeque<>();

    public static void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        List<BlockPos> fires = null;
        ChunkSection[] sections = chunk.getSectionArray();
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(s -> s.isOf(Blocks.FIRE))) continue;
            int baseY = chunk.sectionIndexToCoord(i) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!section.getBlockState(x, y, z).isOf(Blocks.FIRE)) continue;
                if (fires == null) fires = new ArrayList<>();
                fires.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
            }
        }
        if (fires == null) return;
        synchronized (QUEUE) {
            QUEUE.add(new Found(world, fires));
        }
    }

    /** Called at the end of every server tick, on the server thread. */
    public static void tick(MinecraftServer server) {
        for (int budget = 64; budget > 0; budget--) {
            Found found;
            synchronized (QUEUE) {
                found = QUEUE.poll();
            }
            if (found == null) return;
            ServerWorld world = found.world();
            String dim = world.getRegistryKey().getValue().toString();
            int delay = 1;
            for (BlockPos pos : found.fires()) {
                if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                if (!world.getBlockState(pos).isOf(Blocks.FIRE)) continue;
                if (!ProtectionService.fireSpreadBlocked(dim, pos.getX(), pos.getY(), pos.getZ())) continue;
                world.scheduleBlockTick(pos, Blocks.FIRE, delay);
                delay = delay % 20 + 1;
            }
        }
    }
}
