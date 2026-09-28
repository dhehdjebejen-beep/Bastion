package dev.bastionauth.mixin;

import dev.bastionauth.core.ChatGate;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Other players' chat to a player who has not logged in. Stopped at the head
 * of {@code sendChatMessage} — before vanilla bumps the per-connection chat
 * index and remembers the signature — so the client's view of the chat chain
 * and the server's stay in step. The line is held and shown after login.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class PlayerChatGateMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void bastionauth$holdChat(SignedMessage message, MessageType.Parameters params, CallbackInfo ci) {
        if (this.player != null && ChatGate.interceptChat(this.player, message.getContent(), params)) ci.cancel();
    }
}
