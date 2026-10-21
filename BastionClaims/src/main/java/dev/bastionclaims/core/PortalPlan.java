package dev.bastionclaims.core;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The blocks a generated Nether portal would write, collected before any is
 * written (1.20). Server thread only; one plan at a time.
 */
public final class PortalPlan {

    /** One block the portal would write, with vanilla's flags. */
    public record Cell(BlockPos pos, BlockState state, int flags) {}

    private static List<Cell> planning;
    private static boolean refused;

    private PortalPlan() {}

    public static void begin() {
        planning = new ArrayList<>();
    }

    /** Whether a plan is being made: then the block is recorded here instead of written. */
    public static boolean record(BlockPos pos, BlockState state, int flags) {
        if (planning == null) return false;
        planning.add(new Cell(pos.toImmutable(), state, flags));
        return true;
    }

    public static List<Cell> end() {
        List<Cell> out = planning == null ? List.of() : planning;
        planning = null;
        return out;
    }

    /** Writes the plan in the order vanilla wrote it. */
    public static void write(ServerWorld world, List<Cell> cells) {
        for (Cell c : cells) world.setBlockState(c.pos(), c.state(), c.flags());
    }

    public static void markRefused() {
        refused = true;
    }

    /** Whether the portal just failed because of a claim (consumed). */
    public static boolean takeRefused() {
        boolean r = refused;
        refused = false;
        return r;
    }
}
