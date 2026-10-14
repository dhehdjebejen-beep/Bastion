package dev.bastionclaims.core;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;

/**
 * State for the sculk guard mixins: which catalyst's spread is being applied
 * on this thread. It lives outside the mixin package because mixin classes
 * cannot be referenced at runtime.
 */
public final class SculkGuard {

    private static final ThreadLocal<BlockPos> CATALYST = new ThreadLocal<>();

    private SculkGuard() {}

    public static void enter(BlockPos catalyst) {
        CATALYST.set(catalyst.toImmutable());
    }

    public static void leave() {
        CATALYST.remove();
    }

    /** Whether sculk may not change the block at {@code pos} (server worlds only; world generation is left alone). */
    public static boolean blocked(WorldAccess world, BlockPos pos) {
        return world instanceof ServerWorld sw && ProtectionService.sculkSpreadBlocked(sw, pos, CATALYST.get());
    }
}
