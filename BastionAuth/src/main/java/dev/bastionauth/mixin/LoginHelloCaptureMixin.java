package dev.bastionauth.mixin;

import dev.bastionauth.core.DeviceFingerprint;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the profile UUID the client claims for itself in the login
 * hello. An offline server ignores it and derives its own from the name,
 * but many cracked launchers generate a random UUID once per installation
 * and send that same UUID under every nickname — when it differs from the
 * name-derived one, it is the closest thing to a hardware id the vanilla
 * protocol offers. Parked by connection object and claimed on join.
 */
@Mixin(ServerLoginNetworkHandler.class)
public abstract class LoginHelloCaptureMixin {
    @Shadow @Final ClientConnection connection;

    @Inject(method = "onHello", at = @At("HEAD"))
    private void bastionauth$captureHello(LoginHelloC2SPacket packet, CallbackInfo ci) {
        DeviceFingerprint.noteHello(this.connection, packet.name(), packet.profileId());
    }
}
