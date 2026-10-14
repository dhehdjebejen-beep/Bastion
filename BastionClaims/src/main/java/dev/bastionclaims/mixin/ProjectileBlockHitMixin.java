package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A projectile from someone who may not build in the claim does not get to
 * act on the block it hits there. {@code BlockState.onProjectileHit} is the
 * one place every block reacts to projectiles: a burning arrow priming TNT,
 * lighting a campfire or candles; an arrow ringing a bell, powering a target
 * block, breaking pointed dripstone, a chorus flower or a decorated pot. The
 * projectile itself still lands — only the block's reaction is skipped.
 */
@Mixin(ProjectileEntity.class)
public abstract class ProjectileBlockHitMixin {

    @Redirect(method = "onBlockHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/BlockState;onProjectileHit(Lnet/minecraft/world/World;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/hit/BlockHitResult;Lnet/minecraft/entity/projectile/ProjectileEntity;)V"))
    private void bastionclaims$guardBlockReaction(BlockState target, World world, BlockState state,
                                                  BlockHitResult hit, ProjectileEntity projectile) {
        if (!world.isClient()
                && ProtectionService.projectileBlockedAt(world, hit.getBlockPos(), projectile.getOwner())) {
            return;
        }
        target.onProjectileHit(world, state, hit, projectile);
    }
}
