package dev.bastionac.argus;

import dev.bastionac.BastionAC;
import dev.bastionac.core.XrayTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Reads a box of the world for the X-ray analysis — on the server thread,
 * a slice per tick, only from chunks that are already loaded (it never
 * loads one). The analyzer thread asks and waits; the world is never touched
 * off the server thread.
 */
public final class WorldSampler {

    /** The analysis never asks for more than this many cells. */
    public static final long MAX_CELLS = 450_000;

    private static final class Job {
        final ServerWorld world;
        final WorldSample sample;
        final CompletableFuture<WorldSample> done = new CompletableFuture<>();
        /** Written by the server thread, read by the Argus thread for its progress. */
        volatile int next;

        Job(ServerWorld world, WorldSample sample) {
            this.world = world;
            this.sample = sample;
        }
    }

    private static final ConcurrentLinkedQueue<Job> QUEUE = new ConcurrentLinkedQueue<>();

    private WorldSampler() {}

    /** Any thread: queue a box of the overworld; completes on the server thread when read. */
    public static CompletableFuture<WorldSample> request(int x0, int y0, int z0, int x1, int y1, int z1) {
        MinecraftServer srv = BastionAC.server();
        if (srv == null) return CompletableFuture.completedFuture(null);
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz > MAX_CELLS) {
            return CompletableFuture.completedFuture(null);
        }
        Job job = new Job(srv.getOverworld(), new WorldSample(x0, y0, z0, sx, sy, sz));
        QUEUE.add(job);
        return job.done;
    }

    /** Server thread, every tick: read up to the configured number of cells. */
    public static void tick() {
        Job job = QUEUE.peek();
        if (job == null) return;
        int budget = BastionAC.config().argus.worldSampleBlocksPerTick;
        WorldSample w = job.sample;
        int total = w.cells.length;
        BlockPos.Mutable m = new BlockPos.Mutable();
        try {
            while (budget-- > 0 && job.next < total) {
                int i = job.next++;
                int dz = i % w.sz;
                int rest = i / w.sz;
                int dy = rest % w.sy;
                int dx = rest / w.sy;
                int x = w.x0 + dx, y = w.y0 + dy, z = w.z0 + dz;
                if (!job.world.isChunkLoaded(x >> 4, z >> 4)) continue;   // stays UNKNOWN
                m.set(x, y, z);
                w.cells[i] = (byte) XrayTracker.contextOf(job.world.getBlockState(m));
            }
        } catch (Throwable t) {
            QUEUE.poll();
            job.done.complete(null);
            return;
        }
        if (job.next >= total) {
            QUEUE.poll();
            job.done.complete(w);
        }
    }

    public static int pending() {
        return QUEUE.size();
    }

    /**
     * How far the request behind {@code future} has got: {cells read, cells in
     * all, requests before it}; null when it is no longer queued.
     */
    public static int[] progressOf(CompletableFuture<?> future) {
        int before = 0;
        for (Job j : QUEUE) {
            if (j.done == future) return new int[]{j.next, j.sample.cells.length, before};
            before++;
        }
        return null;
    }
}
