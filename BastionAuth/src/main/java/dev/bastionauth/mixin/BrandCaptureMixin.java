package dev.bastionauth.mixin;

import dev.bastionauth.core.DeviceFingerprint;
import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brand capture for the device fingerprint. Vanilla 1.21.11 reads the
 * {@code minecraft:brand} plugin message and throws it away; we keep a copy
 * per connection so {@link DeviceFingerprint#claimPendingBrand} can fold it
 * into the device hash. The payload arrives during the configuration phase,
 * before the player object exists — it is parked keyed by the connection it
 * arrived on (the handler's own connection object, stable across the
 * configuration→play handover), which is what keeps two simultaneous joins
 * from claiming each other's brands.
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class BrandCaptureMixin {

    @Inject(method = "onCustomPayload", at = @At("HEAD"))
    private void bastionauth$captureBrand(CustomPayloadC2SPacket packet, CallbackInfo ci) {
        if (ci.isCancellable()) return;   // not cancellable here; tap only
        CustomPayload payload = packet.payload();
        if (payload instanceof BrandCustomPayload brand) {
            // The mixin instance IS the connection-scoped handler: one
            // ServerCommonNetworkHandler per client connection, alive from
            // the first configuration packet to the last play packet. Using
            // it as the parking key is what makes the brand handoff immune
            // to two simultaneous joins claiming each other's strings.
            DeviceFingerprint.noteBrandPayload(this, brand.brand());
        }
    }
}
