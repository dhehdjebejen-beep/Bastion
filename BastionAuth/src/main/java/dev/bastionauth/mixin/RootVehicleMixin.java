package dev.bastionauth.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vehicle a player logged out on is loaded right after the join — and
 * vanilla seats the player on it, which moves them (and the camera) to where
 * they really are. In limbo it is held in memory instead, out of the world,
 * and every save writes it back into the player's file; the rider sits back
 * down after the password. See {@link dev.bastionauth.core.Limbo#deferVehicle}.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class RootVehicleMixin {

    @Inject(method = "readRootVehicle", at = @At("HEAD"), cancellable = true)
    private void bastionauth$deferVehicle(ReadView view, CallbackInfo ci) {
        if (dev.bastionauth.core.Limbo.deferVehicle((ServerPlayerEntity) (Object) this, view)) ci.cancel();
    }

    @Inject(method = "writeRootVehicle", at = @At("HEAD"), cancellable = true)
    private void bastionauth$writeHeldVehicle(WriteView view, CallbackInfo ci) {
        if (dev.bastionauth.core.Limbo.writeHeldVehicle((ServerPlayerEntity) (Object) this, view)) ci.cancel();
    }
}
