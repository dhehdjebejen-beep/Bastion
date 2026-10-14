package dev.bastionclaims.mixin;

import dev.bastionclaims.core.SculkGuard;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.SculkSpreadManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sculk from a catalyst outside a claim does not grow into it. The catalyst's
 * position is remembered while its spread manager ticks; every block change
 * sculk makes (sculk, veins, sensors, shriekers) is checked against it. A
 * catalyst inside the claim — the owner's own sculk farm — works as before;
 * world generation (ancient cities, a {@code ChunkRegion}) is untouched.
 */
public final class SculkSpreadMixins {

    // State and the decision live in core.SculkGuard: a mixin class (this
    // holder included) cannot be referenced from code at runtime.
    private SculkSpreadMixins() {}

    @Mixin(SculkSpreadManager.class)
    public abstract static class Manager {
        @Inject(method = "tick", at = @At("HEAD"))
        private void bastionclaims$enter(WorldAccess world, BlockPos pos, Random random, boolean shouldConvert,
                                         CallbackInfo ci) {
            SculkGuard.enter(pos);
        }

        @Inject(method = "tick", at = @At("RETURN"))
        private void bastionclaims$leave(WorldAccess world, BlockPos pos, Random random, boolean shouldConvert,
                                         CallbackInfo ci) {
            SculkGuard.leave();
        }
    }

    @Mixin(net.minecraft.block.SculkBlock.class)
    public abstract static class Block {
        @Redirect(method = "spread", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/WorldAccess;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"))
        private boolean bastionclaims$guard(WorldAccess world, BlockPos pos, BlockState state, int flags) {
            if (SculkGuard.blocked(world, pos)) return false;
            return world.setBlockState(pos, state, flags);
        }
    }

    @Mixin(net.minecraft.block.SculkVeinBlock.class)
    public abstract static class Vein {
        // place() is static, the other two are instance methods: a handler
        // must match its target, hence two redirects.
        @Redirect(method = "place", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/WorldAccess;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"))
        private static boolean bastionclaims$guardPlace(WorldAccess world, BlockPos pos, BlockState state, int flags) {
            if (SculkGuard.blocked(world, pos)) return false;
            return world.setBlockState(pos, state, flags);
        }

        @Redirect(method = {"spreadAtSamePosition", "convertToBlock"}, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/WorldAccess;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"))
        private boolean bastionclaims$guard(WorldAccess world, BlockPos pos, BlockState state, int flags) {
            if (SculkGuard.blocked(world, pos)) return false;
            return world.setBlockState(pos, state, flags);
        }
    }
}
