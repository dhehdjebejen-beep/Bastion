package dev.bastionac.mixin;

import dev.bastionac.core.EspShield;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla decides here whether a player's client tracks an entity. For a
 * player or a dropped item the ESP shield (3.6) can say no: the target is then
 * removed from that client as if it had gone out of range, and sent again as
 * soon as the shield lets it through.
 */
@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public abstract class EntityTrackerShieldMixin implements EspShield.Recheck {

    @Shadow @Final Entity entity;

    @Shadow public abstract void updateTrackedStatus(ServerPlayerEntity player);

    @Shadow public abstract void stopTracking(ServerPlayerEntity player);

    @Inject(method = "updateTrackedStatus(Lnet/minecraft/server/network/ServerPlayerEntity;)V",
            at = @At("HEAD"), cancellable = true)
    private void bastionac$espShield(ServerPlayerEntity player, CallbackInfo ci) {
        if (this.entity == player || !EspShield.judges(this.entity)) return;
        if (!EspShield.allowed(player, this.entity)) {
            this.stopTracking(player);
            ci.cancel();
        }
    }

    @Override
    public void bastionac$recheck(ServerPlayerEntity viewer) {
        this.updateTrackedStatus(viewer);
    }
}
