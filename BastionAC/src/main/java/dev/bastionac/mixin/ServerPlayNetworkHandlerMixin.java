package dev.bastionac.mixin;

import dev.bastionac.BastionAC;
import dev.bastionac.checks.CombatChecks;
import dev.bastionac.checks.MovementChecks;
import dev.bastionac.checks.WorldChecks;
import dev.bastionac.core.Action;
import dev.bastionac.core.PlayerData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Packet taps for the anti-cheat.
 *
 * <p>Packet handlers are invoked twice by vanilla — once on the netty thread
 * (which immediately re-schedules to the server thread) and once on the
 * server thread. The v1 anti-cheat processed both invocations and corrupted
 * all of its movement math; every hook here runs the checks only on the
 * server-thread invocation.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Unique
    private boolean bastionac$ready() {
        return player != null && BastionAC.enabled()
                && player.getEntityWorld().getServer().isOnThread();
    }

    // PacketGuard taps: protocol abuse filters run on every packet, judge
    // without heuristics and can drop the connection outright.
    // Judged on the server-thread invocation only: the netty-thread call
    // merely re-schedules to the main thread, so cancelling there is what
    // actually stops the packet, and judging on both counted every packet
    // twice and could issue a verdict twice.
    @Inject(method = "onBookUpdate", at = @At("HEAD"), cancellable = true)
    private void bastionac$onBookUpdate(net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        dev.bastionac.checks.PacketGuard.onAnyPacket(player);
        if (dev.bastionac.checks.PacketGuard.onBookUpdate(player, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "onCreativeInventoryAction", at = @At("HEAD"), cancellable = true)
    private void bastionac$onCreativeInventoryAction(net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        dev.bastionac.checks.PacketGuard.onAnyPacket(player);
        if (dev.bastionac.checks.PacketGuard.onCreativeSlot(player, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void bastionac$onPlayerMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        dev.bastionac.checks.PacketGuard.onAnyPacket(player);
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        // Packet-order invariant: count the moves inside this client tick.
        dev.bastionac.checks.ClientChecks.onMovePacket(player, d);
        if (packet.changesPosition()) {
            double px = packet.getX(player.getX());
            double py = packet.getY(player.getY());
            double pz = packet.getZ(player.getZ());
            // Step signature and MaceDMG padding: where this tick's packets went.
            dev.bastionac.checks.ClientChecks.onMovePosition(player, d, px, py, pz);
            CombatChecks.onPositionSample(d, px, py, pz);
        }
        if (packet.changesLook()) {
            float rawPitch = packet.getPitch(player.getPitch());
            dev.bastionac.checks.ClientChecks.onRotation(player, d, rawPitch);
            CombatChecks.onRotationSample(player, d, packet.getYaw(player.getYaw()), rawPitch);
        }
        if (packet.changesPosition() || packet.changesLook()) {
            // Binary context record per position/rotation packet: timestamp,
            // yaw/pitch, centiblock deltas, state flags. See PlayerContextBuffer.
            double nx = packet.getX(player.getX());
            double ny = packet.getY(player.getY());
            double nz = packet.getZ(player.getZ());
            dev.bastionac.core.PlayerContextBuffer.recordMove(player,
                    packet.getYaw(player.getYaw()), packet.getPitch(player.getPitch()),
                    player.getX(), player.getY(), player.getZ(), nx, ny, nz,
                    packet.isOnGround(), player.isSprinting(), player.isSneaking(),
                    player.isUsingItem(), player.isTouchingWater());
        }
        if (packet.changesLook()) {
            // RECON feed: rotation samples drive the through-wall tracking
            // streak. The tracker itself throttles the expensive raycasts.
            float yaw = packet.getYaw(player.getYaw());
            float pitch = packet.getPitch(player.getPitch());
            dev.bastionac.core.ReconTracker.onRotation(player, yaw, pitch);
            // GCD window: collect the delta pair for the aim-grid analysis
            // that CombatChecks runs on the next attack.
            CombatChecks.onRotation(d, yaw, pitch);
        }
        if (MovementChecks.onMove(player, d, packet) != Action.NONE) {
            ci.cancel();
        }
    }

    @Inject(method = "onVehicleMove", at = @At("HEAD"), cancellable = true)
    private void bastionac$onVehicleMove(VehicleMoveC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        dev.bastionac.checks.PacketGuard.onAnyPacket(player);
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        if (dev.bastionac.checks.VehicleChecks.onVehicleMove(player, d, packet) != Action.NONE) {
            ci.cancel();
        }
    }

    @Inject(method = "onClientTickEnd", at = @At("HEAD"))
    private void bastionac$onClientTickEnd(ClientTickEndC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.checks.ClientChecks.onClientTickEnd(player, d);
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void bastionac$onClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        dev.bastionac.checks.PacketGuard.onAnyPacket(player);
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.checks.ClientChecks.beforeSlotClick(player, d);
        if (dev.bastionac.checks.ClientChecks.onSlotClick(player, d, packet) != Action.NONE) {
            ci.cancel();
        }
    }

    @Inject(method = "onClickSlot", at = @At("TAIL"))
    private void bastionac$afterClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.checks.ClientChecks.afterSlotClick(player, d);
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true)
    private void bastionac$onPlayerInteractEntity(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;

        boolean[] isAttack = {false};
        packet.handle(new PlayerInteractEntityC2SPacket.Handler() {
            @Override
            public void attack() {
                isAttack[0] = true;
            }

            @Override
            public void interact(Hand hand) {
            }

            @Override
            public void interactAt(Hand hand, Vec3d pos) {
            }
        });
        if (!isAttack[0]) return;

        Entity target = packet.getEntity(player.getEntityWorld());
        if (target == null || target == player) return;
        dev.bastionac.core.AttackRate.record(player.getUuid());
        dev.bastionac.core.PlayerContextBuffer.recordAttack(player, target.getId());

        // An attack ends any armed mining session as far as we are concerned, so a
        // cheat cannot keep a break timer running in the background while it
        // fights. It is NOT evidence by itself — vanilla can send an attack with
        // no ABORT in front of it (see WorldChecks.invalidateMining).
        WorldChecks.invalidateMining(d);

        if (CombatChecks.onAttack(player, d, target) != Action.NONE) {
            // Mark this tick so the damage veto (ALLOW_DAMAGE) also blocks the
            // hit — cancelling the packet alone is not always enough.
            d.attackVetoTick = BastionAC.serverTick();
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void bastionac$onPlayerAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        dev.bastionac.checks.PacketGuard.onAnyPacket(player);
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.core.PlayerContextBuffer.recordEvent(player, "action", packet.getAction().name());
        if (WorldChecks.onAction(player, d, packet) != Action.NONE) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"), cancellable = true)
    private void bastionac$onPlayerInteractBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.core.PlayerContextBuffer.recordEvent(player, "place",
                String.valueOf(packet.getBlockHitResult().getBlockPos().asLong()));
        if (WorldChecks.onInteractBlock(player, d, packet) != Action.NONE) {
            ci.cancel();
        }
    }

    @Inject(method = "onHandSwing", at = @At("HEAD"))
    private void bastionac$onHandSwing(HandSwingC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        d.lastSwingTick = BastionAC.serverTick();
        d.recordSwing(System.currentTimeMillis());
    }

    // A slot switch ends the armed mining session. Scrolling the hotbar mid-break
    // is perfectly legal on a vanilla client (nothing there aborts a break on an
    // item change), so this only drops our timer — it raises no violation.
    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"))
    private void bastionac$onUpdateSelectedSlot(UpdateSelectedSlotC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        WorldChecks.invalidateMining(d);
    }

    // Track every server-initiated teleport (plugins, /tp, ender pearls,
    // portals, our own setbacks) so movement checks pause until the client
    // confirms — v1 fought against legitimate teleports instead.

    @Inject(method = "requestTeleport(DDDFF)V", at = @At("HEAD"))
    private void bastionac$onRequestTeleport(double x, double y, double z, float yaw, float pitch, CallbackInfo ci) {
        if (player == null) return;
        PlayerData d = BastionAC.data(player);
        if (d != null) d.beginTeleport(x, y, z, BastionAC.serverTick());
    }

    @Inject(method = "requestTeleport(Lnet/minecraft/entity/EntityPosition;Ljava/util/Set;)V", at = @At("HEAD"))
    private void bastionac$onRequestTeleportFlags(EntityPosition pos, Set<PositionFlag> flags, CallbackInfo ci) {
        if (player == null) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        Vec3d target = pos.position();
        // Resolve the anchor per-axis. Only X/Y/Z mark a RELATIVE position; ROT
        // and DELTA (rotation and velocity) leave the position absolute. An ender
        // pearl teleports with {ROT, DELTA} — absolute destination, relative
        // rotation/velocity — so treating "any flag set" as relative anchored the
        // handshake at the player's OLD spot, collapsed it on the first stale
        // packet, and let the real jump reach BigMove. Mask on the position axes only.
        boolean relX = flags != null && flags.contains(PositionFlag.X);
        boolean relY = flags != null && flags.contains(PositionFlag.Y);
        boolean relZ = flags != null && flags.contains(PositionFlag.Z);
        double ax = relX ? player.getX() + target.x : target.x;
        double ay = relY ? player.getY() + target.y : target.y;
        double az = relZ ? player.getZ() + target.z : target.z;
        d.beginTeleport(ax, ay, az, BastionAC.serverTick());
    }
}
