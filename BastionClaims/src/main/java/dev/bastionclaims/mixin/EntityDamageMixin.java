package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.BlockAttachedEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Claim protection for the entities Fabric's damage event cannot reach.
 *
 * <p>{@code ServerLivingEntityEvents.ALLOW_DAMAGE} only fires for living
 * entities, which leaves out exactly the things people put in a base and lose
 * to a stranger with a bow: item frames full of gear, paintings, chest
 * minecarts and boats. {@code Entity.damage} itself is abstract, so the hook
 * goes on the three concrete declarations instead — {@link ItemFrameEntity}
 * overrides {@link BlockAttachedEntity}'s, hence both are listed.
 *
 * <p>Living entities (animals, armour stands, villagers) are handled through
 * the Fabric event in {@code ClaimEvents}; both paths ask the same question.
 */
@Mixin({BlockAttachedEntity.class, ItemFrameEntity.class, VehicleEntity.class})
public abstract class EntityDamageMixin {

    @Inject(method = "damage(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/damage/DamageSource;F)Z",
            at = @At("HEAD"), cancellable = true)
    private void bastionclaims$protectClaimEntities(ServerWorld world, DamageSource source, float amount,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (ProtectionService.entityDamageBlocked((Entity) (Object) this, source)) {
            cir.setReturnValue(false);
        }
    }
}
