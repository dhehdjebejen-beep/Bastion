package dev.bastionauth.mixin;

import dev.bastionauth.core.ChatGate;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single outbound point for every packet to a player
 * ({@code sendPacket} delegates here). Chat lines, titles and the action bar
 * addressed to a player who has not logged in are held or dropped by
 * {@link ChatGate}; everything else goes out untouched.
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class OutboundChatGateMixin {

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void bastionauth$chatGate(Packet<?> packet, ChannelFutureListener callbacks, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayNetworkHandler play)) return;
        ServerPlayerEntity player = play.player;
        if (player != null && ChatGate.intercept(player, packet)) ci.cancel();
    }
}
