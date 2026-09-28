package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.ExplosionImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Removes blocks inside protected claims from an explosion's destruction list
 * before it is applied, so TNT and creepers cannot grief claimed land. The
 * mob-vs-other distinction is made from the explosion's source entity.
 */
@Mixin(ExplosionImpl.class)
public abstract class ExplosionImplMixin {

    @Shadow
    private ServerWorld world;

    @Shadow
    private Entity entity;

    @Inject(method = "destroyBlocks", at = @At("HEAD"))
    private void bastionclaims$protectClaims(List<BlockPos> blocks, CallbackInfo ci) {
        blocks.removeIf(pos -> ProtectionService.shouldProtectExplosion(world, entity, pos));
    }
}
