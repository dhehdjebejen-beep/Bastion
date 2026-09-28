package dev.bastionauth.mixin;

import com.mojang.authlib.GameProfile;
import dev.bastionauth.BastionAuth;
import dev.bastionauth.core.AuthManager;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}
