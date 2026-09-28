package dev.bastionauth.mixin;

import dev.bastionauth.core.DeviceFingerprint;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.server.network.ServerHandshakeNetworkHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the exact server address string the client connected with
 * ({@code play.example.com} vs {@code 1.2.3.4:36651} vs a trailing dot) and
 * its protocol version. A bookmark habit rather than a device — a weak link
 * signal on its own, but one that survives reinstalls and network changes.
 * Parked by connection object and claimed on join.
 */
@Mixin(ServerHandshakeNetworkHandler.class)
public abstract class HandshakeCaptureMixin {
    @Shadow @Final private ClientConnection connection;

    @Inject(method = "onHandshake", at = @At("HEAD"))
    private void bastionauth$captureHandshake(HandshakeC2SPacket packet, CallbackInfo ci) {
        DeviceFingerprint.noteHandshake(this.connection, packet.address(), packet.port(), packet.protocolVersion());
    }
}
