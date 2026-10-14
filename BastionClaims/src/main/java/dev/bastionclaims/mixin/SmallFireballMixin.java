package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A fire charge (from a dispenser or a blaze) sets fire where it hits; inside
 * someone's claim it only does so when its owner may build there.
 */
@Mixin(SmallFireballEntity.class)
public abstract class SmallFireballMixin {

    @Redirect(method = "onBlockHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Z"))
    private boolean bastionclaims$guardFire(World world, BlockPos pos, BlockState state) {
        SmallFireballEntity self = (SmallFireballEntity) (Object) this;
        if (ProtectionService.projectileBlockedAt(world, pos, self.getOwner())) return false;
        return world.setBlockState(pos, state);
    }
}
