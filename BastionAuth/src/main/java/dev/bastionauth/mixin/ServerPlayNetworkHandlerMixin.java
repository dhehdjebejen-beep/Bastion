package dev.bastionauth.mixin;

import dev.bastionauth.BastionAuth;
import dev.bastionauth.core.AuthManager;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.c2s.play.SpectatorTeleportC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet-level firewall: while a player is not authenticated, every
 * game-affecting serverbound packet is dropped at the head of its handler.
 * Movement additionally snaps the player back to the join anchor. Chat is
 * gated through Fabric's ALLOW_CHAT_MESSAGE (signature-chain safe) and
 * commands through the CommandManager mixin.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Unique
    private boolean bastionauth$blocked() {
        return BastionAuth.isBlocked(this.player);
    }

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onPlayerMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (bastionauth$blocked()) {
            AuthManager m = BastionAuth.manager();
            if (m != null) m.onMovePacket(this.player);
            ci.cancel();
        }
    }

    @Inject(method = "onVehicleMove", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onVehicleMove(VehicleMoveC2SPacket packet, CallbackInfo ci) {
        if (bastionauth$blocked()) ci.cancel();
    }

    @Inject(method = "onClientCommand", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onClientCommand(ClientCommandC2SPacket packet, CallbackInfo ci) {
        // Sneak / sprint / leave-bed and, crucially, dismounting a mount the
        // player joined on top of — all denied while frozen.
        if (bastionauth$blocked()) ci.cancel();
    }
    // NOTE: chat is intentionally NOT blocked here. Cancelling ChatMessageC2SPacket
    // at HEAD would skip vanilla's last-seen/signature acknowledgment processing and
    // can desync a frozen player who receives ambient chat (→ "chat validation
    // failed" kick). Chat is gated by Fabric's ALLOW_CHAT_MESSAGE instead, which runs
    // after acknowledgments and still stops the vanilla broadcast + console log.

    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onPlayerAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        // Blocks digging, item drop (Q) and offhand swap while frozen.
        if (bastionauth$blocked()) ci.cancel();
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onPlayerInteractBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        if (bastionauth$blocked()) ci.cancel();
    }

    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onPlayerInteractItem(PlayerInteractItemC2SPacket packet, CallbackInfo ci) {
        // The agreement books are the one thing a frozen player may use.
        if (bastionauth$blocked()
                && !dev.bastionauth.core.PacketFirewall.isAgreementBookUse(this.player, packet.getHand())) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onPlayerInteractEntity(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        if (bastionauth$blocked()) ci.cancel();
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (bastionauth$blocked()) ci.cancel();
    }

    @Inject(method = "onCreativeInventoryAction", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onCreativeInventoryAction(CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
        if (bastionauth$blocked()) ci.cancel();
    }

    @Inject(method = "onSpectatorTeleport", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onSpectatorTeleport(SpectatorTeleportC2SPacket packet, CallbackInfo ci) {
        if (bastionauth$blocked()) ci.cancel();
    }

    @Inject(method = "onRequestCommandCompletions", at = @At("HEAD"), cancellable = true)
    private void bastionauth$onRequestCommandCompletions(RequestCommandCompletionsC2SPacket packet, CallbackInfo ci) {
        // Tab-completion before login would leak the whole command tree of every
        // mod to an unauthenticated connection — deny it while frozen.
        if (bastionauth$blocked()) ci.cancel();
    }
}
