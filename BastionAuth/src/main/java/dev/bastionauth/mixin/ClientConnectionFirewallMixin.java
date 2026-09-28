package dev.bastionauth.mixin;

import dev.bastionauth.BastionAuth;
import dev.bastionauth.core.AuthManager;
import dev.bastionauth.core.PacketFirewall;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single inbound dispatch point for every serverbound packet
 * ({@code ClientConnection.handlePacket}, netty thread). While the player is
 * frozen, only the {@link PacketFirewall} allow-list gets through; everything
 * else is dropped before its handler — netty <em>or</em> main thread — ever
 * runs. This is what closes the OP-only handlers vanilla gates purely on
 * permission level (NBT queries, difficulty, game mode, structure blocks…),
 * which an impostor joining under an operator's name could reach before
 * typing a password.
 *
 * <p>The per-handler {@code @Inject}s in {@code ServerPlayNetworkHandlerMixin}
 * stay as a second layer; this one is the policy.
 */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionFirewallMixin {

    @Inject(method = "handlePacket", at = @At("HEAD"), cancellable = true)
    private static void bastionauth$firewall(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (!(listener instanceof ServerPlayNetworkHandler handler)) return;
        ServerPlayerEntity player = handler.player;
        if (player == null) return;
        AuthManager m = BastionAuth.manager();
        if (m == null || !m.isBlocked(player)) return;
        if (PacketFirewall.allowedWhileFrozen(packet, player)) return;
        if (packet instanceof PlayerMoveC2SPacket) m.onMovePacket(player);
        m.notePacketDropped(player, packet);
        ci.cancel();
    }
}
