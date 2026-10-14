package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sand, gravel, concrete powder or an anvil that started outside a claim
 * cannot come to rest inside it — poured from above a 3D claim or launched
 * over the border. Refused, it breaks into its item exactly as it does when
 * vanilla finds no room. (The damage it deals is handled in
 * {@code ProtectionService.entityDamageBlocked}.)
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"))
    private boolean bastionclaims$guardLanding(World world, BlockPos pos, BlockState state, int flags) {
        FallingBlockEntity self = (FallingBlockEntity) (Object) this;
        if (!world.isClient() && ProtectionService.fallingBlockBlocked(world, pos, self.getFallingBlockPos())) {
            return false;
        }
        return world.setBlockState(pos, state, flags);
    }
}
