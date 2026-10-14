package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Keeps explosions out of protected claims, for blocks and entities alike.
 * The mob-vs-other distinction is made from the explosion's source entity.
 */
@Mixin(ExplosionImpl.class)
public abstract class ExplosionImplMixin {

    @Shadow
    private ServerWorld world;

    @Shadow
    private Entity entity;

    /**
     * Filters the affected-block list at the source. {@code explode()} hands the
     * same list to both {@code destroyBlocks} and {@code createFire}, so this one
     * cut covers breaking, wind-charge toggling of doors and buttons
     * ({@code onExploded}) and the fire an incendiary blast leaves behind — the
     * old hook on {@code destroyBlocks} never ran when the blast was not allowed
     * to break blocks, and its fire then landed inside claims unfiltered.
     */
    @Inject(method = "getBlocksToDestroy", at = @At("RETURN"))
    private void bastionclaims$protectClaims(CallbackInfoReturnable<List<BlockPos>> cir) {
        cir.getReturnValue().removeIf(pos -> ProtectionService.shouldProtectExplosion(world, entity, pos));
    }

    /**
     * Treats protected entities as immune to this blast: vanilla then skips both
     * the damage and the knockback. The damage hooks alone could not do this —
     * an armour stand's {@code damage()} never reaches Fabric's event, and even
     * a protected entity was still thrown out of the claim by the shock wave.
     */
    @Redirect(method = "damageEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;isImmuneToExplosion(Lnet/minecraft/world/explosion/Explosion;)Z"))
    private boolean bastionclaims$spareClaimEntities(Entity victim, Explosion explosion) {
        return victim.isImmuneToExplosion(explosion)
                || ProtectionService.explosionSparesEntity(world, entity, victim);
    }
}
