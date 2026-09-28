package dev.bastionac.checks;

import dev.bastionac.BastionAC;
import dev.bastionac.core.Action;
import dev.bastionac.core.AimTracker;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.HitboxHistory;
import dev.bastionac.core.MathUtil;
import dev.bastionac.core.PlayerData;
import dev.bastionac.core.RhythmTracker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Combat analysis on attack packets: CPS, Reach (lag-compensated against the
 * victim's hitbox history), Angle (direction to the closest hitbox point, so
 * big mobs at point-blank do not false-flag), Walls (attacks through solid
 * blocks), MultiTarget (aura target switching), UseAttack (attacking while
 * eating/blocking/drawing — impossible on a vanilla client), NoSwing (attack
 * rate far above swing rate) and AutoClick (machine-even click spacing).
 */
public final class CombatChecks {

    /** At most 4 CPS violations per second, so VL growth stays predictable. */
    private static final long CPS_FLAG_INTERVAL_MS = (long) (1000 / MathUtil.CPS_FLAGS_PER_SECOND);

    public static Action onAttack(ServerPlayerEntity player, PlayerData d, Entity target) {
        long tick = BastionAC.serverTick();
        long now = System.currentTimeMillis();

        if (player.isCreative() || player.isSpectator()) return Action.NONE;
        if (d.isFullyExempt(tick) || BastionAC.isServerLagging(tick)) return Action.NONE;
        if (BastionAC.isAuthFrozen(player)) return Action.CANCEL;

        Action action = Action.NONE;

        // ---------------- GCD (aim grid) ----------------
        // Rotation deltas of a vanilla client are integer multiples of its
        // sensitivity step; interpolated aim (smooth aim-assist/killaura
        // paths) is not. The window is judged here, on attack, because a
        // combat cheat's rotation is exactly the rotations that lead into
        // hits — movement-time rotation alone would sweep in innocent
        // camera work. Observe-only by default (see ACConfig: gcd).
        if (d.gcdSamples >= 20) {
            double[] deltas = rotationWindow(d);
            double step = dev.bastionac.core.MathUtil.rotationGcd(deltas);
            if (step > 0) {
                double onGrid = dev.bastionac.core.MathUtil.onGridRatio(deltas, step);
                if (onGrid < 0.75) {
                    d.gcdOffGridRatio = onGrid;
                    d.gcdBuffer += 1;
                    if (d.gcdBuffer >= 2) {
                        d.gcdBuffer = 0;
                        BastionAC.flag(player, d, CheckType.GCD, String.format(
                                java.util.Locale.ROOT,
                                "%.0f%% ротаций мимо сетки чувствительности (шаг %.4f°)",
                                (1 - onGrid) * 100, step));
                    }
                } else {
                    d.gcdBuffer = Math.max(0, d.gcdBuffer - 0.5);
                }
            }
            d.gcdSamples = 0;
            d.gcdOffGrid = 0;
        }

        // ---------------- CPS ----------------
        // Hard ceiling, enforced two ways at once.
        //
        // (1) Physical cap: every attack past the limit is refused, so the cheat
        //     gets no benefit at all — not "eventually", not "once VL builds up".
        //     Cancelled packets still count toward the rate, so a clicker held
        //     above the limit stays locked out until it drops back under it.
        // (2) Graded evidence: VL per violation scales with the overshoot, and
        //     flags are rate-limited to 4/s so the arithmetic is predictable.
        //     At the default 14/20/25 CPS that is roughly 2 / 12 / 22 VL per
        //     second, i.e. a blatant 20 CPS clicker is kicked in ~1.5s while
        //     someone brushing 15 gets many seconds before anything happens.
        //
        // The old form only reacted above 20 CPS, which a vanilla client cannot
        // even produce (one attack packet per client tick) — it caught raw
        // packet spam and nothing else.
        int cps = d.recordAttackAndCountLastSecond(now);
        int maxCps = BastionAC.config().general.maxCps;
        if (cps > maxCps) {
            action = Action.max(action, Action.CANCEL);
            if (now - d.lastCpsFlagMs >= CPS_FLAG_INTERVAL_MS) {
                d.lastCpsFlagMs = now;
                BastionAC.flag(player, d, CheckType.CPS,
                        cps + " ударов/сек (лимит " + maxCps + ")",
                        MathUtil.cpsSeverity(cps, maxCps));
            }
        }

        // ---------------- REACH ----------------
        Vec3d eye = player.getEyePos();
        int ping = player.networkHandler.getLatency();
        // Lag-compensation backtrack window, bounded to the player's ping. Taking
        // the closest of ALL historical boxes hands a chaser the victim's oldest
        // (nearest) position, so the extra reach it grants is victim_speed *
        // ticksBack. Cap it at ~600ms and trim the free floor (3->2 ticks) to keep
        // backtrack under ~3 blocks even at high ping, without breaking real lag comp.
        // 20 ticks of history (one full second) instead of 12: mobile players
        // on carrier NAT sit at 300–800 ms and were being compensated for 600 ms
        // only, then banned for reach through the alert ladder.
        int ticksBack = Math.min(HitboxHistory.DEPTH, Math.max(2, ping / 50 + 2));
        boolean playerTarget = target instanceof ServerPlayerEntity;
        List<Box> boxes = playerTarget ? HitboxHistory.recent(target.getId(), ticksBack) : List.of();
        if (boxes.isEmpty()) boxes = List.of(target.getBoundingBox());

        double base = player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE);
        double margin = playerTarget ? 0.25 : 0.9; // mobs move without history — be lenient
        // Above 200 ms the backtrack itself gets coarse (one tick of victim
        // movement is a quarter block); widen the soft margin so the buffered
        // path, not the alert ladder, absorbs the jitter.
        if (playerTarget && ping > 200) margin = 0.35;
        double best = Double.MAX_VALUE;
        Box bestBox = boxes.get(0);
        for (Box b : boxes) {
            Box eb = b.expand(0.1);
            double dist = MathUtil.distanceToBox(eye.x, eye.y, eye.z,
                    eb.minX, eb.minY, eb.minZ, eb.maxX, eb.maxY, eb.maxZ);
            if (dist < best) {
                best = dist;
                bestBox = b;
            }
        }
        if (best > base + 1.2) {
            BastionAC.flag(player, d, CheckType.REACH,
                    String.format("%.2f бл (лимит %.2f, пинг %d)", best, base + 1.2, ping));
            action = Action.max(action, Action.CANCEL); // blatant — always cancel the hit
        } else if (best > base + margin) {
            d.reachBuffer += 1;
            if (d.reachBuffer >= 2) {
                d.reachBuffer = 0;
                if (BastionAC.flag(player, d, CheckType.REACH,
                        String.format("%.2f бл (лимит %.2f, пинг %d)", best, base + margin, ping))) {
                    action = Action.max(action, Action.CANCEL);
                }
            }
        } else {
            d.reachBuffer = Math.max(0, d.reachBuffer - 0.25);
        }

        // ---------------- ANGLE ----------------
        // Point-blank is still skipped (a big hitbox subtends a wide, noisy angle
        // there), but the dead zone is tightened 1.2 -> 1.0 so a close-range 360
        // aura has less room. The dot threshold + buffer below keep FPs out.
        if (best > 1.0) {
            Vec3d look = player.getRotationVec(1.0F);
            Box eb = bestBox.expand(0.1);
            double dot = MathUtil.lookDotToBox(eye.x, eye.y, eye.z, look.x, look.y, look.z,
                    eb.minX, eb.minY, eb.minZ, eb.maxX, eb.maxY, eb.maxZ);
            if (dot < 0.42) {
                d.angleBuffer += dot < -0.1 ? 2 : 1;
                if (d.angleBuffer >= 3) {
                    d.angleBuffer = 1;
                    if (BastionAC.flag(player, d, CheckType.ANGLE,
                            String.format("удар с отворотом %.0f°", Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))))))) {
                        action = Action.max(action, Action.CANCEL);
                    }
                }
            } else {
                d.angleBuffer = Math.max(0, d.angleBuffer - 0.5);
            }
        }

        // ---------------- AIM (hitbox-centre lock / snap-back) ----------------
        // The Angle check above asks whether the look was anywhere near the
        // target; a computed aura passes it perfectly, because it looks exactly
        // at the target — at its geometric centre, to a hundredth of a degree,
        // every hit. That exactness is the tell. See AimTracker.
        if (best >= AimTracker.MIN_DIST && best <= 6.0 && BastionAC.config().check(CheckType.AIM.key).enabled) {
            double err = aimError(player, d, target, eye, playerTarget, ping);
            // Only a lock that had to follow something counts. A crosshair
            // parked on a mob-farm outlet, hitting mobs that arrive at the same
            // spot, can sit on the centre by chance and stay there hit after
            // hit; the aim neither moved nor needed to. Evidence is a centre
            // hit after the look or the target moved.
            Vec3d centre = target.getBoundingBox().getCenter();
            boolean tracked = !d.hasLastAim
                    || AimTracker.rotationDelta(player.getYaw(), player.getPitch(), d.lastAimYaw, d.lastAimPitch) >= 1.0
                    || centre.squaredDistanceTo(d.lastAimCx, d.lastAimCy, d.lastAimCz) >= 0.04;
            d.hasLastAim = true;
            d.lastAimYaw = player.getYaw();
            d.lastAimPitch = player.getPitch();
            d.lastAimCx = centre.x;
            d.lastAimCy = centre.y;
            d.lastAimCz = centre.z;
            String lock = tracked ? AimTracker.recordLock(d, err) : null;
            if (lock != null && BastionAC.flag(player, d, CheckType.AIM, lock)) {
                action = Action.max(action, Action.CANCEL);
            }
            // A flick into the centre: remember where the head came from, and
            // let the next rotation packet tell whether it went straight back.
            if (d.rotSamples >= 2 && err <= 1.5) {
                double snap = AimTracker.rotationDelta(d.rotYaw[0], d.rotPitch[0], d.rotYaw[1], d.rotPitch[1]);
                if (snap >= AimTracker.MIN_SNAP_DEG) {
                    d.snapPending = true;
                    d.snapPreYaw = d.rotYaw[1];
                    d.snapPrePitch = d.rotPitch[1];
                    d.snapAngle = snap;
                    d.snapErr = err;
                    d.snapTick = tick;
                }
            }
        }

        // ---------------- WALLS ----------------
        // Dead zone tightened 2.0 -> 1.75. Kept non-zero because at point-blank
        // the eye->hitbox ray legitimately clips a corner when fighting around
        // thin cover; requiring BOTH the closest point and the centre to be
        // blocked (below) already suppresses those.
        if (best > 1.75) {
            Vec3d closest = closestPoint(eye, bestBox.expand(0.05));
            Vec3d center = bestBox.getCenter();
            if (rayBlocked(player, eye, closest) && rayBlocked(player, eye, center)) {
                d.wallsBuffer += 1;
                if (d.wallsBuffer >= 2) {
                    d.wallsBuffer = 0;
                    if (BastionAC.flag(player, d, CheckType.WALLS,
                            String.format("удар сквозь стену с %.1f бл", best))) {
                        action = Action.max(action, Action.CANCEL);
                    }
                }
            } else {
                d.wallsBuffer = Math.max(0, d.wallsBuffer - 0.5);
            }
        }

        // ---------------- MULTITARGET ----------------
        int distinct = d.recordTargetAndCountDistinct(target.getId(), tick, 6);
        if (distinct >= 3) {
            BastionAC.flag(player, d, CheckType.MULTITARGET, distinct + " разных целей за 6 тиков");
        }

        // ---------------- USEATTACK ----------------
        // Switching from right-click (eat/shield/bow) to a left-click attack
        // fires this transiently for legit players, so require a sustained
        // streak before acting.
        if (player.isUsingItem()) {
            d.useAttackStreak++;
            if (d.useAttackStreak >= 4) {
                if (BastionAC.flag(player, d, CheckType.USEATTACK, "атака во время использования предмета")) {
                    action = Action.max(action, Action.CANCEL);
                }
            }
        } else {
            d.useAttackStreak = 0;
        }

        // ---------------- CRITICALS ----------------
        // Ask the question vanilla asks. PlayerEntity.isCriticalHit in 1.21.11 is
        // fallDistance > 0 && !onGround && !climbing && !inWater && !blind &&
        // !hasVehicle && target is living && !sprinting — every term of it fed by
        // the client's own position packets. A forced-crit module sends a micro-hop
        // (onGround=false, a few centimetres of "fall") before each swing, so the
        // server computes a crit for a fall that never happened.
        //
        // The contradiction is with the server's own collision scan: the client
        // claims to be airborne and falling, while scanEnv still finds solid
        // support under the player and airTicks has not started counting. A real
        // jump leaves support on the first tick and lands ten-odd ticks later; a
        // real step off a ledge has no support at all. Neither can produce a crit
        // with the player still resting on the block they never left.
        //
        // The previous form asked for a same-tick micro-jump with an onGround
        // true->false flip AND player.isOnGround() — but the server's on-ground
        // flag IS the last move packet's flag (onPlayerMove -> setMovement), so
        // that pair could never both hold and the check never fired once.
        boolean wouldCrit = target instanceof net.minecraft.entity.LivingEntity
                && player.fallDistance > 0.0
                && !player.isOnGround()
                && !player.isClimbing()
                && !player.isTouchingWater()
                && !player.hasVehicle()
                && !player.isSprinting()
                && !player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS);
        // Height matters as much as support: walking off a one-block ledge and
        // swinging on the first air tick is a legitimate crit, and it also lands
        // on support (the ledge is still under the probe box) with airTicks at
        // zero. The two cases separate cleanly by height — a step-off is already
        // BELOW the ground it left, a faked hop is at or above it.
        boolean noDescent = player.getY() >= d.climbBaseY - 0.03;
        if (wouldCrit && d.lastSupport && d.airTicks <= 1 && noDescent
                && player.fallDistance < 0.6) {
            d.critBuffer += 1;
            if (d.critBuffer >= 2) {
                d.critBuffer = 0;
                if (BastionAC.flag(player, d, CheckType.CRITICALS,
                        String.format("крит без падения (падение %.3f, на опоре, воздух %d т)",
                                player.fallDistance, d.airTicks))) {
                    action = Action.max(action, Action.CANCEL);
                }
            }
        } else {
            // Gentle decay. Vanilla refuses a crit while sprinting, so in real PvP
            // most swings cannot qualify at all and a half-point drain per swing
            // ate the evidence faster than the forced crits could add it — which is
            // why this took fifteen-plus hits to reach a verdict.
            d.critBuffer = Math.max(0, d.critBuffer - 0.15);
        }

        // ---------------- NOSWING ----------------
        // Rate comparison, not recency. The old form asked "was there a swing in
        // the last 20 ticks", which one swing per second satisfied forever — a
        // killaura landing 20 hits/s while sending a single swing was invisible.
        // A vanilla client swings at least once per attack (it even swings on a
        // miss), so a sustained swing rate far below the attack rate is not
        // something a legitimate client can produce. The swing packet arrives
        // just after the attack it belongs to, so the counts can differ by one;
        // requiring a 2x shortfall leaves that slack untouched.
        int swings = d.countSwingsLastSecond(now);
        if (cps >= 6 && swings * 2 < cps) {
            d.noSwingBuffer += 1;
            if (d.noSwingBuffer >= 6) {
                d.noSwingBuffer = 0;
                BastionAC.flag(player, d, CheckType.NOSWING,
                        cps + " ударов/сек при " + swings + " замахах/сек");
            }
        } else {
            d.noSwingBuffer = Math.max(0, d.noSwingBuffer - 1);
        }

        // ---------------- AUTOCLICK ----------------
        // Shape of the click spacing, independent of its rate: a macro at a
        // human-plausible 9 CPS still spaces its clicks far more evenly than a
        // hand can. Observe-only by default — see ACConfig.
        RhythmTracker.Verdict rhythm = d.attackRhythm.push(now);
        if (rhythm != null) {
            if (rhythm.suspicious()) {
                d.autoClickBuffer += rhythm.signals() >= 2 ? 2 : 1;
                // Two windows, not three. With a 16-gap window that is ~32 clicks
                // per flag; at three windows of 24 gaps it took over 200 clicks at
                // a perfectly steady rate before staff heard anything, which no
                // real session ever produced.
                if (d.autoClickBuffer >= 2) {
                    d.autoClickBuffer = 0;
                    BastionAC.flag(player, d, CheckType.AUTOCLICK, rhythm.describe());
                }
            } else {
                d.autoClickBuffer = Math.max(0, d.autoClickBuffer - 1);
            }
        }

        return action;
    }

    /**
     * The smallest angle between the look the server holds and the target's
     * hitbox centre, over every place the client could have seen either end.
     */
    private static double aimError(ServerPlayerEntity player, PlayerData d, Entity target, Vec3d eye,
                                   boolean playerTarget, int ping) {
        double[] look = AimTracker.look(player.getYaw(), player.getPitch());
        double eyeH = player.getEyeHeight(player.getPose());
        List<double[]> eyes = new ArrayList<>(4);
        eyes.add(new double[]{eye.x, eye.y, eye.z});
        for (int i = 0; i < d.samples; i++) {
            eyes.add(new double[]{d.sampleX[i], d.sampleY[i] + eyeH, d.sampleZ[i]});
        }
        List<Box> boxes = playerTarget
                ? HitboxHistory.recent(target.getId(), Math.min(HitboxHistory.DEPTH, ping / 50 + 5))
                : List.of();
        if (boxes.isEmpty()) {
            Box now = target.getBoundingBox();
            boxes = List.of(now, now.offset(target.lastX - target.getX(), target.lastY - target.getY(),
                    target.lastZ - target.getZ()));
        }
        List<double[]> centres = new ArrayList<>(boxes.size() * 3);
        Vec3d prev = null;
        for (Box b : boxes) {
            Vec3d c = b.getCenter();
            centres.add(new double[]{c.x, c.y, c.z});
            // The client draws other entities between their last positions.
            if (prev != null) {
                for (double f : new double[]{1.0 / 3.0, 2.0 / 3.0}) {
                    centres.add(new double[]{prev.x + (c.x - prev.x) * f, prev.y + (c.y - prev.y) * f,
                            prev.z + (c.z - prev.z) * f});
                }
            }
            prev = c;
        }
        return AimTracker.minError(look, eyes, centres);
    }

    /**
     * Every rotation-bearing move packet: settles a pending flick (did the
     * head go straight back?) and shifts the rotation ring. Server thread.
     */
    public static void onRotationSample(ServerPlayerEntity player, PlayerData d, float yaw, float pitch) {
        if (d.snapPending) {
            d.snapPending = false;
            long tick = BastionAC.serverTick();
            if (tick - d.snapTick <= 10) {
                if (AimTracker.snappedBack(d.snapAngle, d.snapPreYaw, d.snapPrePitch, yaw, pitch)) {
                    d.snapBuffer += 1;
                    if (d.snapBuffer >= 2) {
                        d.snapBuffer = 0.5;
                        BastionAC.flag(player, d, CheckType.AIM, String.format(java.util.Locale.ROOT,
                                "рывок взгляда на %.0f° точно в центр цели (%.2f°) и сразу обратно",
                                d.snapAngle, d.snapErr), 1.5);
                    }
                } else {
                    d.snapBuffer = Math.max(0, d.snapBuffer - 0.5);
                }
            }
        }
        for (int i = d.rotYaw.length - 1; i > 0; i--) {
            d.rotYaw[i] = d.rotYaw[i - 1];
            d.rotPitch[i] = d.rotPitch[i - 1];
        }
        d.rotYaw[0] = yaw;
        d.rotPitch[0] = pitch;
        if (d.rotSamples < d.rotYaw.length) d.rotSamples++;
    }

    /** Every position-bearing move packet: shifts the claimed-position ring. */
    public static void onPositionSample(PlayerData d, double x, double y, double z) {
        for (int i = d.sampleX.length - 1; i > 0; i--) {
            d.sampleX[i] = d.sampleX[i - 1];
            d.sampleY[i] = d.sampleY[i - 1];
            d.sampleZ[i] = d.sampleZ[i - 1];
        }
        d.sampleX[0] = x;
        d.sampleY[0] = y;
        d.sampleZ[0] = z;
        if (d.samples < d.sampleX.length) d.samples++;
    }

    private static Vec3d closestPoint(Vec3d from, Box box) {
        return new Vec3d(
                Math.max(box.minX, Math.min(from.x, box.maxX)),
                Math.max(box.minY, Math.min(from.y, box.maxY)),
                Math.max(box.minZ, Math.min(from.z, box.maxZ)));
    }

    private static boolean rayBlocked(ServerPlayerEntity player, Vec3d from, Vec3d to) {
        HitResult hit = player.getEntityWorld().raycast(new RaycastContext(
                from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    // ------------------------------------------------------------------
    // GCD (aim grid) plumbing
    // ------------------------------------------------------------------

    /**
     * Fed from the move hook for every rotation-changing packet: the delta
     * window is collected in PlayerData and judged on the next attack.
     * Server thread only.
     */
    public static void onRotation(PlayerData d, float yaw, float pitch) {
        if (d.hasLastRotation) {
            float dy = yaw - d.lastYawDelta2;
            float dp = pitch - d.lastPitchDelta2;
            // Skip degenerate samples (teleport resync, look reset): their
            // deltas are wrap-around noise that no grid analysis survives.
            if (Math.abs(dy) > 180 || Math.abs(dp) > 180) {
                d.hasLastRotation = false;
                return;
            }
            if (Math.abs(dy) > 1.0e-3 || Math.abs(dp) > 1.0e-3) {
                d.pushRotationDelta(dy, dp);
            }
        }
        d.lastYawDelta2 = yaw;
        d.lastPitchDelta2 = pitch;
        d.hasLastRotation = true;
    }

    private static double[] rotationWindow(PlayerData d) {
        return d.rotationDeltas();
    }

    private CombatChecks() {}
}
