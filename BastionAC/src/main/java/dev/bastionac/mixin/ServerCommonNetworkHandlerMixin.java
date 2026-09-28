package dev.bastionac.mixin;

import dev.bastionac.BastionAC;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches outgoing velocity/explosion packets addressed to a player: the
 * knockback they encode becomes a measured movement allowance, so legitimate
 * knockback, TNT, wind charges and fishing-rod pulls never trip Speed/Fly.
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {

    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void bastionac$onSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayNetworkHandler handler)) return;
        ServerPlayerEntity target = handler.player;
        if (target == null || !BastionAC.enabled()) return;

        if (packet instanceof EntityVelocityUpdateS2CPacket velocity) {
            if (velocity.getEntityId() == target.getId()) {
                BastionAC.grantVelocity(target, velocity.getVelocity());
            }
        } else if (packet instanceof ExplosionS2CPacket explosion) {
            explosion.playerKnockback().ifPresent(vec -> BastionAC.grantVelocity(target, vec));
        }
    }
}
