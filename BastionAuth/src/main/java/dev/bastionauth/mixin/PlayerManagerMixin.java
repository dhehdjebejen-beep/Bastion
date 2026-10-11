package dev.bastionauth.mixin;

import com.mojang.authlib.GameProfile;
import dev.bastionauth.BastionAuth;
import dev.bastionauth.core.AuthManager;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * Connection gate. Runs before vanilla's ban/whitelist checks and refuses:
 * invalid usernames, join floods per IP, temporarily blocked IPs, names that
 * differ from a registered account only by letter case, and duplicates of a
 * player who is already online (protects the online player from being kicked
 * by an impostor with the same name).
 */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true)
    private void bastionauth$checkCanJoin(SocketAddress address, net.minecraft.server.PlayerConfigEntry configEntry,
                                          CallbackInfoReturnable<Text> cir) {
        AuthManager m = BastionAuth.manager();
        if (m == null) return;
        GameProfile profile = new GameProfile(configEntry.id(), configEntry.name());
        Text denied = m.checkCanJoin(address, profile);
        if (denied != null) cir.setReturnValue(denied);
    }

    /** Before any packet with a position: the player waits in limbo. See {@link dev.bastionauth.core.Limbo}. */
    @Inject(method = "onPlayerConnect", at = @At("HEAD"))
    private void bastionauth$limbo(net.minecraft.network.ClientConnection connection,
                                   net.minecraft.server.network.ServerPlayerEntity player,
                                   net.minecraft.server.network.ConnectedClientData clientData, CallbackInfo ci) {
        dev.bastionauth.core.Limbo.atConnect(player);
        dev.bastionauth.core.SpectatorGate.atConnect(player);
    }

    /** Leaving without the password: the real position is put back before the final save. */
    @Inject(method = "remove", at = @At("HEAD"))
    private void bastionauth$limboRemove(net.minecraft.server.network.ServerPlayerEntity player, CallbackInfo ci) {
        dev.bastionauth.core.Limbo.onRemove(player);
        dev.bastionauth.core.SpectatorGate.onRemove(player);
    }

    /** Every save of a player in limbo writes the real position and game mode, never the sky or spectator. */
    @Inject(method = "savePlayerData", at = @At("HEAD"))
    private void bastionauth$saveReal(net.minecraft.server.network.ServerPlayerEntity player, CallbackInfo ci) {
        dev.bastionauth.core.Limbo.beforeSave(player);
        dev.bastionauth.core.SpectatorGate.beforeSave(player);
    }

    @Inject(method = "savePlayerData", at = @At("RETURN"))
    private void bastionauth$saveRealDone(net.minecraft.server.network.ServerPlayerEntity player, CallbackInfo ci) {
        dev.bastionauth.core.Limbo.afterSave(player);
        dev.bastionauth.core.SpectatorGate.afterSave(player);
    }
}
