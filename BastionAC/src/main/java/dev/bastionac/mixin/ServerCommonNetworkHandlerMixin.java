package dev.bastionac.mixin;

import dev.bastionac.BastionAC;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
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
        dev.bastionac.core.EntityKnowledge.onSent(target.getUuid(), packet);

        if (packet instanceof EntityVelocityUpdateS2CPacket velocity) {
            if (velocity.getEntityId() == target.getId()) {
                BastionAC.grantVelocity(target, velocity.getVelocity());
                dev.bastionac.telemetry.Telemetry.velocity(target, velocity.getVelocity(), false);
            }
        } else if (packet instanceof ExplosionS2CPacket explosion) {
            explosion.playerKnockback().ifPresent(vec -> {
                BastionAC.grantVelocity(target, vec);
                dev.bastionac.telemetry.Telemetry.velocity(target, vec, true);
            });
            BastionAC.noteExplosion(target);
        }
    }

    // Anti-KB transaction (KnockbackVerifier): the ping goes out right behind
    // the velocity packet it belongs to, never in front of it.
    @Inject(method = "sendPacket", at = @At("TAIL"))
    private void bastionac$afterSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayNetworkHandler handler)) return;
        if (handler.player == null || !BastionAC.enabled()) return;
        if (packet instanceof EntityVelocityUpdateS2CPacket || packet instanceof ExplosionS2CPacket) {
            BastionAC.afterPacketSent(handler.player);
        }
    }

    /**
     * Every brand payload, in both phases (the configuration handler is a
     * ServerCommonNetworkHandler too): BrandSpoof judges how many arrived and
     * whether they agree, not only the last string.
     */
    @Inject(method = "onCustomPayload", at = @At("HEAD"))
    private void bastionac$onCustomPayload(net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket packet,
                                           CallbackInfo ci) {
        if (packet.payload() instanceof net.minecraft.network.packet.BrandCustomPayload brand) {
            dev.bastionac.core.BrandLedger.note(((CommonNetworkHandlerAccessor) this).bastionac$connection(),
                    brand.brand());
        }
    }

    @Inject(method = "onPong", at = @At("HEAD"))
    private void bastionac$onPong(CommonPongC2SPacket packet, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayNetworkHandler handler)) return;
        if (handler.player == null || !BastionAC.enabled()) return;
        // Runs on the netty thread (vanilla ignores pongs): the round trip is
        // measured where it ends, untouched by how busy the server is.
        if (dev.bastionac.telemetry.Telemetry.isOurPing(packet.getParameter())) {
            dev.bastionac.telemetry.Telemetry.pong(handler.player, packet.getParameter());
            return;
        }
        BastionAC.onPong(handler.player, packet.getParameter());
    }

    /**
     * Keep-alive answered (netty thread). Vanilla folds each round trip into
     * a smoothed latency; the raw one goes to the telemetry, where it is
     * compared with our own pings — a client that answers pings at once but
     * holds keep-alives back is faking its latency.
     */
    @Inject(method = "onKeepAlive", at = @At("HEAD"))
    private void bastionac$onKeepAlive(net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket packet, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayNetworkHandler handler)) return;
        if (handler.player == null || !BastionAC.enabled()) return;
        CommonNetworkHandlerAccessor acc = (CommonNetworkHandlerAccessor) this;
        if (!acc.bastionac$waitingForKeepAlive() || packet.getId() != acc.bastionac$keepAliveId()) return;
        long rtt = net.minecraft.util.Util.getMeasuringTimeMs() - acc.bastionac$lastKeepAliveTime();
        dev.bastionac.telemetry.Telemetry.keepAlive(handler.player, (int) Math.max(0, Math.min(60_000, rtt)));
    }
}
