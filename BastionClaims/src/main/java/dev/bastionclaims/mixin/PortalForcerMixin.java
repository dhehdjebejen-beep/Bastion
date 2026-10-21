package dev.bastionclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bastionclaims.core.PortalPlan;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.dimension.PortalForcer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The blocks a generated Nether portal writes (frame, platform, the air it
 * clears), recorded instead of written while {@link PortalPlan} is planning.
 * See {@link NetherPortalBlockMixin}.
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {

    @WrapOperation(method = "createPortal", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Z"))
    private boolean bastionclaims$plan(ServerWorld world, BlockPos pos, BlockState state, Operation<Boolean> original) {
        if (PortalPlan.record(pos, state, net.minecraft.block.Block.NOTIFY_ALL)) return true;
        return original.call(world, pos, state);
    }

    @WrapOperation(method = "createPortal", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"))
    private boolean bastionclaims$plan(ServerWorld world, BlockPos pos, BlockState state, int flags,
                                       Operation<Boolean> original) {
        if (PortalPlan.record(pos, state, flags)) return true;
        return original.call(world, pos, state, flags);
    }
}
