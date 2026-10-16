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

    /**
     * The netty-thread pass of a packet (vanilla calls every play handler
     * once there, only to re-queue it for the server thread). Its moment is
     * the packet's real arrival: stamp it, so the server-thread pass knows
     * when the packet came in rather than when the server got to it.
     */
    @Unique
    private PlayerData bastionac$netty(Object packet) {
        if (player == null || !BastionAC.enabled() || player.getEntityWorld().getServer().isOnThread()) return null;
        PlayerData d = BastionAC.data(player);
        if (d != null) d.arrivals.stamp(packet, System.currentTimeMillis());
        return d;
    }

    /**
     * A dead player — on the death screen, not yet respawned — clicking blocks
     * or entities, using items, digging or moving items. A vanilla client sends
     * none of it from there; a hacked one keeps playing "from beyond", which is
     * where the death-time dupes and ghost hits come from (3.3.1). Dropped
     * silently, on the server thread.
     */
    @Unique
    private boolean bastionac$deadAction(CallbackInfo ci) {
        if (player == null || player.isAlive()) return false;
        if (!player.getEntityWorld().getServer().isOnThread()) return false;
        if (BastionAC.config() == null || !BastionAC.config().general.ignoreDeadPlayerActions) return false;
        ci.cancel();
        return true;
    }

    /** Server-thread pass: the arrival stamp of this packet (now, when it was never stamped). */
    @Unique
    private static long bastionac$arrival(PlayerData d, Object packet) {
        long now = System.currentTimeMillis();
        long at = d.arrivals.arrivalOf(packet, now);
        d.currentArrivalMs = at;
        return at;
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
        PlayerData nd = bastionac$netty(packet);
        if (nd != null) {
            // Old clients have no tick ends of their own: their move packets are the clock.
            MovementChecks.timerArrival(player, nd, System.currentTimeMillis(), false);
            return;
        }
        if (!bastionac$ready()) return;
        dev.bastionac.checks.PacketGuard.onAnyPacket(player);
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.telemetry.Telemetry.move(player, packet, bastionac$arrival(d, packet));
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
            if (packet.changesLook()) {
                d.rawYaw = packet.getYaw(0f);
                d.rawPitch = packet.getPitch(0f);
            }
            dev.bastionac.core.PlayerContextBuffer.recordMove(player,
                    d.rawYaw, d.rawPitch, packet.changesLook(),
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
        // A frozen player is taken off any vehicle; a steering packet that still
        // arrives (ridden before the tick dismounted them) must not move it.
        if (dev.bastionac.core.FreezeManager.isStaffFrozen(player.getUuid())) {
            ci.cancel();
            return;
        }
        if (dev.bastionac.checks.VehicleChecks.onVehicleMove(player, d, packet) != Action.NONE) {
            ci.cancel();
        }
    }

    // Every server teleport makes a vanilla client answer with a confirm AND a
    // full move packet straight from the packet handler, inside whatever tick
    // it is in — one more move than its own tick sends. PacketOrder must not
    // count that one (a player after every long /tp, and in the resync loop
    // vanilla runs while the destination chunk loads).
    //
    // 3.0: the confirm is matched against the ids vanilla actually sent. It
    // used to count only while teleportPending was set — and a move packet
    // the client sent BEFORE it saw a short teleport (a setback, vanilla's
    // own put-back) lands near enough the target to clear that flag. The
    // confirm then arrived unaccounted, its Full move counted as a second
    // move of the tick, and an AFK player in a put-back loop was kicked for
    // PacketOrder (a live case, 2026-10-08).
    @Inject(method = "onTeleportConfirm", at = @At("HEAD"))
    private void bastionac$onTeleportConfirm(net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket packet,
                                             CallbackInfo ci) {
        if (bastionac$netty(packet) != null) return;
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        long at = bastionac$arrival(d, packet);
        boolean valid = d.consumeTeleportConfirm(packet.getTeleportId());
        if (valid) d.teleportConfirmsThisClientTick++;
        dev.bastionac.telemetry.Telemetry.teleportConfirm(player, packet.getTeleportId(), valid, at);
    }

    /** After vanilla took the confirm: if it was the last teleport, movement starts again from its target. */
    @Inject(method = "onTeleportConfirm", at = @At("TAIL"))
    private void bastionac$afterTeleportConfirm(net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket packet,
                                                CallbackInfo ci) {
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null || !d.teleportPending) return;
        if (((PlayNetworkHandlerAccessor) (Object) this).bastionac$requestedTeleportPos() != null) return;
        d.teleportPending = false;
        d.resetMovement(d.tpX, d.tpY, d.tpZ);
        d.resetMovePackets();
    }

    /** Vanilla refused the move and is putting the player back (moved wrongly / into a block / too fast). */
    @Inject(method = "onPlayerMove", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;requestTeleport(DDDFF)V"))
    private void bastionac$onVanillaPutBack(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null || d.teleportPending) return;   // vanilla re-sending a pending teleport is not a refusal
        dev.bastionac.core.CorrectionTracker.onVanillaCorrection(player, d, packet);
    }

    @Inject(method = "onPlayerInput", at = @At("HEAD"))
    private void bastionac$onPlayerInput(net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket packet,
                                         CallbackInfo ci) {
        if (bastionac$netty(packet) != null) return;
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        long at = bastionac$arrival(d, packet);
        d.inputBits = dev.bastionac.telemetry.Telemetry.inputBits(packet.input());
        dev.bastionac.telemetry.Telemetry.input(player, packet.input(), at);
    }

    @Inject(method = "onCloseHandledScreen", at = @At("HEAD"))
    private void bastionac$onCloseHandledScreen(net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket packet,
                                                CallbackInfo ci) {
        if (bastionac$netty(packet) != null) return;
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.telemetry.Telemetry.close(player, packet.getSyncId(), bastionac$arrival(d, packet));
    }

    @Inject(method = "onClientTickEnd", at = @At("HEAD"))
    private void bastionac$onClientTickEnd(ClientTickEndC2SPacket packet, CallbackInfo ci) {
        PlayerData nd = bastionac$netty(packet);
        if (nd != null) {
            // 1.21.2+: every client tick ends with this packet — the exact clock for Timer.
            MovementChecks.timerArrival(player, nd, System.currentTimeMillis(), true);
            return;
        }
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.telemetry.Telemetry.tickEnd(player, bastionac$arrival(d, packet));
        dev.bastionac.checks.ClientChecks.onClientTickEnd(player, d);
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void bastionac$onClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (bastionac$netty(packet) != null) return;
        if (bastionac$deadAction(ci)) return;
        if (!bastionac$ready()) return;
        dev.bastionac.checks.PacketGuard.onAnyPacket(player);
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.telemetry.Telemetry.click(player, packet, bastionac$arrival(d, packet));
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
        if (bastionac$netty(packet) != null) return;
        if (bastionac$deadAction(ci)) return;
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        long arrivedAt = bastionac$arrival(d, packet);

        boolean[] isAttack = {false};
        Vec3d[] clickedAt = {null};
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
                clickedAt[0] = pos;
            }
        });
        if (!isAttack[0]) {
            // A right-click on an entity through a wall (InteractWalls, 3.3.1).
            Entity used = packet.getEntity(player.getEntityWorld());
            long nowTick = BastionAC.serverTick();
            if (used != null && clickedAt[0] != null) {
                d.lastInteractAtEntity = used.getId();
                d.lastInteractAtTick = nowTick;
                d.lastInteractAtPos = clickedAt[0];
            } else if (used != null && used.getId() == d.lastInteractAtEntity && nowTick - d.lastInteractAtTick <= 1) {
                clickedAt[0] = d.lastInteractAtPos;   // the plain INTERACT of the same click
            }
            if (used != null && used != player && CombatChecks.interactWalls(player, d, used, clickedAt[0])) {
                ci.cancel();
            }
            return;
        }

        if (dev.bastionac.core.FreezeManager.blocksActions(player)) {
            ci.cancel();
            return;
        }
        Entity target = packet.getEntity(player.getEntityWorld());
        if (target == null || target == player) return;
        // 3.0: the client must have been shown this entity before it can hit it.
        if (!d.isFullyExempt(BastionAC.serverTick())
                && !dev.bastionac.core.EntityKnowledge.knows(player.getUuid(), target.getId())) {
            boolean refuse = BastionAC.flag(player, d, dev.bastionac.core.CheckType.ENTITYPREDICT, String.format(
                    "удар по %s (id %d), которую клиенту ещё не показывали — предсказание id (CrystalAura)",
                    net.minecraft.registry.Registries.ENTITY_TYPE.getId(target.getType()).getPath(), target.getId()));
            if (refuse) {
                d.attackVetoTick = BastionAC.serverTick();
                ci.cancel();
                return;
            }
        }
        dev.bastionac.core.AttackRate.record(player.getUuid());
        dev.bastionac.core.PlayerContextBuffer.recordAttack(player, target.getId());
        dev.bastionac.telemetry.Telemetry.attack(player, target, arrivedAt);

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
        if (bastionac$netty(packet) != null) return;
        if (bastionac$deadAction(ci)) return;
        if (!bastionac$ready()) return;
        dev.bastionac.checks.PacketGuard.onAnyPacket(player);
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.telemetry.Telemetry.action(player, packet, bastionac$arrival(d, packet));
        if (dev.bastionac.core.FreezeManager.blocksActions(player)
                && packet.getAction() != PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            ci.cancel();
            return;
        }
        dev.bastionac.core.PlayerContextBuffer.recordEvent(player, "action", packet.getAction().name());
        if (WorldChecks.onAction(player, d, packet) != Action.NONE) {
            ci.cancel();
        }
    }

    /**
     * After the server handled a right-click with a block: a cell right under
     * the player that stayed empty is a placement the server refused (a claim,
     * a spawn guard). The client may already stand on its own copy of the
     * block; HighJump counts from its top for a moment (3.4).
     */
    @Inject(method = "onPlayerInteractBlock", at = @At("TAIL"))
    private void bastionac$afterInteractBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        if (player == null || !bastionac$ready()) return;
        if (!player.getEntityWorld().getServer().isOnThread()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        net.minecraft.item.ItemStack held = player.getStackInHand(packet.getHand());
        if (!(held.getItem() instanceof net.minecraft.item.BlockItem)) return;
        net.minecraft.util.hit.BlockHitResult hit = packet.getBlockHitResult();
        net.minecraft.util.math.BlockPos cell = hit.getBlockPos().offset(hit.getSide());
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        if (Math.abs(cell.getX() + 0.5 - px) > 0.9 || Math.abs(cell.getZ() + 0.5 - pz) > 0.9) return;
        if (cell.getY() + 1 > py + 0.6 || cell.getY() + 1 < py - 2.5) return;
        if (!player.getEntityWorld().getBlockState(cell).isReplaceable()) return;   // it was placed
        d.ghostTopY = cell.getY() + 1;
        d.ghostX = cell.getX();
        d.ghostZ = cell.getZ();
        d.ghostSupportUntil = BastionAC.serverTick() + 30;
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"), cancellable = true)
    private void bastionac$onPlayerInteractBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        if (bastionac$netty(packet) != null) return;
        if (bastionac$deadAction(ci)) return;
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.telemetry.Telemetry.place(player, packet, bastionac$arrival(d, packet));
        if (dev.bastionac.core.FreezeManager.blocksActions(player)) {
            ci.cancel();
            return;
        }
        dev.bastionac.core.PlayerContextBuffer.recordEvent(player, "place",
                String.valueOf(packet.getBlockHitResult().getBlockPos().asLong()));
        if (WorldChecks.onInteractBlock(player, d, packet) != Action.NONE) {
            ci.cancel();
        }
    }

    // Staff freeze: no item use (an ender pearl or a chorus fruit is a way out),
    // and no commands except talking to staff.
    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"), cancellable = true)
    private void bastionac$onPlayerInteractItem(net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket packet,
                                                CallbackInfo ci) {
        if (player != null && dev.bastionac.core.FreezeManager.blocksActions(player)) {
            ci.cancel();
            return;
        }
        if (bastionac$netty(packet) != null) return;
        if (bastionac$deadAction(ci)) return;
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d != null) dev.bastionac.telemetry.Telemetry.useItem(player, packet, bastionac$arrival(d, packet));
    }

    @Inject(method = "onCommandExecution", at = @At("HEAD"), cancellable = true)
    private void bastionac$onCommandExecution(net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket packet,
                                              CallbackInfo ci) {
        bastionac$frozenCommand(packet.command(), ci);
    }

    @Inject(method = "onChatCommandSigned", at = @At("HEAD"), cancellable = true)
    private void bastionac$onChatCommandSigned(net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket packet,
                                               CallbackInfo ci) {
        bastionac$frozenCommand(packet.command(), ci);
    }

    @Unique
    private void bastionac$frozenCommand(String command, CallbackInfo ci) {
        if (player == null || !dev.bastionac.core.FreezeManager.blocksActions(player)) return;
        if (dev.bastionac.core.FreezeManager.commandAllowed(command)) return;
        ci.cancel();
        player.getEntityWorld().getServer().execute(() -> player.sendMessage(net.minecraft.text.Text.literal(
                "§b❄ Вы заморожены: §7доступны только личные сообщения (/msg, /r)"), false));
    }

    @Inject(method = "onHandSwing", at = @At("HEAD"))
    private void bastionac$onHandSwing(HandSwingC2SPacket packet, CallbackInfo ci) {
        if (bastionac$netty(packet) != null) return;
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.telemetry.Telemetry.swing(player, packet.getHand().ordinal(), bastionac$arrival(d, packet));
        d.lastSwingTick = BastionAC.serverTick();
        d.recordSwing(System.currentTimeMillis());
    }

    // A slot switch ends the armed mining session. Scrolling the hotbar mid-break
    // is perfectly legal on a vanilla client (nothing there aborts a break on an
    // item change), so this only drops our timer — it raises no violation.
    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"))
    private void bastionac$onUpdateSelectedSlot(UpdateSelectedSlotC2SPacket packet, CallbackInfo ci) {
        if (bastionac$netty(packet) != null) return;
        if (!bastionac$ready()) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        dev.bastionac.telemetry.Telemetry.slot(player, packet.getSelectedSlot(), bastionac$arrival(d, packet));
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

    /**
     * After vanilla has sent the teleport: remember its id (the confirm must
     * answer it) and take the target from vanilla itself, already resolved
     * from any relative axes.
     */
    @Inject(method = "requestTeleport(Lnet/minecraft/entity/EntityPosition;Ljava/util/Set;)V", at = @At("TAIL"))
    private void bastionac$afterRequestTeleport(EntityPosition pos, Set<PositionFlag> flags, CallbackInfo ci) {
        if (player == null) return;
        PlayerData d = BastionAC.data(player);
        if (d == null) return;
        PlayNetworkHandlerAccessor acc = (PlayNetworkHandlerAccessor) (Object) this;
        int id = acc.bastionac$requestedTeleportId();
        d.noteTeleportSent(id);
        Vec3d target = acc.bastionac$requestedTeleportPos();
        if (target != null) {
            d.tpX = target.x;
            d.tpY = target.y;
            d.tpZ = target.z;
        }
        int rel = 0;
        if (flags != null) {
            if (flags.contains(PositionFlag.X)) rel |= 1;
            if (flags.contains(PositionFlag.Y)) rel |= 2;
            if (flags.contains(PositionFlag.Z)) rel |= 4;
        }
        dev.bastionac.telemetry.Telemetry.teleportSent(player, id, d.tpX, d.tpY, d.tpZ, rel);
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
