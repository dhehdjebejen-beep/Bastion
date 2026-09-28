package dev.bastionclaims.util;

import dev.bastionclaims.model.Claim;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Set;

/** Safe teleport into a claim: lands the player on a real floor, never in the air. */
public final class Teleports {

    /** How far out from the centre we look for a landing spot before giving up. */
    private static final int SEARCH_RADIUS = 8;

    /**
     * Teleports {@code p} to a standable spot inside the claim.
     *
     * @return false when the claim has no safe spot at all — the caller should
     *         say so rather than move the player. The old code fell back to the
     *         claim's ceiling, which in a full-height claim meant dropping the
     *         player from build height.
     */
    public static boolean toClaimSafely(ServerPlayerEntity p, ServerWorld world, Claim c) {
        int worldTop = world.getBottomY() + world.getHeight() - 1;
        int top = Math.min(c.maxY, worldTop);
        int bottom = Math.max(c.minY, world.getBottomY());
        int cx = (c.minX + c.maxX) / 2;
        int cz = (c.minZ + c.maxZ) / 2;

        // Centre first, then a widening ring — a base can easily have its middle
        // column filled while the rest of the claim is perfectly walkable.
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue; // ring only
                    int x = cx + dx, z = cz + dz;
                    if (x < c.minX || x > c.maxX || z < c.minZ || z > c.maxZ) continue;
                    BlockPos safe = findSafe(world, x, z, top, bottom);
                    if (safe != null) {
                        p.teleport(world, x + 0.5, safe.getY(), z + 0.5, Set.of(), p.getYaw(), p.getPitch(), false);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Highest position in the column with two clear blocks over a solid, non-fluid floor. */
    private static BlockPos findSafe(ServerWorld world, int x, int z, int top, int bottom) {
        for (int y = top; y >= bottom; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            BlockState fs = world.getBlockState(feet);
            BlockState hs = world.getBlockState(feet.up());
            BlockState gs = world.getBlockState(feet.down());
            boolean bodyClear = fs.isAir() && hs.isAir();
            boolean floorSolid = !gs.isAir() && gs.getFluidState().isEmpty();
            if (bodyClear && floorSolid) return feet;
        }
        return null;
    }

    private Teleports() {}
}
