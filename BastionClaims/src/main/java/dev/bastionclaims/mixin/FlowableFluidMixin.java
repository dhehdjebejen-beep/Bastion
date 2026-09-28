package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops liquids (water and lava) from creeping into a claim from outside its
 * border. {@code flow} is called for every cell a fluid tries to spread into;
 * {@code pos} is the destination and {@code direction} points from the source
 * toward it, so the source cell is {@code pos.offset(direction.getOpposite())}.
 * A flow is cancelled only when the destination is protected and its source lies
 * outside the same claim — the owner's own liquids still spread normally.
 */
@Mixin(FlowableFluid.class)
public abstract class FlowableFluidMixin {

    @Inject(method = "flow", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$blockExternalInflow(WorldAccess world, BlockPos pos, BlockState state,
                                                   Direction direction, FluidState fluidState, CallbackInfo ci) {
        if (!(world instanceof ServerWorld sw)) return;
        BlockPos src = pos.offset(direction.getOpposite());
        String dim = sw.getRegistryKey().getValue().toString();
        if (ProtectionService.fluidFlowBlocked(dim, pos.getX(), pos.getY(), pos.getZ(),
                src.getX(), src.getY(), src.getZ())) {
            ci.cancel();
        }
    }
}
