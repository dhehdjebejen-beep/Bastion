package dev.bastionauth.mixin;

import dev.bastionauth.BastionAuth;
import dev.bastionauth.core.AuthManager;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents unauthenticated (frozen) players from vacuuming up dropped items. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onPlayerCollision(PlayerEntity player, CallbackInfo ci) {
        AuthManager m = BastionAuth.manager();
        if (m == null || !m.isBlockItemPickup()) return;
        if (player instanceof ServerPlayerEntity p && m.isBlocked(p)) ci.cancel();
    }
}
