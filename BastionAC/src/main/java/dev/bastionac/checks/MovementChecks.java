package dev.bastionac.checks;

import dev.bastionac.BastionAC;
import dev.bastionac.core.Action;
import dev.bastionac.core.CheckType;
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
        // confirms, so we just wait for the client to arrive at the target.
        if (d.teleportPending) {
            if (packet.changesPosition()) {
                double nx = packet.getX(player.getX());
                double ny = packet.getY(player.getY());
                double nz = packet.getZ(player.getZ());
                double ddx = nx - d.tpX;
                double ddy = ny - d.tpY;
                double ddz = nz - d.tpZ;
                if (ddx * ddx + ddy * ddy + ddz * ddz < 2.25) {
                    d.teleportPending = false;
                    d.resetMovement(d.tpX, d.tpY, d.tpZ);
                    d.resetMovePackets();
                }
            }
            if (d.teleportPending && tick - d.teleportPendingSince > 100) {
                d.teleportPending = false; // failsafe: never dead-lock the checks
                d.resetMovement(player.getX(), player.getY(), player.getZ());
            }
            if (d.teleportPending) {
                return Action.CANCEL;
            }
        }

        if (player.isCreative() || player.isSpectator() || BastionAC.isAuthFrozen(player)) {
            d.resetMovement(player.getX(), player.getY(), player.getZ());
            return Action.NONE;
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

        // TIMER — packet-rate check; skip entirely for vehicles (they burst).
        if (!movementExempt) {
            long span = d.movePacketSpan(now);
            // N timestamps span N-1 gaps, not N.
            long minSpan = (PlayerData.TIMER_WINDOW - 1L) * BastionAC.config().general.timerMinIntervalMs;
            if (span >= 0 && span < minSpan) {
                d.resetMovePackets();
                if (BastionAC.flag(player, d, CheckType.TIMER,
                        PlayerData.TIMER_WINDOW + " пакетов за " + span + " мс (минимум " + minSpan + ")")) {
                    action = Action.max(action, Action.SETBACK);
                }
            }
        }

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
            if (d.blinkLastRefillMs == 0) d.blinkLastRefillMs = now;
            long elapsed = now - d.blinkLastRefillMs;
            if (elapsed > 0) {
                d.blinkTokens += elapsed / 50.0;
                d.blinkLastRefillMs = now;
                if (d.blinkTokens > 20) d.blinkTokens = 20; // cap the bucket
            }
            d.blinkTokens -= 1;
            d.recordBurstPacket(now);
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
                                "всплеск пакетов с σ(IAT)=%.2f мс", iatStd))) {
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
            double descent = d.pendingFallDescent;
            boolean tookDamage = d.lastFallDamageTick >= d.pendingFallTick - 1;
            d.pendingFallTick = -1;
            d.pendingFallDescent = 0;
            if (!tookDamage && !d.isFullyExempt(tick) && !BastionAC.isServerLagging(tick)
                    && !player.isCreative() && !player.isSpectator()) {
                d.noFallBuffer += 1;
                if (d.noFallBuffer >= 2) {
                    d.noFallBuffer = 0;
                    BastionAC.flag(player, d, CheckType.NOFALL,
                            String.format("падение %.1f бл без урона", descent));
                    // Charge the fall the client dodged.
                    float dmg = (float) Math.min(descent - 3.0, 40.0);
                    if (dmg > 0) player.serverDamage(player.getDamageSources().fall(), dmg);
                }
            } else {
                d.noFallBuffer = Math.max(0, d.noFallBuffer - 0.5);
            }
        }

        // Anti-KB: feed this position sample into any armed knockback transaction.
        // Runs before BigMove so a setback from an unrelated check cannot abort the
        // measurement mid-window; the transaction anchors on the server position.
        BastionAC.tickKbVerify(player, d, newX, newZ, tick);

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
        if (speedAttr > 0.13) bigMoveLimit = Math.max(bigMoveLimit, 6.0 * (speedAttr / 0.1));
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
        if (hDist > bigMoveLimit || dy > bigMoveLimit || -dy > descentAllow) {
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
            // A move another mod announced (the duel arena): same treatment
            // as a server reposition — re-anchor on the server, no flag.
            if (sdx * sdx + sdy * sdy + sdz * sdz > 36 || tick < d.serverMoveGraceUntil) {
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
            BastionAC.flag(player, d, CheckType.BIGMOVE, String.format(
                    "%.1f бл за пакет (гор. %.1f/%.1f, верт. %.1f, пауза %d т)%s",
                    Math.sqrt(distSq), hDist, bigMoveLimit, dy, moveGap,
                    padded ? " после " + d.zeroMovesThisClientTick + " пустых пакетов — MaceDMG/ArrowDMG" : ""),
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
        if (general.shadowTelemetryEnabled) {
            ShadowTelemetry.recordEnvironmentScanNanos(System.nanoTime() - scanStartNanos);
        }

        // Anti-KB: a medium that swallows the impulse (water, lava, cobweb,
        // powder snow, a ladder) makes the air-drag displacement model
        // meaningless, so the armed transaction is dropped instead of convicting.
        if (d.kbPending && (env.liquid || env.web || env.climbable || env.powder)) {
            d.kbBlocked = true;
        }

        if (movementExempt) {
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
                        String.format("средняя %.2f бл/тик, лимит %.2f", avgSpeed, avgLimit));
                if (mitigate || BastionAC.config().general.instantMovementSetback) {
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
        boolean groundStable = env.support && d.lastSupport && !env.liquid && !env.entitySupport;
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
                            String.format("на земле %.2f бл/тик, лимит %.2f", hDist, groundLimit));
                    if (mitigate || BastionAC.config().general.instantMovementSetback) {
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
        // Vanilla applies a hard 0.2x multiplier to movement input while an item
        // is in use (eating, bow draw, shield block). After the first few ticks
        // of inertia bleed off, a using-item player cannot sustain anywhere near
        // sprint speed. A NoSlow module skips the multiplier, so the player keeps
        // full speed — detectable as a sustained scalar excess with no raycast.
        if (player.isUsingItem()) {
            if (d.usingItemSinceTick < 0) d.usingItemSinceTick = tick;
            long usingFor = tick - d.usingItemSinceTick;
            // Only judge a player standing on solid ground and not sliding. Ice
            // carries momentum far above the use-penalty for many ticks (blue ice
            // reaches 0.5+ per tick), and airborne momentum from a jump taken
            // before the item went up bleeds off slowly — both used to be read as
            // NoSlow and set the player back mid-meal.
            boolean judgeable = env.support && d.lastSupport && !env.liquid
                    && !env.entitySupport && d.iceMemoryTicks == 0
                    && tick >= d.bounceGraceUntil;
            if (usingFor > general.noSlowGraceTicks && judgeable) {
                double useLimit = groundMax * general.noSlowMaxFactor;
                // Sneaking is a movement multiplier, not an attribute, so the
                // MOVEMENT_SPEED-derived ceiling does not shrink with it — which
                // handed NoSlow a free pass: crouch-walking while using an item
                // stayed far under a limit built for standing.
                if (player.isSneaking()) useLimit *= 0.5;
                if (velocityActive) useLimit += Math.max(0, d.velocityBudget);
                if (general.debugChecks) {
                    BastionAC.LOGGER.info("[DEBUG noslow] {} h={} limit={} usingFor={} sneak={} buffer={}",
                            d.name, String.format("%.3f", hDist), String.format("%.3f", useLimit),
                            usingFor, player.isSneaking(), String.format("%.1f", d.noSlowBuffer));
                }
                if (hDist > useLimit) {
                    d.noSlowBuffer += 1;
                    // Six consecutive ticks, not four. The server keeps
                    // isUsingItem() true until the client's release packet lands,
                    // so a player who taps a food item and sprints on is "using" it at
                    // full speed for a ping's worth of ticks through no fault of
                    // their own. A real NoSlow holds the excess for seconds.
                    if (d.noSlowBuffer > 5) {
                        d.noSlowBuffer = 1;
                        boolean mitigate = BastionAC.flag(player, d, CheckType.NOSLOW,
                                String.format("%.2f бл/тик с предметом (лимит %.2f)", hDist, useLimit));
                        if (mitigate || BastionAC.config().general.instantMovementSetback) {
                            action = Action.max(action, Action.SETBACK);
                        }
                    }
                } else {
                    d.noSlowBuffer = Math.max(0, d.noSlowBuffer - 1);
                }
            }
        } else {
            d.usingItemSinceTick = -1;
            d.noSlowBuffer = Math.max(0, d.noSlowBuffer - 0.5);
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
                        String.format("в паутине %.2f бл/тик (без мода не больше 0.08)", hDist));
                if (mitigate || BastionAC.config().general.instantMovementSetback) {
                    action = Action.max(action, Action.SETBACK);
                }
            }
        } else {
            d.webFastStreak = Math.max(0, d.webFastStreak - 1);
        }

        if (velocityActive) d.velocityBudget *= 0.88;

        boolean resting =env.support || env.liquid || env.climbable || env.web || env.powder || env.bubble;
        if (resting) {
            d.airTicks = 0;
            d.hoverStreak = 0;
            d.glideStreak = 0;
            d.flyBuffer = Math.max(0, d.flyBuffer - 1);
            d.fallPeakY = newY; // reset fall origin
        } else {
            d.airTicks++;
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

        // Spider footing: only a floor directly below, a climbable, liquid or
        // special medium resets the height baseline — NOT a side wall (which the
        // wide support box counts as "resting"). This is what un-blinds Spider.
        boolean footing = env.groundBelow || env.climbable || env.liquid || env.web || env.powder || env.bubble;
        if (footing) {
            d.climbBaseY = newY;
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
                            String.format("подъём %.2f у стены, dy=%.2f, воздух %d т", climbGain, dy, d.airTicks))) {
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
        if (!footing && dy > 0.0 && !player.isGliding() && !player.isUsingRiptide()) {
            double maxGain = 1.35;
            StatusEffectInstance jb = player.getStatusEffect(StatusEffects.JUMP_BOOST);
            if (jb != null) maxGain += (jb.getAmplifier() + 1) * 2.0;
            double gain = newY - d.climbBaseY;
            boolean exempt = player.hasStatusEffect(StatusEffects.LEVITATION)
                    || tick < d.bounceGraceUntil || velocityActive;
            if (gain > maxGain + 0.35 && !exempt && d.highJumpBase != d.climbBaseY) {
                d.highJumpBase = d.climbBaseY;
                boolean mitigate = BastionAC.flag(player, d, CheckType.FLY, String.format(
                        "прыжок на %.2f бл без опоры (предел %.2f) — HighJump", gain, maxGain));
                if (mitigate || BastionAC.config().general.instantMovementSetback) {
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
                        String.format("по лестнице %.3f бл/тик (без мода 0.118) — FastLadder", dy), 0.5);
                if (mitigate || BastionAC.config().general.instantMovementSetback) {
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
            double descent = d.fallPeakY - newY;
            boolean cushioned = env.liquid || env.web || env.climbable || env.powder
                    || env.bubble || env.cushionBelow || env.entitySupport;
            boolean exempt = cushioned
                    || player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                    || player.hasStatusEffect(StatusEffects.LEVITATION)
                    || player.isGliding() || player.isUsingRiptide() || player.hasVehicle()
                    || player.isInvulnerable() || velocityActive
                    || tick < d.bounceGraceUntil;
            if (descent > 4.0 && !exempt) {
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
                BastionAC.flag(player, d, type, String.format("onGround=true в воздухе, dy=%.2f", dy));
                // NoFall spoofs onGround to dodge fall damage — deal it ourselves.
                if (type == CheckType.NOFALL) {
                    double fallDist = d.fallPeakY - newY;
                    if (fallDist > 3.5) {
                        float dmg = (float) Math.min(fallDist - 3.0, 40.0);
                        player.serverDamage(player.getDamageSources().fall(), dmg);
                        d.fallPeakY = newY;   // charge each fall once
                        // The outcome path must not bill the same fall again.
                        d.pendingFallTick = -1;
                        d.pendingFallDescent = 0;
                        d.lastFallDamageTick = tick;
                    }
                }
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
                        String.format("стоит на воде (onGround над жидкостью, dy=%.2f)", dy));
                if (mitigate || BastionAC.config().general.instantMovementSetback) {
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
                        "onGround=false на твёрдой земле 30 пакетов подряд — AntiHunger");
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

    private static boolean overflowFly(ServerPlayerEntity player, PlayerData d, double dy) {
        if (d.flyBuffer <= 3) return false;
        d.flyBuffer = 1.5;
        boolean mitigate = BastionAC.flag(player, d, CheckType.FLY,
                String.format("dy=%.3f, в воздухе %d тиков", dy, d.airTicks));
        return mitigate || BastionAC.config().general.instantMovementSetback;
    }

    private static void update(PlayerData d, double x, double y, double z, double dy, Env env) {
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
        player.networkHandler.requestTeleport(d.anchorX, d.anchorY, d.anchorZ, d.anchorYaw, d.anchorPitch);
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
                VoxelShape shape = state.getCollisionShape(world, pos);
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
        if (!env.support) {
            var entities = world.getOtherEntities(player, supportBox.expand(0.2),
                    e -> e.isCollidable(player));
            if (!entities.isEmpty()) {
                env.support = true;
                env.entitySupport = true;
            }
        }

        // Strict floor-below probe: the collision must have a top face at foot
        // height. Horizontal overlap alone is insufficient: a full concrete wall,
        // fence or iron bars beside the player may intersect the footprint, but
        // their vertical side is not a floor and must not blind Spider.
        Box footBox = new Box(x - 0.28, y - 0.0625, z - 0.28, x + 0.28, y + 0.03125, z + 0.28);
        loop:
        for (BlockPos pos : BlockPos.iterate(
                MathHelper.floor(footBox.minX), MathHelper.floor(footBox.minY), MathHelper.floor(footBox.minZ),
                MathHelper.floor(footBox.maxX), MathHelper.floor(footBox.maxY), MathHelper.floor(footBox.maxZ))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(world, pos);
            if (shape.isEmpty()) continue;
            for (Box b : shape.getBoundingBoxes()) {
                Box worldBox = b.offset(pos.getX(), pos.getY(), pos.getZ());
                if (worldBox.intersects(footBox) && supportsFeetFromTop(worldBox, y)) {
                    env.groundBelow = true;
                    break loop;
                }
            }
        }
        return env;
    }

    private MovementChecks() {}
}
