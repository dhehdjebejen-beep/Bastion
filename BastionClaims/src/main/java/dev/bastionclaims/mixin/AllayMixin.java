package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An allay collects the item it was given from the ground and carries it to
 * the player who gave it (1.20). It flies over a wall that stops a person, so
 * a stranger's allay emptied the floor of a farm inside a claim. It now picks
 * up an item lying in a claim only for a player with the {@code entities}
 * permission there — the rule the fishing rod already follows for items.
 */
@Mixin(AllayEntity.class)
public abstract class AllayMixin {

    @Inject(method = "loot", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$guardClaim(ServerWorld world, ItemEntity item, CallbackInfo ci) {
        if (ProtectionService.allayPickupBlocked((AllayEntity) (Object) this, item)) ci.cancel();
    }
}
