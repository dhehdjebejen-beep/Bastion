package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.LavaFluid;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lava lights the air next to flammable blocks a few blocks around it on its
 * random tick. A natural lava pool on spawn kept setting the grass around it
 * alight: nothing burnt, but fire appeared and slowly crept. Inside a claim
 * that does not allow fire the lava simply sets none; elsewhere it is vanilla.
 */
@Mixin(LavaFluid.class)
public abstract class LavaFireMixin {

    @Redirect(method = "onRandomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Z"))
    private boolean bastionclaims$noLavaFireInClaims(ServerWorld world, BlockPos pos, BlockState state) {
        String dim = world.getRegistryKey().getValue().toString();
        if (ProtectionService.fireSpreadBlocked(dim, pos.getX(), pos.getY(), pos.getZ())) return false;
        return world.setBlockState(pos, state);
    }
}
