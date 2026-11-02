package dev.bastionac.checks;

import dev.bastionac.BastionAC;
import dev.bastionac.core.Action;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.Lang;
import dev.bastionac.core.MathUtil;
import dev.bastionac.core.PlayerData;
import dev.bastionac.core.ShadowTelemetry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

/**
 * Movement analysis: Speed (momentum envelope), Fly (ascend / hover / glide),
 * BigMove (ClickTP-style single-packet jumps), Timer (packet rate) and
 * GroundSpoof / NoFall.
 *
 * <p>Design rules that keep false positives out:
 * <ul>
 *   <li>every suspicious tick only bumps a small buffer; a violation is
 *       confirmed only when the buffer overflows, and VL alert/mitigation
 *       thresholds sit on top of that;</li>
 *   <li>ground support comes from real collision shapes (fences, walls,
 *       slabs, carpets and boats/shulkers included), never from "block is
 *       not air";</li>
 *   <li>knockback and explosions grant a measured allowance (velocity
 *       packets are tracked), teleports pause checks until the client
 *       confirms, slime/beds/bubble columns/climbables/cobwebs/liquids are
 *       recognised environments, elytra/riptide/vehicles are exempt.</li>
 * </ul>
 */
public final class MovementChecks {

    /**
     * Blocks a player on foot can cover sideways in one tick at sprint speed,
     * with a wide margin (3.4): see the FOOT TELEPORT note in the move check.
     */
    static final double FOOT_TELEPORT_H = 2.0;

    /** Environment of the claimed position, from real collision shapes. */
    private static final class Env {
        boolean support;
        /** Support that comes from an entity (boat / shulker / happy ghast), not a
         *  block. Such a platform can carry the player faster than a sprint, so the
         *  strict ground-speed sub-check must not treat it as solid footing. */
        boolean entitySupport;
        /** Stricter than {@link #support}: a block directly below the feet within a
         *  narrow footprint, so a side wall the wide support box grazes does NOT
         *  count. Used by Spider to tell wall-climbing from standing on the floor. */
        boolean groundBelow;
        /** The feet sit exactly on a block's top face (within {@link #FACE_EPS}). */
        boolean floorFace;
        boolean liquid;
        boolean climbable;
        boolean web;
        boolean powder;
        boolean bubble;
        boolean slimeBelow;
        boolean bedBelow;
        boolean iceBelow;
        /** Blocks that cancel or absorb fall damage outright (hay, honey, beds, slime, wool). */
        boolean cushionBelow;
    }

    /**
     * Honey-block wall slide (a constant slow descent that mimics a glide).
     * Kept out of {@link #scanEnv} and behind the glide condition's {@code &&}:
     * it costs ~48 block reads and only one branch ever asks for it.
     */
    /**
     * The fastest a vanilla client can fall after {@code ticks} ticks of free
     * fall, downward positive. Vanilla integrates {@code v' = (v + 0.08) * 0.98}
     * from rest, which converges on the ~3.92 bl/tick terminal; the loop is
     * capped so a long drop never costs more than a handful of multiplies.
     */
    static double maxVanillaFallSpeed(int ticks) {
        double v = 0.0;
        int n = Math.min(Math.max(ticks, 0), 60);
        for (int i = 0; i < n; i++) v = (v + 0.08) * 0.98;
        return v;
    }

    /** The first tick of free fall at which vanilla reaches this downward speed. */
    static int ticksForFallSpeed(double speed) {
        double v = 0.0;
        for (int n = 0; n < 60; n++) {
            if (v >= speed) return n;
            v = (v + 0.08) * 0.98;
        }
        return 60;
    }

    /** Blocks taken off the measured descent before the NoFall outcome check bills it. */
    static final double NOFALL_DESCENT_SLACK = 1.0;

    /** Vanilla's fall damage for a drop, before armour (LivingEntity.computeFallDamage). */
    static int vanillaFallDamage(double fallDistance, double safeFallDistance, double multiplier) {
        return MathHelper.floor((fallDistance + 1.0e-6 - safeFallDistance) * multiplier);
    }

    /**
     * NoFall by outcome, settled a few ticks after a landing: what the whole
     * drop should have cost by vanilla's own formula, against what vanilla
     * (and any earlier charge) actually dealt. One fake onGround packet just
     * before the ground lands the fall early with part of the bill; a
     * packet-every-tick NoFall pays nothing. Either way the difference is
     * charged now, in full, and flagged.
     */
    private static void settleFall(ServerPlayerEntity player, PlayerData d, long tick) {
        double descent = d.pendingFallDescent;
        double applied = d.fallDamageRaw;
        d.pendingFallTick = -1;
        d.pendingFallDescent = 0;
        d.fallDamageRaw = 0;
        if (d.isFullyExempt(tick) || BastionAC.isServerLagging(tick) || player.isCreative() || player.isSpectator()
                || player.isInvulnerable() || !player.isAlive()) {
            return;
        }
        // Still falling four ticks after the "landing": it was a shaft wall the
        // wide support box brushed on the way down, not a floor. Vanilla rightly
        // charged nothing, and this used to bill the player for it in mid-air
        // (26.10: 3 HP on a drop that ended in water further down).
        if (d.airTicks >= 2 && d.lastDy < -0.3) {
            return;
        }
        double safe = player.getAttributeValue(EntityAttributes.SAFE_FALL_DISTANCE);
        double mult = player.getAttributeValue(EntityAttributes.FALL_DAMAGE_MULTIPLIER);
        // Our descent and vanilla's fall distance start from different points
        // when the fall begins at a water surface or a block edge; 26.10 one
        // landing measured 7.5 here against vanilla's 6.7 and was billed a
        // fourth heart. Judge the drop one block short of what we measured: a
        // NoFall still saves whole hearts on any fall worth cheating on.
        int expected = vanillaFallDamage(descent - NOFALL_DESCENT_SLACK, safe, mult);
        double missing = expected - applied;
        if (expected < 1 || missing < 1.0) {
            d.noFallBuffer = Math.max(0, d.noFallBuffer - 0.5);
            return;
        }
        BastionAC.flag(player, d, CheckType.NOFALL, String.format(
                Lang.t("падение %.1f бл: урон %.0f из положенных %d"), descent, applied, expected));
        // The landing's own damage put the player into hurt cooldown, which
        // would silently swallow this charge (vanilla only applies damage above
        // the last hit while it lasts). The bill is not a new hit; clear it.
        player.timeUntilRegen = 0;
        player.serverDamage(player.getDamageSources().fall(), (float) Math.min(missing, 1000.0));
    }

    /** Any collision within {@code depth} blocks under the feet, in a footprint a little wider than the hitbox. */
    private static boolean floorWithin(ServerPlayerEntity player, World world, double x, double y, double z, double depth) {
        Box probe = new Box(x - 0.4, y - depth, z - 0.4, x + 0.4, y + 0.05, z + 0.4);
        net.minecraft.block.ShapeContext ctx = net.minecraft.block.ShapeContext.of(player);
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(probe.minX), MathHelper.floor(probe.minY) - 1,
                MathHelper.floor(probe.minZ), MathHelper.floor(probe.maxX), MathHelper.floor(probe.maxY),
                MathHelper.floor(probe.maxZ))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            for (Box b : state.getCollisionShape(world, pos, ctx).getBoundingBoxes()) {
                if (b.offset(pos.getX(), pos.getY(), pos.getZ()).intersects(probe)) return true;
            }
        }
        return false;
    }

    private static boolean honeyBeside(World world, double x, double y, double z) {
        int x0 = MathHelper.floor(x - 0.35) - 1;
        int x1 = MathHelper.floor(x + 0.35) + 1;
        int z0 = MathHelper.floor(z - 0.35) - 1;
        int z1 = MathHelper.floor(z + 0.35) + 1;
        for (BlockPos pos : BlockPos.iterate(x0, MathHelper.floor(y), z0, x1, MathHelper.floor(y + 1.8), z1)) {
            if (world.getBlockState(pos).isOf(Blocks.HONEY_BLOCK)) return true;
        }
        return false;
    }

    /**
     * True when a solid block sits right beside the player's body — a wall they
     * could be climbing. Detected by growing the body box horizontally by a small
     * epsilon and finding a collision box that touches the grown box but not the
     * exact one (i.e. a block flush against the side, not floor or ceiling).
     */
    private static boolean againstWall(World world, double x, double y, double z) {
        double r = 0.3;
        double eps = 0.1;
        Box inner = new Box(x - r, y + 0.2, z - r, x + r, y + 1.6, z + r);
        Box outer = inner.expand(eps, 0.0, eps);
        int x0 = MathHelper.floor(outer.minX);
        int x1 = MathHelper.floor(outer.maxX);
        int z0 = MathHelper.floor(outer.minZ);
        int z1 = MathHelper.floor(outer.maxZ);
        int y0 = MathHelper.floor(outer.minY);
        int y1 = MathHelper.floor(outer.maxY);
        for (BlockPos pos : BlockPos.iterate(x0, y0, z0, x1, y1, z1)) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(world, pos);
            if (shape.isEmpty()) continue;
            for (Box b : shape.getBoundingBoxes()) {
                Box wb = b.offset(pos.getX(), pos.getY(), pos.getZ());
                if (wb.intersects(outer) && !wb.intersects(inner)) return true;
            }
        }
        return false;
    }

    public static Action onMove(ServerPlayerEntity player, PlayerData d, PlayerMoveC2SPacket packet) {
        long tick = BastionAC.serverTick();
        long now = System.currentTimeMillis();

        d.recordMovePacket(now);
        // Context is one-shot: never let a non-alerting BigMove sample leak into
        // an unrelated later check for the same player.
        d.clearShadowDetail();
        var general = BastionAC.config().general;
        if (general.shadowTelemetryEnabled) {
            int packetCount = ShadowTelemetry.recordMovePacket(player.getUuid());
            // Shadow telemetry must stay silent in production unless console
            // reporting was explicitly enabled. Previously only the periodic
            // summary respected this switch; a bursty client could still emit a
            // pressure line every tick and flood the console.
            if (general.shadowConsoleReports
                    && packetCount > general.shadowPerPlayerMoveWarn
                    && d.lastShadowMoveWarnTick != tick) {
                d.lastShadowMoveWarnTick = tick;
                BastionAC.LOGGER.info("[SHADOW] move packet pressure player={} packets={} tick={}",
                        d.name, packetCount, tick);
            }
        }

        World world = player.getEntityWorld();
        if (d.lastWorld != world) {
            d.lastWorld = world;
            d.respawnGraceUntil = Math.max(d.respawnGraceUntil, tick + 40);
            d.resetMovement(player.getX(), player.getY(), player.getZ());
            d.resetMovePackets();
            return Action.NONE;
        }

        // Server-initiated teleport: vanilla ignores movement until the client
        // confirms. Done is when VANILLA stops waiting (the confirm hook clears
        // the flag); "a packet landed near the target" is not done — a packet
        // sent before the client even saw a short teleport lands near it too
        // (3.0, the PacketOrder kick in a put-back loop).
        if (d.teleportPending) {
            if (player.networkHandler != null
                    && ((dev.bastionac.mixin.PlayNetworkHandlerAccessor) player.networkHandler)
                            .bastionac$requestedTeleportPos() == null) {
                d.teleportPending = false;
                d.resetMovement(d.tpX, d.tpY, d.tpZ);
                d.resetMovePackets();
            }
            if (d.teleportPending && tick - d.teleportPendingSince > 100) {
                d.teleportPending = false; // failsafe: never dead-lock the checks
                d.resetMovement(player.getX(), player.getY(), player.getZ());
            }
            if (d.teleportPending) {
                return Action.CANCEL;
            }
        }

        // Staff freeze is absolute and comes before every exemption: creative,
        // flight, elytra — none of them moves a frozen player an inch. Owner,
        // 2026-10-04: «чел может летать в нём… нужен жёсткий».
        if (dev.bastionac.core.FreezeManager.isStaffFrozen(player.getUuid())) {
            if (!packet.changesPosition()) return Action.NONE;
            double fx = packet.getX(player.getX()) - d.freezeX;
            double fy = packet.getY(player.getY()) - d.freezeY;
            double fz = packet.getZ(player.getZ()) - d.freezeZ;
            if (fx * fx + fy * fy + fz * fz <= 0.03 * 0.03) {
                d.resetMovement(packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()));
                return Action.NONE;
            }
            if (tick - d.lastFreezeResyncTick >= 2) {
                d.lastFreezeResyncTick = tick;
                player.networkHandler.requestTeleport(d.freezeX, d.freezeY, d.freezeZ, player.getYaw(), player.getPitch());
            }
            return Action.CANCEL;
        }

        if (player.isCreative() || player.isSpectator() || BastionAC.isAuthFrozen(player)) {
            d.resetMovement(player.getX(), player.getY(), player.getZ());
            return Action.NONE;
        }

        // Frozen (staff freeze, or the moments after a setback): the client was
        // told to stand still; anything but standing or falling straight down
        // is refused and the player is put back where they are held.
        if (dev.bastionac.core.FreezeManager.isFrozen(player, d, tick)) {
            if (!packet.changesPosition()) return Action.NONE;
            double fx = packet.getX(player.getX()) - d.freezeX;
            double fz = packet.getZ(player.getZ()) - d.freezeZ;
            double fy = packet.getY(player.getY()) - d.freezeY;
            if (fx * fx + fz * fz <= 0.01 && fy <= 0.05) {
                d.freezeY = Math.min(d.freezeY, packet.getY(player.getY()));
                d.resetMovement(packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()));
                return Action.NONE;
            }
            if (tick - d.lastFreezeResyncTick >= 5) {
                d.lastFreezeResyncTick = tick;
                player.networkHandler.requestTeleport(d.freezeX, d.freezeY, d.freezeZ, player.getYaw(), player.getPitch());
            }
            return Action.CANCEL;
        }

        // Transition-only vehicle/elytra grace. Transport mods are gone, so a
        // mount no longer justifies a rolling exemption: while riding, speed is
        // the SERVER-side vehicle physics, which the client cannot inflate
        // without also moving the vehicle server-side. The grace now covers only
        // the dismount/mount instant (dismount-fling, glide start) and is never
        // extended by further packets — a one-second window, state-tracked in
        // PlayerData, instead of the old every-packet renewal that amounted to
        // permanent movement-check immunity for anyone on a horse.
        d.noteVehicleState(player.hasVehicle(), tick);
        d.noteGlideState(player.isGliding(), tick);
        if (player.isUsingRiptide()) d.riptideGraceUntil = tick + 20;

        boolean fullyExempt = d.isFullyExempt(tick) || BastionAC.isServerLagging(tick);
        boolean movementExempt = fullyExempt || d.isMovementExempt(tick);

        Action action = Action.NONE;

        // TIMER is judged by balance on the netty thread now (timerArrival):
        // the 30-packets-in-870-ms window read every lag spike — the client's
        // or the server's own — as a sped-up client.

        // ---------------- BLINK ----------------
        // Token bucket: +1 token per 50ms of real time, -1 per move packet. A
        // legit client never overdraws — it sends one packet per tick. A Blink
        // module withholds packets then flushes them in a burst, overdrawing the
        // bucket. When the overdraw passes the threshold, examine the burst's
        // inter-arrival-time variance: a synthetic flush arrives in one or two
        // TCP frames (variance collapses toward zero), while a real bufferbloat
        // burst keeps measurable jitter from the network stack. This is what
        // separates a cheat from a laggy player — the TIMER check cannot tell
        // them apart, this one can.
        if (!movementExempt) {
            // Arrival time, not processing time: a server stall hands the
            // checks a whole second of packets in one millisecond, and an
            // IAT spread of zero is exactly what this check convicts on.
            long arrived = d.currentArrivalMs > 0 ? d.currentArrivalMs : now;
            if (d.blinkLastRefillMs == 0) d.blinkLastRefillMs = arrived;
            long elapsed = arrived - d.blinkLastRefillMs;
            if (elapsed > 0) {
                d.blinkTokens += elapsed / 50.0;
                d.blinkLastRefillMs = arrived;
                if (d.blinkTokens > 20) d.blinkTokens = 20; // cap the bucket
            }
            d.blinkTokens -= 1;
            d.recordBurstPacket(arrived);
            if (d.blinkTokens < -general.blinkBurstOverdraw) {
                // Judge only the packets that caused the overdraw. The window is
                // a rolling one now; reading all of it would average the flush
                // together with normally-paced arrivals and hide the collapse.
                double iatStd = d.burstIatStd(general.blinkBurstOverdraw + 4);
                d.resetBurst();
                d.blinkTokens = 0; // restart the bucket after a verdict
                if (iatStd < general.blinkMaxIatStdMs) {
                    d.blinkStreak++;
                    if (d.blinkStreak >= 2) {
                        d.blinkStreak = 0;
                        if (BastionAC.flag(player, d, CheckType.BLINK, String.format(
                                Lang.t("всплеск пакетов с σ(IAT)=%.2f мс"), iatStd))) {
                            action = Action.max(action, Action.SETBACK);
                        }
                    }
                } else {
                    // Real jitter — a lag spike, not a cheat. Decay the streak.
                    d.blinkStreak = Math.max(0, d.blinkStreak - 1);
                }
            }
        }

        if (!packet.changesPosition()) {
            // A look-only packet is a tick spent standing where the last one ended:
            // a client cannot hide from the knockback replay by not reporting position.
            dev.bastionac.core.KnockbackVerifier.onMove(player, d, player.getX(), player.getY(), player.getZ(),
                    packet.isOnGround() && d.lastEnvSupport);
            // A packet with no position that claims the ground while the player
            // is visibly falling. A vanilla client in a fall moves every tick,
            // so its packets always carry a position; "OnGroundOnly(true)" in
            // mid-air is the classic one-packet NoFall (Wurst; BastionTester
            // mode 1) and lands the fall early on the server. It was invisible:
            // this branch returned before any ground check looked at it.
            if (packet.isOnGround() && !movementExempt && !d.lastSupport && !d.lastEnvSupport
                    && d.airTicks > 2 && d.lastDy < -0.1) {
                BastionAC.flag(player, d, CheckType.NOFALL,
                        String.format(Lang.t("onGround=true без координат в падении (%.1f бл от верха)"), d.fallPeakY - d.lastY));
            }
            // Keep-alive move packets: a player hovering mid-air keeps sending
            // them without position changes — that still accumulates hover.
            if (!movementExempt && !d.lastSupport && d.airTicks > 6) {
                d.hoverStreak++;
                if (d.hoverStreak > 6) {
                    d.hoverStreak = 0;
                    d.flyBuffer += 2;
                    if (overflowFly(player, d, 0.0)) action = Action.max(action, Action.SETBACK);
                }
            }
            return finish(player, d, action, tick);
        }

        double newX = packet.getX(player.getX());
        double newY = packet.getY(player.getY());
        double newZ = packet.getZ(player.getZ());

        if (!d.moveInitialized) {
            d.resetMovement(newX, newY, newZ);
            return Action.NONE;
        }

        // A landing recorded a few ticks ago is now old enough to judge:
        // vanilla has had its chance to raise the fall-damage event.
        if (d.pendingFallTick >= 0 && tick - d.pendingFallTick >= 4) {
            settleFall(player, d, tick);
        }

        double dx = newX - d.lastX;
        double dy = newY - d.lastY;
        double dz = newZ - d.lastZ;
        double hDist = MathUtil.horizontal(dx, dz);
        double distSq = dx * dx + dy * dy + dz * dz;

        // BIGMOVE: > 6 blocks in a single packet is impossible for a walking
        // player. This runs even during vehicle/elytra grace on purpose: a
        // client-side ClickTP inside that 2s window would otherwise be a free
        // teleport. Legit vehicle/elytra bursts move the SERVER position too, so
        // the server-reposition resync below accepts them without a flag — only a
        // jump the server never made (a real ClickTP) survives to be flagged.
        //
        // The 6-block floor scales with what the server itself granted the
        // player: a Speed effect multiplies MOVEMENT_SPEED (Speed 30 → ~7 bl/tick
        // on the ground), Jump Boost raises the launch (Jump Boost 30 → ~18 bl/tick
        // vertically). Both are server-side facts a client cannot fake, and both
        // used to kick admins testing effects — 65 of 135 kicks in the logs.
        double bigMoveLimit = 6.0;
        double speedAttr = player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        // Above plain sprinting (0.13): a Speed effect. Plain sprinting used to
        // pass too — 0.13 > 0.13 in floating point — and raised the limit to
        // 7.8 blocks for anyone running (3.4).
        if (speedAttr > 0.135) bigMoveLimit = Math.max(bigMoveLimit, 6.0 * (speedAttr / 0.1));
        StatusEffectInstance jumpBoost = player.getStatusEffect(StatusEffects.JUMP_BOOST);
        if (jumpBoost != null) {
            bigMoveLimit = Math.max(bigMoveLimit, 6.0 + (jumpBoost.getAmplifier() + 1) * 1.2);
        }
        // Falling is not teleporting. Terminal velocity is about 3.92 blocks
        // per tick, so a long drop legitimately covers far more than six
        // blocks in one packet — more still when a lag spike batches packets
        // together. Measuring a fall against the horizontal ceiling is why a
        // plain high fall flagged BigMove during the live test. Downward
        // motion gets its own budget, scaled by the ticks since the last
        // accepted packet; upward motion stays teleport-tight, because
        // nothing launches a player like that without a velocity packet.
        long moveGap = d.lastMoveTick <= 0 ? 1 : Math.max(1, tick - d.lastMoveTick);
        double descentAllow = Math.max(bigMoveLimit, 4.0 * Math.min(moveGap, 20) + 4.0);
        // FOOT TELEPORT (3.4): a client sends one position per tick, so one
        // packet is one tick of movement, however laggy the network. On foot a
        // tick tops out near 0.6 blocks sprint-jumping and ~1.2 on blue ice;
        // honest sessions on the server peak at 0.62. A ClickTP of 2..7.8
        // blocks sat under BigMove's six-block teleport limit and the Speed
        // average diluted it — the hole. Gliding, riptide, vehicles, flight the
        // server granted and knockback (the velocity allowance below) are not
        // "on foot"; a Speed effect raises the limit with the attribute.
        boolean onFoot = !movementExempt && !player.isGliding() && !player.isUsingRiptide()
                && !player.hasVehicle() && !player.getAbilities().allowFlying;
        double footLimit = FOOT_TELEPORT_H * Math.max(1.0, speedAttr / 0.13);
        boolean footTeleport = onFoot && hDist > footLimit;
        if (hDist > bigMoveLimit || dy > bigMoveLimit || -dy > descentAllow || footTeleport) {
            if (general.shadowTelemetryEnabled) {
                ShadowTelemetry.recordBigMoveCandidate();
                d.setShadowDetail(String.format(java.util.Locale.ROOT,
                        "bigmove claimed=%.3f,%.3f,%.3f previous=%.3f,%.3f,%.3f delta=%.3f,%.3f,%.3f",
                        newX, newY, newZ, d.lastX, d.lastY, d.lastZ, dx, dy, dz));
            }
            // First rule out a server-side reposition the AC didn't explicitly
            // hook: ender pearl (Entity.teleportTo), vehicle physics/dismount,
            // etc. In those the SERVER already moved the player, so its
            // authoritative position sits far from our last sample — the client
            // packet just confirms the new spot. A client-side ClickTP can never
            // move the server position, so this can't be abused: resync, no flag.
            double sdx = player.getX() - d.lastX;
            double sdy = player.getY() - d.lastY;
            double sdz = player.getZ() - d.lastZ;
            double serverMovedSq = sdx * sdx + sdy * sdy + sdz * sdz;
            // A shorter server move counts too when the client lands where the
            // server put it (a short dismount, a nudge by another mod): the
            // foot limit is far below the six blocks this test used to need.
            double cx = newX - player.getX(), cy = newY - player.getY(), cz = newZ - player.getZ();
            boolean claimAtServer = cx * cx + cy * cy + cz * cz <= footLimit * footLimit;
            // A move another mod announced (the duel arena): same treatment
            // as a server reposition — re-anchor on the server, no flag.
            if (serverMovedSq > 36 || (serverMovedSq > 1.0 && claimAtServer) || tick < d.serverMoveGraceUntil) {
                // Re-anchor on the SERVER's position, never the claimed one: the
                // server moving is what earned the resync, so the client's number
                // has no authority here and accepting it would hand out one free
                // arbitrary jump per legitimate reposition.
                d.resetMovement(player.getX(), player.getY(), player.getZ());
                d.resetMovePackets();
                return finish(player, d, Action.NONE, tick);
            }
            // Knockback we granted: an extreme launch (a huge Knockback enchant,
            // stacked wind charges) legitimately flings the CLIENT many blocks in
            // one tick. The velocity packet is server->client and can't be
            // spoofed — grantVelocity only fires from the outgoing-packet hook —
            // so a jump that fits inside the granted budget is the victim being
            // hit, not cheating. Without this, hitting a player with knockback
            // ~199+ flags BigMove on the victim. Accept the client position here:
            // unlike a server reposition, the client is authoritative for where
            // its own knockback carried it.
            double allowance = d.velocityBudget + 2.0;
            if (tick < d.velocityGraceUntil && distSq <= allowance * allowance) {
                d.resetMovement(newX, newY, newZ);
                d.resetMovePackets();
                return finish(player, d, Action.NONE, tick);
            }
            // MaceDMG / ArrowDMG: four motionless packets first raise vanilla's
            // per-tick movement allowance to 5x100, then a sqrt(500) = 22.4-block
            // jump and straight back — a fall the mace bills in full, or a
            // bow shot from 22 blocks behind. The padding is the signature.
            boolean padded = d.zeroMovesThisClientTick >= 3;
            boolean onlyFoot = footTeleport && !(hDist > bigMoveLimit || dy > bigMoveLimit || -dy > descentAllow);
            BastionAC.flag(player, d, CheckType.BIGMOVE, onlyFoot
                    ? String.format(Lang.t("телепорт пешком: %.1f бл по горизонтали за тик (предел %.1f, верт. %.1f)"),
                            hDist, footLimit, dy)
                    : String.format(Lang.t("%.1f бл за пакет (гор. %.1f/%.1f, верт. %.1f, пауза %d т)%s"),
                            Math.sqrt(distSq), hDist, bigMoveLimit, dy, moveGap,
                            padded ? Lang.t(" после ") + d.zeroMovesThisClientTick + Lang.t(" пустых пакетов — MaceDMG/ArrowDMG") : ""),
                    padded ? 2.0 : 1.0);
            // Never accept the jump as the new position.
            return finish(player, d, Action.SETBACK, tick);
        }

        boolean scanAllowed = true;
        if (general.shadowTelemetryEnabled) {
            scanAllowed = ShadowTelemetry.reserveEnvironmentScan(
                    general.movementScansPerTickBudget, general.enforceMovementScanBudget);
        }
        if (!scanAllowed) {
            // Enforcement is opt-in and never flags. Re-anchor to the server's
            // current accepted movement sample rather than turning pressure into VL.
            d.resetMovement(newX, newY, newZ);
            d.resetMovePackets();
            return finish(player, d, action, tick);
        }
        long scanStartNanos = general.shadowTelemetryEnabled ? System.nanoTime() : 0L;
        Env env = scanEnv(player, world, newX, newY, newZ);
        // The landing below was first limited to "no support at all", but the
        // support box reaches half a block down: landing on a wall's 1.5-high
        // arm or a snow layer from the corner of the start box left support
        // set by the floor underneath and the landing unrecognised (HighJump
        // 1,75 at a desert village wall and on a snowy slope, 02.11 12:13/12:41).
        if ((!env.support || !env.groundBelow) && packet.isOnGround() && newY <= d.lastY) {
            // Vanilla resolves the vertical collision with the box where the tick
            // started, then slides sideways: a landing on a block's corner can end
            // the tick a few centimetres past the edge, on the ground. 02.11 a sprint
            // jump up a slope landed on a cobblestone corner and drifted 6 cm off it;
            // the end position alone had no floor, so the landing did not count and
            // the next jump was measured from the step before (HighJump 1,88, setback).
            Env start = scanEnv(player, world, d.lastX, newY, d.lastZ);
            if (start.support && !start.entitySupport) {
                env.support = true;
                env.groundBelow |= start.groundBelow;
                env.floorFace |= start.floorFace;
                env.slimeBelow |= start.slimeBelow;
                env.bedBelow |= start.bedBelow;
                env.iceBelow |= start.iceBelow;
                env.cushionBelow |= start.cushionBelow;
            }
        }
        if (!env.groundBelow && packet.isOnGround() && d.lastGroundedOnFace
                && newY > d.lastY && newY - d.lastY <= 0.6 && floorFaceNear(player, world, newX, newY, newZ)) {
            // A step-up onto a slab or a block edge ends with the hitbox over the
            // step by however far the tick carried it, sometimes by less than the
            // foot probe's hair (HighJump 1,75 after a step from 138,5 to 139,0).
            env.support = true;
            env.groundBelow = true;
            env.floorFace = true;
        }
        if (general.shadowTelemetryEnabled) {
            ShadowTelemetry.recordEnvironmentScanNanos(System.nanoTime() - scanStartNanos);
        }
        if (dev.bastionac.telemetry.Telemetry.of(player.getUuid()) != null) {
            recordEnv(player, world, env, newX, newY, newZ);
        }

        // Anti-KB: a medium that swallows the impulse (water, lava, cobweb,
        // powder snow, a ladder) makes the air-drag displacement model
        // meaningless, so the armed transaction is dropped instead of convicting.
        if (d.kbPending && (env.liquid || env.web || env.climbable || env.powder)) {
            d.kbBlocked = true;
        }
        // Anti-KB replay: one tick of the knockback, with the ground state the
        // client claimed only where the server's own scan agrees.
        dev.bastionac.core.KnockbackVerifier.onMove(player, d, newX, newY, newZ,
                packet.isOnGround() && env.support);
        d.lastGroundBacked = packet.isOnGround() && env.support;

        d.lastOnFloorFace = env.floorFace && !env.liquid;
        d.lastGroundedOnFace = d.lastOnFloorFace && packet.isOnGround();

        if (movementExempt) {
            d.clearKbVerify();
            // Riding, gliding and riptide carry the player anywhere without a
            // footing tick, so the take-off height measured before them means
            // nothing afterwards: a player who rode a horse up a hill used to
            // get their first jump after dismounting judged against the valley
            // floor (HighJump 4,75 / 5,17 in the prod log).
            d.climbBaseY = newY;
            d.highJumpBase = Double.NaN;
            // The fall origin has to follow the player through the exempt stretch
            // too. A server lag spike exempts everyone for a few seconds; the origin
            // used to stay where the spike began, and the first landing after it was
            // measured from there: 02.11, sprint-hopping down a snowy slope right
            // after an /rtp lag spike was billed as a 9,6- and a 7,2-block fall
            // (NoFall, and 5 HP charged) for hops of two blocks each.
            if (env.support || env.liquid || env.climbable || env.web || env.powder || env.bubble
                    || (packet.isOnGround() && floorWithin(player, world, newX, newY, newZ, 1.0))) {
                d.fallPeakY = newY;
            } else if (newY > d.fallPeakY) {
                d.fallPeakY = newY;
            }
            d.lastEnvSupport = env.support;
            update(d, newX, newY, newZ, dy, env);
            return finish(player, d, action, tick);
        }

        if (env.support && env.iceBelow) d.iceMemoryTicks = 12;
        else if (d.iceMemoryTicks > 0) d.iceMemoryTicks--;

        if ((env.slimeBelow || env.bedBelow) && dy < -0.2) {
            d.bounceGraceUntil = tick + 30;
        }

        boolean velocityActive = tick < d.velocityGraceUntil;

        // ---------------- SPEED (sustained average) ----------------
        // Per-tick speed spikes on every sprint-jump / bunny-hop, so we judge
        // the AVERAGE over a 20-tick window: bursts average out, only sustained
        // excess (a real speed hack) flags. The ceiling is deliberately
        // generous and tunable via general.speedToleranceMultiplier.
        // Realistic ground speed: walk ≈ 0.215, sprint ≈ 0.28 (the MOVEMENT_SPEED
        // attribute already folds in the sprint modifier and any Speed effect).
        double attr = player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        double groundMax = attr * 2.15;
        double tol = Math.max(0.9, BastionAC.config().general.speedToleranceMultiplier);

        // (a) Overall 20-tick average — a deliberately LOOSE backstop for gross,
        // all-round speed. Kept generous (~0.7 bl/tick for a sprinter) so a legit
        // sprint-jump chain, even in a 2-high tunnel where the player bounces off
        // the ceiling and re-jumps rapidly, never reaches it. This is why we do
        // not tighten it: the ground check below is what catches moderate hacks.
        double avgLimit = groundMax * 2.4 + 0.05;
        if (d.iceMemoryTicks > 0) avgLimit *= 1.9;
        if (env.liquid) avgLimit *= player.hasStatusEffect(StatusEffects.DOLPHINS_GRACE) ? 2.2 : 1.5;
        avgLimit *= tol;
        if (velocityActive) avgLimit += Math.max(0, d.velocityBudget);

        double avgSpeed = d.pushSpeedSampleAndAvg(hDist);
        if (!env.web && !env.powder && avgSpeed > avgLimit && hDist > groundMax * 1.1) {
            d.speedBuffer += 1;
            if (d.speedBuffer > 4) {
                d.speedBuffer = 2;
                boolean mitigate = BastionAC.flag(player, d, CheckType.SPEED,
                        String.format(Lang.t("средняя %.2f бл/тик, лимит %.2f"), avgSpeed, avgLimit));
                if (mitigate || BastionAC.instantSetback(d)) {
                    action = Action.max(action, Action.SETBACK);
                }
            }
        } else {
            d.speedBuffer = Math.max(0, d.speedBuffer - 1);
        }

        // (b) Ground speed — on solid ground for ≥2 consecutive ticks a player
        // cannot exceed sprint (no jump momentum to carry). This catches moderate
        // ground speed-hacks (e.g. Meteor "Only On Ground") that the loose average
        // misses, and is immune to bunny-hopping (airborne). Ice/knockback/bounce
        // are exempted so legit momentum never flags.
        // The support box reaches half a block down, so it also holds a player hopping 0,2 blocks
        // under a 2-high ceiling — sprint-jumping in a tunnel, where vanilla carries jump momentum
        // to ~0,40 bl/tick (Speed 0,40 > 0,39, 02.11). The client must also say it stood on the
        // ground for both moves; a hack that lies "on ground" is still measured here.
        boolean groundStable = env.support && d.lastSupport && packet.isOnGround() && d.lastPacketGround
                && !env.liquid && !env.entitySupport;
        d.lastPacketGround = packet.isOnGround();
        if (groundStable && tick >= d.bounceGraceUntil) {
            double groundLimit = (groundMax * 1.3 + 0.03) * tol;
            if (d.iceMemoryTicks > 0) groundLimit *= 1.9;
            // Knockback: keep the check LIVE but widen the limit by the measured
            // velocity budget instead of switching the check off. Micro-knockback
            // yields a micro-budget, so it can no longer cloak a ground speed hack
            // (the old blanket 16-tick exemption on any velocity packet did).
            if (velocityActive) groundLimit += Math.max(0, d.velocityBudget);
            if (hDist > groundLimit) {
                d.groundSpeedBuffer += 1;
                if (d.groundSpeedBuffer > 3) {
                    d.groundSpeedBuffer = 1;
                    boolean mitigate = BastionAC.flag(player, d, CheckType.SPEED,
                            String.format(Lang.t("на земле %.2f бл/тик, лимит %.2f"), hDist, groundLimit));
                    if (mitigate || BastionAC.instantSetback(d)) {
                        action = Action.max(action, Action.SETBACK);
                    }
                }
            } else {
                d.groundSpeedBuffer = Math.max(0, d.groundSpeedBuffer - 1);
            }
        } else {
            d.groundSpeedBuffer = Math.max(0, d.groundSpeedBuffer - 0.5);
        }

        // ---------------- NOSLOW ----------------
        // While an item is in use vanilla scales the movement INPUT (by the
        // item's use speed, 0.2 for food, bows and shields) — and nothing
        // else. Momentum keeps its friction, and a sprint already running is
        // not stopped by eating: the client only refuses to START a sprint
        // with an item up (ClientPlayerEntity.canStartSprinting), so every
        // jump of a player who sprints and eats still gets the full 0.2
        // sprint-jump boost. A fixed speed cap read those jumps as NoSlow —
        // "0,32 бл/тик с предметом (лимит 0,10)" on stairs with an apple,
        // the false positive that survived three fixes.
        //
        // So the judge is the vanilla step itself: this tick's horizontal
        // distance from the last one, its friction, the slowed acceleration
        // and the jump boost when the client took off sprinting. A NoSlow
        // skips the 0.2 and holds walking or sprinting speed, which is far
        // above that step after a tick or two of friction.
        if (player.isUsingItem()) {
            if (d.usingItemSinceTick < 0) d.usingItemSinceTick = tick;
            long usingFor = tick - d.usingItemSinceTick;
            boolean groundNow = packet.isOnGround() && env.groundBelow;
            // Ice and blue ice carry momentum the friction model below does
            // not cover past a few ticks of memory; liquids, ladders, webs and
            // mounts have their own rules; a bounce and an entity floor push.
            boolean judgeable = moveGap == 1 && !env.liquid && !env.climbable && !env.web
                    && !env.entitySupport && d.iceMemoryTicks == 0 && tick >= d.bounceGraceUntil
                    && d.noSlowLastHDist >= 0;
            if (usingFor > general.noSlowGraceTicks && judgeable) {
                float useMul = player.getActiveItem().getOrDefault(
                        net.minecraft.component.DataComponentTypes.USE_EFFECTS,
                        net.minecraft.component.type.UseEffectsComponent.DEFAULT).speedMultiplier();
                float slip = world.getBlockState(BlockPos.ofFloored(newX, newY - 0.5000001, newZ))
                        .getBlock().getSlipperiness();
                // The step starts from vanilla's own prediction of the last
                // tick when there is one, not from what the client claimed: a
                // NoSlow is only ~0.02 a tick over vanilla in the air, inside
                // any per-tick slack, but the excess adds up tick after tick.
                double base = d.noSlowShadow >= 0 ? d.noSlowShadow : d.noSlowLastHDist;
                double step = noSlowStep(base, d.noSlowGroundBefore, d.noSlowLastGround, dy,
                        // The jump boost follows the client's sprint; the
                        // server's flag can lag behind it (19.10: a sprint-hop
                        // with an item, the key held, the flag off).
                        player.isSprinting() || player.getPlayerInput().sprint(),
                        player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED),
                        useMul, slip, player.isSneaking());
                double bound = noSlowSlack(step, d.noSlowLastGround);
                if (velocityActive) bound += Math.max(0, d.velocityBudget);
                if (general.debugChecks) {
                    BastionAC.LOGGER.info("[DEBUG noslow] {} h={} bound={} usingFor={} ground={}/{} buffer={}",
                            d.name, String.format("%.3f", hDist), String.format("%.3f", bound),
                            usingFor, d.noSlowLastGround, groundNow, String.format("%.1f", d.noSlowBuffer));
                }
                // Shoved by a mob, a boat or another player: vanilla adds
                // that push to the step. Asked only when the step was exceeded.
                boolean shoved = hDist > bound && !world.getOtherEntities(player,
                        player.getBoundingBox().expand(0.6, 0.3, 0.6),
                        e -> e.isPushable() && e != player.getVehicle()).isEmpty();
                // Vanilla's prediction carries on; a slower client (a wall, a
                // corner) pulls it down, a faster one never pushes it up.
                d.noSlowShadow = shoved || velocityActive ? -1 : Math.min(step, hDist);
                if (shoved) {
                    // Neither evidence nor an excuse.
                } else if (hDist > bound) {
                    d.noSlowBuffer += 1;
                    // Six ticks over the vanilla step: the server keeps
                    // isUsingItem() until the client's release lands, so a
                    // ping's worth of full-speed ticks after letting go is honest.
                    if (d.noSlowBuffer > 5) {
                        d.noSlowBuffer = 1;
                        boolean mitigate = BastionAC.flag(player, d, CheckType.NOSLOW,
                                String.format(Lang.t("%.2f бл/тик с предметом (ванильный предел %.2f)"), hDist, bound));
                        if (mitigate || BastionAC.instantSetback(d)) {
                            action = Action.max(action, Action.SETBACK);
                        }
                    }
                } else {
                    d.noSlowBuffer = Math.max(0, d.noSlowBuffer - 1);
                }
            } else {
                d.noSlowShadow = -1;
            }
            d.noSlowLastHDist = moveGap == 1 ? hDist : -1;
            d.noSlowGroundBefore = d.noSlowLastGround;
            d.noSlowLastGround = groundNow;
        } else {
            d.usingItemSinceTick = -1;
            d.noSlowShadow = -1;
            d.noSlowBuffer = Math.max(0, d.noSlowBuffer - 0.5);
            d.noSlowLastHDist = moveGap == 1 ? hDist : -1;
            d.noSlowGroundBefore = d.noSlowLastGround;
            d.noSlowLastGround = packet.isOnGround() && env.groundBelow;
        }

        // ---------------- NOWEB ----------------
        // A cobweb multiplies movement by 0.25: even a sprinter crawls at
        // ~0.07 bl/tick inside one. NoWeb skips the multiplier. The probe is the
        // player's exact hitbox — the wider environment box also counts webs
        // the player merely brushes past, which do not slow anyone.
        if (env.web && !velocityActive && hDist > 0.12 && inWeb(world, newX, newY, newZ)) {
            d.webFastStreak++;
            if (d.webFastStreak >= 6) {
                d.webFastStreak = 2;
                boolean mitigate = BastionAC.flag(player, d, CheckType.NOSLOW,
                        String.format(Lang.t("в паутине %.2f бл/тик (без мода не больше 0.08)"), hDist));
                if (mitigate || BastionAC.instantSetback(d)) {
                    action = Action.max(action, Action.SETBACK);
                }
            }
        } else {
            d.webFastStreak = Math.max(0, d.webFastStreak - 1);
        }

        // ---------------- NOWEB: sweet berry bush ----------------
        // A bush is not a web: vanilla multiplies the move by 0.8 and zeroes the
        // velocity every tick, so inside one a player only ever travels the
        // movement input of that single tick — the speed attribute (sprint and
        // Speed included) times 0.8, about 0.10 bl/tick for a sprinter. The
        // cobweb branch above never looked at bushes at all. Judged only when
        // the last two positions were both inside (the slow-down applies from
        // the tick after entering), on a clean one-packet cadence.
        boolean inBush = inBlock(world, newX, newY, newZ, Blocks.SWEET_BERRY_BUSH);
        if (inBush && d.lastInBerryBush && !velocityActive && moveGap == 1 && tick >= d.bounceGraceUntil) {
            double bushLimit = player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED) * 0.8 + 0.03;
            if (hDist > bushLimit) {
                d.berryFastStreak++;
                if (d.berryFastStreak >= 6) {
                    d.berryFastStreak = 2;
                    boolean mitigate = BastionAC.flag(player, d, CheckType.NOSLOW, String.format(
                            Lang.t("в кусте ягод %.2f бл/тик (без мода не больше %.2f)"), hDist, bushLimit));
                    if (mitigate || BastionAC.instantSetback(d)) {
                        action = Action.max(action, Action.SETBACK);
                    }
                }
            } else {
                d.berryFastStreak = Math.max(0, d.berryFastStreak - 1);
            }
        } else {
            d.berryFastStreak = Math.max(0, d.berryFastStreak - 1);
        }
        d.lastInBerryBush = inBush;

        if (velocityActive) d.velocityBudget *= 0.88;

        // The drop this packet ends, measured BEFORE the resting branch below
        // moves the fall origin to the landing spot. Reading it afterwards (as
        // the outcome NoFall did since 2.22) always gave 0, so that detector
        // never once fired.
        double dropBeforeLanding = d.fallPeakY - newY;
        boolean resting =env.support || env.liquid || env.climbable || env.web || env.powder || env.bubble;
        if (resting) {
            d.airTicks = 0;
            d.hoverStreak = 0;
            d.glideStreak = 0;
            d.flyBuffer = Math.max(0, d.flyBuffer - 1);
            d.fallPeakY = newY; // reset fall origin
        } else if (packet.isOnGround() && floorWithin(player, world, newX, newY, newZ, 1.0)) {
            // A sprint-hop down a slope lands for a single tick on a block edge the
            // support box misses, and the fall origin used to ride over every hop:
            // 02.11 a run of one-block hops was billed as a 5,2-block fall (and
            // charged as damage). A claimed landing with a floor within a block
            // under the feet ends the fall, as it does for vanilla. A NoFall that
            // claims the ground high in the air has no floor there and is still
            // caught by both detectors below.
            d.airTicks++;
            d.fallPeakY = newY;
        } else {
            d.airTicks++;
            if (d.airTicks == 1 && d.pendingFallTick < 0) d.fallDamageRaw = 0;
            if (newY > d.fallPeakY) d.fallPeakY = newY; // track the fall's highest point
        }
        // Gliding, riptide and levitation are controlled descent, not falling:
        // vanilla charges little or nothing for them, and a player who glides
        // down from build height would otherwise look to the outcome check
        // like a 130-block drop that cost no damage. Keep the fall origin
        // pinned to the current height for as long as any of them is active,
        // so only what happens AFTER they stop counts as a fall.
        if (player.isGliding() || player.isUsingRiptide()
                || player.hasStatusEffect(StatusEffects.LEVITATION)
                || player.hasStatusEffect(StatusEffects.SLOW_FALLING)) {
            d.fallPeakY = newY;
            d.pendingFallTick = -1;
        }

        // ---------------- FASTFALL ----------------
        // Vanilla free fall rides a fixed curve: each tick the downward speed
        // becomes (v + 0.08) * 0.98, converging on the 3.92 bl/tick terminal.
        // A vanilla client can never descend FASTER than that for how long it
        // has actually been falling — FastFall multiplies the drop, so a fall
        // of only a few blocks already overshoots the curve (the live test's
        // "падения до 9 блоков не детектятся" gap: BigMove's floor is far above
        // this, and NoFall only fires when the fall dealt no damage).
        //   • judged only on a clean one-packet-per-tick cadence (moveGap == 1);
        //     a lag spike batches several ticks into one packet and shows a big
        //     dy that is not a cheat — BigMove already bounds that case;
        //   • fall-absorbing media and server-granted motion (velocity, bounce,
        //     slow-fall, levitation, glide, riptide, vehicle) are exempt;
        //   • six sustained over-curve ticks per flag, so one jittery packet or
        //     a one-tick collision artefact never convicts.
        boolean fastFallExempt = env.liquid || env.web || env.climbable || env.powder || env.bubble
                || player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                || player.hasStatusEffect(StatusEffects.LEVITATION)
                || player.isGliding() || player.isUsingRiptide() || player.hasVehicle()
                || velocityActive || tick < d.bounceGraceUntil;
        if (!resting && dy < 0 && fastFallExempt) {
            // Still falling, just not judged this tick. Restarting the curve at
            // zero here was a false positive: every hit (fall damage included)
            // sends a velocity packet, so a player hurt mid-fall was then held
            // to the 0.77 bl/tick of an eighth tick while already doing 1.5.
            // Keep counting, and never below the tick vanilla needs for this speed.
            d.fallTicks = Math.max(d.fallTicks + (int) Math.min(moveGap, 10), ticksForFallSpeed(-dy));
        } else if (!resting && dy < 0) {
            d.fallTicks += (int) Math.min(moveGap, 10);
            if (moveGap == 1 && d.fallTicks >= 3) {
                double cap = maxVanillaFallSpeed(d.fallTicks) * 1.15 + 0.10;
                if (-dy > cap) {
                    d.fastFallBuffer += 1;
                    if (d.fastFallBuffer > 5) {
                        d.fastFallBuffer = 1;
                        boolean mitigate = BastionAC.flag(player, d, CheckType.FASTFALL, String.format(
                                Lang.t("падение %.2f бл/тик (ванильный предел %.2f на %d-м тике)"), -dy, cap, d.fallTicks));
                        if (mitigate || BastionAC.instantSetback(d)) {
                            action = Action.max(action, Action.SETBACK);
                        }
                    }
                } else {
                    d.fastFallBuffer = Math.max(0, d.fastFallBuffer - 1);
                }
            }
        } else if (dy < 0 && !env.groundBelow && !env.entitySupport) {
            // "Resting" with nothing under the feet: the support box brushing a
            // shaft wall, or the body scan still finding the water the player is
            // dropping out of. The fall is real and keeps its speed; restarting
            // the curve at zero held two players falling down a water-topped
            // shaft to the eighth tick's 0.77 while vanilla had them at the
            // twelfth (0.84, to the hundredth) — FastFall 26.10, and a setback.
            d.fallTicks = ticksForFallSpeed(-dy);
            d.fastFallBuffer = Math.max(0, d.fastFallBuffer - 0.5);
        } else {
            d.fallTicks = 0;
            d.fastFallBuffer = Math.max(0, d.fastFallBuffer - 0.5);
        }

        // Spider footing: only a floor directly below, a climbable, liquid or
        // special medium resets the height baseline — NOT a side wall (which the
        // wide support box counts as "resting"). This is what un-blinds Spider.
        boolean footing = env.groundBelow || env.climbable || env.liquid || env.web || env.powder || env.bubble;
        if (footing) {
            d.climbBaseY = newY;
            // A new take-off: the HighJump latch belongs to the previous one.
            // Without this a second HighJump from the same height as the first
            // (same floor, same Y) compared equal to the latch and was never
            // reported — once per height instead of once per jump.
            d.highJumpBase = Double.NaN;
            d.spiderBuffer = Math.max(0, d.spiderBuffer - 1);
        }

        // ---------------- FLY ----------------
        if (!resting) {
            boolean bounce = tick < d.bounceGraceUntil;
            boolean levitating = player.hasStatusEffect(StatusEffects.LEVITATION);
            boolean slowFalling = player.hasStatusEffect(StatusEffects.SLOW_FALLING);
            int maxJumpTicks = 14;
            StatusEffectInstance jump = player.getStatusEffect(StatusEffects.JUMP_BOOST);
            if (jump != null) maxJumpTicks += (jump.getAmplifier() + 1) * 5;

            if (dy > 0.03 && d.airTicks > maxJumpTicks && !bounce && !velocityActive && !levitating) {
                d.flyBuffer += 1.5; // rising long after any legit jump could
            } else if (Math.abs(dy) < 0.02 && d.airTicks > 6 && !levitating && !velocityActive) {
                d.hoverStreak++;
                if (d.hoverStreak > 4) {
                    d.hoverStreak = 0;
                    d.flyBuffer += 2; // hovering — the jump apex lasts 1-2 ticks, not 5+
                }
            } else if (dy < 0 && dy > -0.4 && d.airTicks > 20 && Math.abs(dy - d.lastDy) < 0.003
                    && !slowFalling && !velocityActive && !honeyBeside(world, newX, newY, newZ)) {
                d.glideStreak++;
                if (d.glideStreak > 6) {
                    d.glideStreak = 0;
                    d.flyBuffer += 1.5; // constant slow fall — vanilla falls accelerate
                }
            } else {
                d.flyBuffer = Math.max(0, d.flyBuffer - 0.5);
            }
            if (overflowFly(player, d, dy)) action = Action.max(action, Action.SETBACK);
        }

        // ---------------- SPIDER (wall-climbing) ----------------
        // Climbing a wall like a ladder: rising while airborne, pressed to a wall
        // and not on any climbable/liquid. A legal jump gains at most ~1.25 blocks
        // and needs a landing to chain, so a height gain past that against a wall
        // with no footing in between is unambiguous.
        if (!footing && dy > 0.0) {
            boolean levitating = player.hasStatusEffect(StatusEffects.LEVITATION);
            boolean bounce = tick < d.bounceGraceUntil;
            double maxJumpGain = 1.35;
            StatusEffectInstance jb = player.getStatusEffect(StatusEffects.JUMP_BOOST);
            if (jb != null) maxJumpGain += (jb.getAmplifier() + 1) * 2.0; // Jump Boost jumps go much higher
            double climbGain = newY - d.climbBaseY;
            if (climbGain > maxJumpGain && !levitating && !bounce && !velocityActive
                    && againstWall(world, newX, newY, newZ)) {
                d.spiderBuffer += 1;
                if (d.spiderBuffer > 2) {
                    d.spiderBuffer = 1;
                    if (BastionAC.flag(player, d, CheckType.SPIDER,
                            String.format(Lang.t("подъём %.2f у стены, dy=%.2f, воздух %d т"), climbGain, dy, d.airTicks))) {
                        action = Action.max(action, Action.SETBACK);
                    }
                }
            }
        }

        // ---------------- HIGHJUMP ----------------
        // Spider judges the same height gain only against a wall. Away from
        // one, a jump still tops out at ~1.25 blocks (plus Jump Boost), and
        // Wurst's HighJump (height 6 by default) clears six. Once per jump:
        // the take-off height is latched after the first report.
        // Not in Прятки worlds: props are blocks sent to the clients only, so a
        // player who hops onto a disguised hider stands on air for the server.
        boolean propWorld = player.getEntityWorld().getRegistryKey().getValue().getNamespace().equals("hideseek");
        if (!footing && dy > 0.0 && !player.isGliding() && !player.isUsingRiptide() && !propWorld) {
            double maxGain = 1.35;
            StatusEffectInstance jb = player.getStatusEffect(StatusEffects.JUMP_BOOST);
            if (jb != null) maxGain += (jb.getAmplifier() + 1) * 2.0;
            double base = d.climbBaseY;
            // Towering into a refused cell (someone's claim, a spawn guard):
            // the client stands on its own block for a moment and jumps off
            // it before the server's correction lands. The jump counts from
            // that block's top (3.4) — a stranger building in a claim was
            // flagged HighJump five times in four seconds.
            if (tick < d.ghostSupportUntil && d.ghostTopY > base && d.ghostTopY <= newY
                    && Math.abs(newX - (d.ghostX + 0.5)) < 0.9 && Math.abs(newZ - (d.ghostZ + 0.5)) < 0.9) {
                base = d.ghostTopY;
            }
            double gain = newY - base;
            boolean exempt = player.hasStatusEffect(StatusEffects.LEVITATION)
                    || tick < d.bounceGraceUntil || velocityActive;
            if (gain > maxGain + 0.35 && !exempt && d.highJumpBase != d.climbBaseY) {
                d.highJumpBase = d.climbBaseY;
                boolean mitigate = BastionAC.flag(player, d, CheckType.FLY, String.format(
                        Lang.t("прыжок на %.2f бл без опоры (предел %.2f) — HighJump"), gain, maxGain));
                if (mitigate || BastionAC.instantSetback(d)) {
                    action = Action.max(action, Action.SETBACK);
                }
            }
        }

        // ---------------- FASTLADDER ----------------
        // On a ladder, vine or scaffolding vanilla sets the climb velocity to
        // 0.2 after each move, so the steady climb is 0.1176 bl/tick; only the
        // first tick of a jump onto it is faster. Wurst's FastLadder pins it
        // at 0.2872 every tick.
        if (env.climbable && dy > 0.21 && !velocityActive && !player.isGliding() && tick >= d.bounceGraceUntil
                && !env.bubble && !env.liquid && !player.hasStatusEffect(StatusEffects.LEVITATION)) {
            d.ladderFastStreak++;
            if (d.ladderFastStreak >= 5) {
                d.ladderFastStreak = 2;
                // Half a Spider flag each: the climb is a fact, but it repeats
                // every few ticks, and Spider's kick threshold was set for a
                // check that fires once per wall.
                boolean mitigate = BastionAC.flag(player, d, CheckType.SPIDER,
                        String.format(Lang.t("по лестнице %.3f бл/тик (без мода 0.118) — FastLadder"), dy), 0.5);
                if (mitigate || BastionAC.instantSetback(d)) {
                    action = Action.max(action, Action.SETBACK);
                }
            }
        } else {
            d.ladderFastStreak = 0;
        }

        // ---------------- NOFALL (by outcome) ----------------
        // SECOND detector, running alongside the signature one further down —
        // not a replacement for it. The signature path catches a sustained
        // packet NoFall within a few packets and is what already flags the
        // common implementations instantly; it is untouched.
        //
        // What it cannot see is a client that sends exactly ONE onGround
        // packet, at the moment of impact: four-in-a-row never accumulates,
        // so nothing fires. That is the case the live test ran into. This
        // path ignores the method entirely and audits the RESULT. The server
        // measures the descent itself from the player's own positions, waits
        // for the landing, and asks one question: did vanilla ever try to
        // charge fall damage for it? A thirty-block drop onto bare stone that
        // raised no fall-damage event was not an honest fall, whichever
        // packet trick produced it.
        boolean landedNow = env.support && !d.lastEnvSupport;
        if (landedNow && d.pendingFallTick < 0) {
            double descent = dropBeforeLanding;
            boolean cushioned = env.liquid || env.web || env.climbable || env.powder
                    || env.bubble || env.cushionBelow || env.entitySupport;
            boolean exempt = cushioned
                    || player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                    || player.hasStatusEffect(StatusEffects.LEVITATION)
                    || player.isGliding() || player.isUsingRiptide() || player.hasVehicle()
                    || player.isInvulnerable()
                    // Прятки switch all damage off in their arena, so no fall is ever billed there.
                    || player.getEntityWorld().getRegistryKey().getValue().getNamespace().equals("hideseek")
                    || tick < d.bounceGraceUntil
                    // A wind charge / explosion changes how vanilla bills the fall.
                    || tick - d.lastExplosionTick < 200;
            // Knockback no longer exempts: the fall damage of the landing itself
            // sends a velocity packet, so "velocity active" excused every fall
            // that hurt — exactly the falls a one-packet NoFall shortens.
            if (general.debugChecks) {
                BastionAC.LOGGER.info("[DEBUG nofall] {} landed descent={} exempt={} applied-so-far={}",
                        d.name, String.format("%.2f", descent), exempt, String.format("%.1f", d.fallDamageRaw));
            }
            if (descent > 3.0 && !exempt) {
                // Vanilla applies the damage while handling THIS packet, just
                // after us, so the verdict waits a few ticks for the event.
                d.pendingFallTick = tick;
                d.pendingFallDescent = descent;
            }
        }

        // ---------------- GROUNDSPOOF / NOFALL (signature path) ----------------
        // The first of the two NoFall detectors, and the one that already
        // works: a client that keeps insisting it is standing while the
        // server's collision scan says mid-air. That pattern is what a
        // sustained packet NoFall produces, and it is caught within a few
        // packets — this path is deliberately left exactly as it was.
        //
        // It stays reported as NOFALL (not merely GroundSpoof) whenever the
        // player is descending, because that is what it actually proves and
        // because NoFall is the check that carries weight. The outcome path
        // above is the SECOND detector, for implementations that never
        // produce this pattern at all; between them the two cover both the
        // sustained spoof and the single well-timed packet.
        boolean claimedGround = packet.isOnGround();
        if (claimedGround && !env.support && !env.liquid && !env.climbable && !env.bubble && d.airTicks > 2) {
            d.groundBuffer += 1;
            if (d.groundBuffer > 3) {
                d.groundBuffer = 1;
                CheckType type = dy < -0.5 ? CheckType.NOFALL : CheckType.GROUNDSPOOF;
                BastionAC.flag(player, d, type, String.format(Lang.t("onGround=true в воздухе, dy=%.2f"), dy));
                // The damage is not dealt here any more. Charging "fall so far"
                // each time the buffer overflowed billed a 20-block drop as
                // 2 + 1 + 1 and reset the fall origin each time; the landing
                // now bills the whole drop once, minus whatever was dealt.
            }
        } else {
            d.groundBuffer = Math.max(0, d.groundBuffer - 0.25);
        }

        // ---------------- JESUS ----------------
        // Walking on water: the client makes liquids solid for itself and then
        // reports standing — onGround with water, not a block, underneath.
        // Floating at the surface a vanilla client is IN the water (env.liquid)
        // and never on the ground; lily pads, boats and frost-walker ice are
        // solid support. What is left is the module.
        if (claimedGround && !env.support && !env.liquid && !player.hasVehicle()
                && fluidBelow(world, newX, newY, newZ)) {
            d.jesusBuffer += 1;
            if (d.jesusBuffer > 5) {
                d.jesusBuffer = 2;
                boolean mitigate = BastionAC.flag(player, d, CheckType.JESUS,
                        String.format(Lang.t("стоит на воде (onGround над жидкостью, dy=%.2f)"), dy));
                if (mitigate || BastionAC.instantSetback(d)) {
                    action = Action.max(action, Action.SETBACK);
                }
            }
        } else {
            d.jesusBuffer = Math.max(0, d.jesusBuffer - 0.5);
        }

        // ---------------- ANTIHUNGER (reverse ground spoof) ----------------
        // The mirror of NoFall: claiming to be airborne while standing on a
        // floor. Sprinting and jumping cost hunger only on the ground, so
        // AntiHunger (Wurst and Meteor alike) rewrites every packet to
        // onGround=false. A vanilla client standing on a block with no vertical
        // motion always reports the ground; thirty such packets in a row do not
        // happen by accident.
        if (!claimedGround && env.groundBelow && d.lastSupport && Math.abs(dy) < 1.0e-9
                && !env.liquid && !env.climbable && !env.web && !env.powder && !env.bubble) {
            d.falseAirStreak++;
            if (d.falseAirStreak >= 30) {
                d.falseAirStreak = 10;
                BastionAC.flag(player, d, CheckType.GROUNDSPOOF,
                        Lang.t("onGround=false на твёрдой земле 30 пакетов подряд — AntiHunger"));
            }
        } else {
            d.falseAirStreak = 0;
        }

        if (env.support && action == Action.NONE) {
            d.anchorValid = true;
            d.anchorX = newX;
            d.anchorY = newY;
            d.anchorZ = newZ;
            d.anchorYaw = packet.getYaw(player.getYaw());
            d.anchorPitch = packet.getPitch(player.getPitch());
        }

        d.lastEnvSupport = env.support;
        d.lastMoveTick = tick;
        update(d, newX, newY, newZ, dy, env);
        return finish(player, d, action, tick);
    }

    /**
     * The environment the analyzer's physics replay needs for this packet:
     * everything that legitimately bends vanilla movement, the block that
     * sets friction and the speed attribute (sprint and effects included).
     */
    private static void recordEnv(ServerPlayerEntity player, World world, Env env, double x, double y, double z) {
        int b = 0;
        if (env.support) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_SUPPORT;
        if (env.entitySupport) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_ENTITY_SUPPORT;
        if (env.groundBelow) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_GROUND_BELOW;
        if (env.floorFace) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_FLOOR_FACE;
        if (env.liquid) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_LIQUID;
        if (env.climbable) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_CLIMBABLE;
        if (env.web) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_WEB;
        if (env.powder) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_POWDER;
        if (env.bubble) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_BUBBLE;
        if (env.slimeBelow) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_SLIME;
        if (env.bedBelow) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_BED;
        if (env.iceBelow) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_ICE;
        if (env.cushionBelow) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_CUSHION;
        if (player.isUsingItem()) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_USING_ITEM;
        if (player.isSneaking()) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_SNEAKING;
        if (player.isSprinting()) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_SPRINTING;
        if (player.isGliding()) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_GLIDING;
        if (player.hasVehicle()) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_VEHICLE;
        if (player.isUsingRiptide()) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_RIPTIDE;
        if (player.getAbilities().flying) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_FLYING;
        if (player.hasStatusEffect(StatusEffects.LEVITATION)) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_LEVITATION;
        if (player.hasStatusEffect(StatusEffects.SLOW_FALLING)) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_SLOW_FALLING;
        BlockState ground = world.getBlockState(BlockPos.ofFloored(x, y - 0.5000001, z));
        BlockState feet = world.getBlockState(BlockPos.ofFloored(x, y, z));
        if (ground.getBlock().getVelocityMultiplier() < 1.0f || feet.getBlock().getVelocityMultiplier() < 1.0f
                || ground.getBlock().getJumpVelocityMultiplier() < 1.0f) {
            b |= dev.bastionac.telemetry.TelemetryFormat.ENV_HONEY_OR_SOUL;
        }
        if (feet.isOf(Blocks.SCAFFOLDING) || ground.isOf(Blocks.SCAFFOLDING)) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_SCAFFOLDING;
        if (inBlock(world, x, y, z, Blocks.SWEET_BERRY_BUSH)) b |= dev.bastionac.telemetry.TelemetryFormat.ENV_BERRY;
        // Anything that can shove the player (other players, mobs, boats) — on
        // the ground too: in a dense flock the sheep push a player standing
        // still, and Argus read those ticks as "speeding up with no keys held"
        // (10.6 % on an honest session of 19.10, 3.5 beta). The probe is the
        // player's own box widened by 0.3 on the ground, where shoving needs contact.
        double reach = env.support ? 0.3 : 0.6;
        if (!world.getOtherEntities(player, player.getBoundingBox().expand(reach, 0.3, reach),
                e -> e.isPushable() && e != player.getVehicle()).isEmpty()) {
            b |= dev.bastionac.telemetry.TelemetryFormat.ENV_PUSHABLE_NEAR;
        }
        dev.bastionac.telemetry.Telemetry.env(player, b, net.minecraft.block.Block.getRawIdFromState(ground),
                (float) player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED));
    }

    // ------------------------------------------------------------------
    // Timer by balance (netty thread → verdict on the server thread)
    // ------------------------------------------------------------------

    /**
     * Netty thread, at the packet's arrival: one client tick of the clock this
     * client is judged by — ClientTickEnd for 1.21.2+, move packets for older
     * clients whose tick ends Via makes up. See {@link dev.bastionac.core.TimerBalance}.
     */
    /**
     * The farthest a vanilla client can move horizontally this tick while
     * using an item, from the last tick's distance (3.6). Vanilla's
     * {@code travel} takes the friction from where the player was at the start
     * of a tick and keeps it for the next one; the acceleration comes from
     * where they are at the start of this one:
     * <ul>
     *   <li>last tick's distance × friction: the block's slipperiness × 0.91
     *       if the LAST tick began on the ground, air drag 0.91 if not;</li>
     *   <li>plus the acceleration of this tick, scaled by the item: on the
     *       ground speed × 0.216 / slip³, in the air 0.02 (0.026 sprinting);</li>
     *   <li>plus, on the tick the player jumps while sprinting, the 0.2
     *       sprint-jump boost, which item use does not take away.</li>
     * </ul>
     * Checked against a real stair climb with an apple: 0.2297 × 0.91 +
     * 0.0255 + 0.2 = 0.4345, recorded 0.435.
     * Sneaking scales the input by 0.3. No slack here: see {@link #noSlowSlack}.
     */
    static double noSlowStep(double lastH, boolean groundBefore, boolean lastGround, double dy, boolean sprinting,
                             double speedAttr, float useMul, float slip, boolean sneaking) {
        double input = 0.98 * useMul * (sneaking ? 0.3 : 1.0);
        double step = lastH * (groundBefore ? slip * 0.91 : 0.91);
        if (lastGround) {
            step += speedAttr * (0.21600002 / (slip * slip * slip)) * input;
            if (dy > 0.2 && sprinting) step += 0.2;
        } else {
            step += (sprinting ? 0.025999999 : 0.02) * input;
        }
        return step;
    }

    /**
     * The vanilla step with its slack. In the air nothing but drag and the
     * input acts, and the recorded honest climb matches to a thousandth: 2 %
     * and 0.012. On the ground the friction is read from one block: 5 % and 0.03.
     */
    static double noSlowSlack(double step, boolean startedOnGround) {
        return startedOnGround ? step * 1.05 + 0.03 : step * 1.02 + 0.012;
    }

    /**
     * One vehicle-steering packet came off the wire (netty thread). A pilot's
     * client sends exactly one per tick; the speed check above judges each
     * packet on its own, so two packets a tick, each within the limit, doubled
     * a boat's or horse's speed unseen — on any client version, and on a
     * 1.21.1 client the vehicle had no clock at all (3.5 beta, audit). Same
     * balance as the player timer, kept separately.
     */
    public static void vehicleTimerArrival(ServerPlayerEntity player, PlayerData d, long nowMs) {
        var g = BastionAC.config().general;
        double balance = d.vehicleTimer.onTick(nowMs, g.timerLagBankMs);
        if (balance >= -g.timerMaxAheadMs) return;
        double ahead = -balance;
        d.vehicleTimer.settle();
        var srv = BastionAC.server();
        if (srv != null) srv.execute(() -> vehicleTimerVerdict(player, d, ahead));
    }

    private static void vehicleTimerVerdict(ServerPlayerEntity player, PlayerData d, double aheadMs) {
        if (player.isRemoved() || BastionAC.data(player) != d) return;
        long tick = BastionAC.serverTick();
        if (player.isCreative() || player.isSpectator() || BastionAC.isAuthFrozen(player)) return;
        if (d.isFullyExempt(tick)) return;
        net.minecraft.entity.Entity vehicle = player.getRootVehicle();
        if (vehicle == null || vehicle == player) return;
        // Flag only: the packets already played out, and the anchor is the
        // latest accepted one — there is no earlier spot to put it back to.
        BastionAC.flag(player, d, CheckType.TIMER, String.format(java.util.Locale.ROOT,
                Lang.t("транспорт обогнал реальное время на %.0f мс (по пакетам транспорта)"), aheadMs));
    }

    public static void timerArrival(ServerPlayerEntity player, PlayerData d, long nowMs, boolean tickEnd) {
        int kind = d.tickEndKind;
        boolean tickClock = kind == 1;
        if (tickEnd != tickClock) return;
        var g = BastionAC.config().general;
        double balance = d.timer.onTick(nowMs, g.timerLagBankMs);
        if (balance >= -g.timerMaxAheadMs) return;
        double ahead = -balance;
        d.timer.settle();
        var srv = BastionAC.server();
        if (srv != null) srv.execute(() -> timerVerdict(player, d, ahead, tickClock));
    }

    private static void timerVerdict(ServerPlayerEntity player, PlayerData d, double aheadMs, boolean tickClock) {
        if (player.isRemoved() || BastionAC.data(player) != d) return;
        long tick = BastionAC.serverTick();
        if (player.isCreative() || player.isSpectator() || BastionAC.isAuthFrozen(player)) return;
        if (d.isFullyExempt(tick)) return;
        if (BastionAC.flag(player, d, CheckType.TIMER, String.format(java.util.Locale.ROOT,
                Lang.t("клиент обогнал реальное время на %.0f мс (%s)"), aheadMs,
                tickClock ? Lang.t("по концам тиков") : Lang.t("по пакетам движения")))) {
            setbackNow(player, d);
        }
    }

    /** A setback decided outside the move handler (the timer verdict). */
    public static void setbackNow(ServerPlayerEntity player, PlayerData d) {
        finish(player, d, Action.SETBACK, BastionAC.serverTick());
    }

    private static boolean overflowFly(ServerPlayerEntity player, PlayerData d, double dy) {
        if (d.flyBuffer <= 3) return false;
        d.flyBuffer = 1.5;
        boolean mitigate = BastionAC.flag(player, d, CheckType.FLY,
                String.format(Lang.t("dy=%.3f, в воздухе %d тиков"), dy, d.airTicks));
        return mitigate || BastionAC.instantSetback(d);
    }

    private static void update(PlayerData d, double x, double y, double z, double dy, Env env) {
        d.prevMoveX = d.lastX;
        d.prevMoveY = d.lastY;
        d.prevMoveZ = d.lastZ;
        d.lastMoveHDist = Math.sqrt((x - d.lastX) * (x - d.lastX) + (z - d.lastZ) * (z - d.lastZ));
        d.lastMoveHDistTick = BastionAC.serverTick();
        d.lastX = x;
        d.lastY = y;
        d.lastZ = z;
        // Criticals reads the last vertical delta purely to describe the violation
        // in the alert; the verdict itself comes from support + airTicks.
        d.lastDy1 = dy;
        d.lastDy = dy;
        d.lastSupport = env.support || env.liquid || env.climbable || env.web || env.powder || env.bubble;
    }

    /** Applies the setback (if allowed) and returns the final action. */
    private static Action finish(ServerPlayerEntity player, PlayerData d, Action action, long tick) {
        if (action != Action.SETBACK) return action;
        var general = BastionAC.config().general;
        if (!general.setbackEnabled) {
            return Action.CANCEL;
        }
        // Rate-limit the teleports themselves. The pending-teleport handshake
        // already spaces most of these out; this bounds the rest so a player who
        // keeps tripping a check is not fought with a teleport every tick.
        if (tick - d.lastSetbackTick < general.setbackMinIntervalTicks) {
            return Action.CANCEL;
        }

        if (!d.anchorValid) {
            d.anchorX = player.getX();
            d.anchorY = player.getY();
            d.anchorZ = player.getZ();
            d.anchorYaw = player.getYaw();
            d.anchorPitch = player.getPitch();
        }

        d.lastSetbackTick = tick;
        dev.bastionac.telemetry.Telemetry.acAction(player.getUuid(), dev.bastionac.telemetry.TelemetryFormat.ACT_SETBACK,
                String.format(java.util.Locale.ROOT, Lang.t("к %.2f %.2f %.2f"), d.anchorX, d.anchorY, d.anchorZ));
        player.networkHandler.requestTeleport(d.anchorX, d.anchorY, d.anchorZ, d.anchorYaw, d.anchorPitch);
        dev.bastionac.core.FreezeManager.afterSetback(player, d, tick, general.setbackFreezeTicks);
        return Action.SETBACK;
    }

    // ------------------------------------------------------------------
    // Environment probe
    // ------------------------------------------------------------------

    /**
     * True only when a collision shape presents a horizontal top surface directly
     * under the player's feet. A side wall may overlap the foot footprint, but its
     * top is far above the feet and must never reset Spider's climb baseline.
     */
    static boolean supportsFeetFromTop(Box collision, double feetY) {
        final double toleranceBelow = 0.0625; // packet rounding / vanilla ground epsilon
        final double toleranceAbove = 0.03125;
        return collision.maxY >= feetY - toleranceBelow && collision.maxY <= feetY + toleranceAbove;
    }

    /** A liquid right under the feet (JESUS): the block the player would be standing on, or the one below. */
    private static boolean fluidBelow(World world, double x, double y, double z) {
        return !world.getFluidState(BlockPos.ofFloored(x, y - 0.2, z)).isEmpty()
                || !world.getFluidState(BlockPos.ofFloored(x, y - 1.0, z)).isEmpty();
    }

    /** Whether a block of this kind intersects the player's actual hitbox. */
    private static boolean inBlock(World world, double x, double y, double z, net.minecraft.block.Block block) {
        Box body = new Box(x - 0.299, y + 0.001, z - 0.299, x + 0.299, y + 1.799, z + 0.299);
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(body.minX), MathHelper.floor(body.minY),
                MathHelper.floor(body.minZ), MathHelper.floor(body.maxX), MathHelper.floor(body.maxY),
                MathHelper.floor(body.maxZ))) {
            if (world.getBlockState(pos).isOf(block)) return true;
        }
        return false;
    }

    /** Whether a cobweb intersects the player's actual hitbox (the web slowdown's own test). */
    private static boolean inWeb(World world, double x, double y, double z) {
        Box body = new Box(x - 0.299, y + 0.001, z - 0.299, x + 0.299, y + 1.799, z + 0.299);
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(body.minX), MathHelper.floor(body.minY),
                MathHelper.floor(body.minZ), MathHelper.floor(body.maxX), MathHelper.floor(body.maxY),
                MathHelper.floor(body.maxZ))) {
            if (world.getBlockState(pos).isOf(Blocks.COBWEB)) return true;
        }
        return false;
    }

    /** Blocks vanilla lets you land on for free, or nearly so. */
    private static boolean isCushion(BlockState state) {
        return state.isOf(Blocks.HAY_BLOCK) || state.isOf(Blocks.HONEY_BLOCK)
                || state.isOf(Blocks.SLIME_BLOCK) || state.isIn(BlockTags.BEDS)
                || state.isOf(Blocks.COBWEB) || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.SCAFFOLDING) || state.isIn(BlockTags.WOOL);
    }

    private static Env scanEnv(ServerPlayerEntity player, World world, double x, double y, double z) {
        Env env = new Env();
        // Collision as the game computes it for THIS player. Without the entity
        // context some blocks answer differently from what the client stands on:
        // powder snow is a solid floor to a player in leather boots and empty
        // air to a context-less query, so walking a snowy slope in leather boots
        // read as hovering (Fly "dy=0,248, в воздухе 17 тиков") and its uphill
        // jumps as HighJump.
        net.minecraft.block.ShapeContext shapeCtx = net.minecraft.block.ShapeContext.of(player);
        double half = 0.3;
        Box supportBox = new Box(x - half - 0.05, y - 0.501, z - half - 0.05,
                x + half + 0.05, y + 0.05, z + half + 0.05);

        int x0 = MathHelper.floor(supportBox.minX);
        int x1 = MathHelper.floor(supportBox.maxX);
        int z0 = MathHelper.floor(supportBox.minZ);
        int z1 = MathHelper.floor(supportBox.maxZ);
        int yFeet = MathHelper.floor(y);
        int yLow = MathHelper.floor(y - 0.501) - 1; // one deeper: fences/walls stick up 1.5

        for (BlockPos pos : BlockPos.iterate(x0, yLow, z0, x1, yFeet, z1)) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;

            if (!env.support) {
                VoxelShape shape = state.getCollisionShape(world, pos, shapeCtx);
                if (!shape.isEmpty()) {
                    for (Box b : shape.getBoundingBoxes()) {
                        if (b.offset(pos.getX(), pos.getY(), pos.getZ()).intersects(supportBox)) {
                            env.support = true;
                            if (state.isOf(Blocks.SLIME_BLOCK)) env.slimeBelow = true;
                            if (state.isIn(BlockTags.BEDS)) env.bedBelow = true;
                            if (state.isIn(BlockTags.ICE)) env.iceBelow = true;
                            if (isCushion(state)) env.cushionBelow = true;
                            break;
                        }
                    }
                }
            } else {
                if (state.isOf(Blocks.SLIME_BLOCK)) env.slimeBelow = true;
                if (state.isIn(BlockTags.BEDS)) env.bedBelow = true;
                if (state.isIn(BlockTags.ICE)) env.iceBelow = true;
                if (isCushion(state)) env.cushionBelow = true;
            }
        }

        // Blocks intersecting the body volume: liquids and special media.
        int by0 = MathHelper.floor(y);
        int by1 = MathHelper.floor(y + 1.8);
        for (BlockPos pos : BlockPos.iterate(x0, by0, z0, x1, by1, z1)) {
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) env.liquid = true;
            if (state.isIn(BlockTags.CLIMBABLE)) env.climbable = true;
            if (state.isOf(Blocks.COBWEB)) env.web = true;
            if (state.isOf(Blocks.POWDER_SNOW)) env.powder = true;
            if (state.isOf(Blocks.BUBBLE_COLUMN)) env.bubble = true;
        }
        // Bubble columns also push from below the feet.
        if (!env.bubble && world.getBlockState(BlockPos.ofFloored(x, y - 0.5, z)).isOf(Blocks.BUBBLE_COLUMN)) {
            env.bubble = true;
        }
        if (player.isTouchingWater()) env.liquid = true;

        // Standing on a solid-collision entity (boats, shulkers, happy ghasts and
        // any future rideable platform). isCollidable(player) is the vanilla
        // authority for "another entity can stand on this", so it needs no
        // per-type list — and it fixes the happy-ghast GroundSpoof/Fly false flag:
        // a HappyGhastEntity is an AnimalEntity, not a VehicleEntity, so the old
        // instanceof list missed it and the player looked like they hovered in air.
        java.util.List<net.minecraft.entity.Entity> platforms = java.util.List.of();
        if (!env.support) {
            platforms = world.getOtherEntities(player, supportBox.expand(0.2),
                    e -> e.isCollidable(player));
            if (!platforms.isEmpty()) {
                env.support = true;
                env.entitySupport = true;
            }
        }

        // Strict floor-below probe: the collision must have a top face at foot
        // height. Horizontal overlap alone is insufficient: a full concrete wall,
        // fence or iron bars beside the player may intersect the footprint, but
        // their vertical side is not a floor and must not blind Spider.
        // Scan from two blocks below the feet, not only the foot slab. A fence,
        // wall or cobblestone wall is a SINGLE BlockPos whose collision sticks
        // up 1.5 blocks, so the block the player is standing ON sits one to two
        // levels below their feet. The old range started at floor(footBox.minY)
        // (feet level), so that BlockPos was never examined: standing on a
        // fence/wall top failed this floor probe even though env.support (whose
        // own loop scans deep, see yLow above) recognised it. climbBaseY then
        // never reset while the player stood on the fence, and the next hop
        // measured a 1.7-2.0 block gain against the stale baseline — exactly the
        // HighJump/Spider false positives in the live test ("подъём 1,80 … воздух
        // 0 т"). supportsFeetFromTop still demands a top face AT foot height, so
        // a side wall these extra rows scan past is rejected as before and
        // Spider keeps catching a genuine wall-climb.
        //
        // The footprint is the player's real hitbox (half-width 0.3, minus a
        // hair so a wall the player merely touches still does not count). It
        // used to be 0.28: a player standing on the last two hundredths of a
        // slab, a stair step or a block edge — which is exactly where a step-up
        // or a landing leaves you when you run up stairs holding jump — was
        // "not on the floor" for this probe while vanilla had them standing.
        // The take-off height then stayed at the step below, and the next jump
        // measured 0,5 + 1,25 = 1,75 blocks: the "HighJump 1,75" that every
        // player on the server collected on stairs and slabs.
        Box footBox = new Box(x - FOOT_HALF, y - 0.0625, z - FOOT_HALF, x + FOOT_HALF, y + 0.03125, z + FOOT_HALF);
        int footProbeLowY = MathHelper.floor(y) - 2;
        loop:
        for (BlockPos pos : BlockPos.iterate(
                MathHelper.floor(footBox.minX), footProbeLowY, MathHelper.floor(footBox.minZ),
                MathHelper.floor(footBox.maxX), MathHelper.floor(footBox.maxY), MathHelper.floor(footBox.maxZ))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(world, pos, shapeCtx);
            if (shape.isEmpty()) continue;
            for (Box b : shape.getBoundingBoxes()) {
                Box worldBox = b.offset(pos.getX(), pos.getY(), pos.getZ());
                if (worldBox.intersects(footBox) && supportsFeetFromTop(worldBox, y)) {
                    env.groundBelow = true;
                    if (Math.abs(worldBox.maxY - y) <= FACE_EPS) {
                        env.floorFace = true;
                        break loop;
                    }
                }
            }
        }
        // A boat, a shulker or a happy ghast is a floor too. Standing on one
        // counted as support but never as footing, so the jump off a boat was
        // measured from the ground the player walked up from.
        if (!env.groundBelow) {
            for (net.minecraft.entity.Entity e : platforms) {
                Box eb = e.getBoundingBox();
                if (eb.intersects(footBox) && supportsFeetFromTop(eb, y)) {
                    env.groundBelow = true;
                    break;
                }
            }
        }
        return env;
    }

    /** A top face at the feet anywhere under the hitbox widened by 2 cm. */
    private static boolean floorFaceNear(ServerPlayerEntity player, World world, double x, double y, double z) {
        double h = 0.32;
        Box probe = new Box(x - h, y - 0.0625, z - h, x + h, y + 0.03125, z + h);
        net.minecraft.block.ShapeContext ctx = net.minecraft.block.ShapeContext.of(player);
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(probe.minX), MathHelper.floor(y) - 2,
                MathHelper.floor(probe.minZ), MathHelper.floor(probe.maxX), MathHelper.floor(probe.maxY),
                MathHelper.floor(probe.maxZ))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            for (Box b : state.getCollisionShape(world, pos, ctx).getBoundingBoxes()) {
                Box wb = b.offset(pos.getX(), pos.getY(), pos.getZ());
                if (wb.intersects(probe) && supportsFeetFromTop(wb, y)) return true;
            }
        }
        return false;
    }

    /** Half-width of the foot probe: the player's hitbox (0.3) less a hair. */
    static final double FOOT_HALF = 0.2999;
    /** How close the feet must be to a top face to count as standing exactly on it. */
    static final double FACE_EPS = 0.002;

    private MovementChecks() {}
}
