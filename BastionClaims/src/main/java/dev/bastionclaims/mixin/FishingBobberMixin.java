package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A fishing rod does not reel a claim's animals, villagers, armour stands or
 * items out over the border for someone without the {@code entities}
 * permission there (see {@link ProtectionService#fishingPullBlocked}).
 */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberMixin {

    @Inject(method = "pullHookedEntity", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$guardPull(Entity hooked, CallbackInfo ci) {
        FishingBobberEntity self = (FishingBobberEntity) (Object) this;
        if (!self.getEntityWorld().isClient() && ProtectionService.fishingPullBlocked(hooked, self.getOwner())) {
            ci.cancel();
        }
    }
}
