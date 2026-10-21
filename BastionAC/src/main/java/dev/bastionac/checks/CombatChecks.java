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

    /** Ticks either side of the round-trip moment a reach check accepts (jitter + client interpolation). */
    static final int REACH_BAND_SLACK = 3;

    /** At most 4 CPS violations per second, so VL growth stays predictable. */
    private static final long CPS_FLAG_INTERVAL_MS = (long) (1000 / MathUtil.CPS_FLAGS_PER_SECOND);

    public static Action onAttack(ServerPlayerEntity player, PlayerData d, Entity target) {
        long tick = BastionAC.serverTick();
        long now = System.currentTimeMillis();

        // A mace smash resets the attacker's fall (vanilla bills nothing for it):
        // the NoFall accounting must start the fall over from here too.
        if (player.getMainHandStack().isOf(net.minecraft.item.Items.MACE) && player.fallDistance > 1.5) {
            d.fallPeakY = player.getY();
        }
        if (player.isCreative() || player.isSpectator()) return Action.NONE;
        if (d.isFullyExempt(tick)) return Action.NONE;
        if (BastionAC.isAuthFrozen(player)) return Action.CANCEL;
        // A slow server tick pauses the checks that read timing or fresh entity
        // positions (reach, CPS, aim). Criticals reads neither: the fake hop is
        // an order of packets inside one client tick. Pausing it too meant any
        // tick over 80 ms — routine on the 2-core host, and something a player
        // can provoke — switched forced crits off for two seconds; on the test
        // server the hit itself was such a tick, so only the first of four
        // forced crits was ever judged.
        if (BastionAC.isServerLagging(tick)) return criticals(player, d, target);

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
            // bestGridFit: deltas with no common grid at all used to return
            // step 0 and skip the check — the purest interpolated aim passed.
            // The step this player's mouse was already seen on is a candidate
            // too, and every delta carries the precision it was measured at.
            dev.bastionac.core.MathUtil.GridFit fit = dev.bastionac.core.MathUtil.bestGridFit(
                    deltas, d.rotationNoise(), d.gridStep);
            double step = fit.step();
            if (!Double.isNaN(fit.ratio())) {
                double onGrid = fit.ratio();
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
        int[] rtt = dev.bastionac.telemetry.Telemetry.lagCompRange(player);
        int ping = rtt[1];
        // Lag compensation: the boxes the target had around the moment the
        // client saw it — one round trip back, ± REACH_BAND_SLACK ticks for
        // jitter and the client's interpolation. Twenty ticks of history cover
        // mobile players at 300–800 ms. Until 3.1.1 the window was every box
        // from now back to the full ping, and the closest of them won: a player
        // who delayed all of his traffic chose among up to twenty positions of
        // the victim — Backtrack by real lag (see HitboxHistory.reachBand).
        // Since 3.1.2 the band spans the fastest to the slowest recent round
        // trip (capped), so a jittery mobile ping keeps the box it really saw.
        int[] band = HitboxHistory.reachBand(rtt[0], rtt[1], REACH_BAND_SLACK);
        boolean playerTarget = target instanceof ServerPlayerEntity;
        List<Box> boxes = playerTarget ? HitboxHistory.between(target.getId(), band[0], band[1]) : List.of();
        if (boxes.isEmpty()) boxes = List.of(target.getBoundingBox());

        double base = player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE);
        double margin = playerTarget ? 0.25 : 0.9; // mobs move without history — be lenient
        // Above 200 ms the backtrack itself gets coarse (one tick of victim
        // movement is a quarter block); widen the soft margin so the buffered
        // path, not the alert ladder, absorbs the jitter.
        if (playerTarget && ping > 200) margin = 0.35;
        // What the client hit is where the target was one view delay ago, and
        // a mob has no history to look that up in: the server's box is all
        // there is. A sheep knocked back by the hit before (or by the sweep of
        // the hit on its neighbour) flies close to a block a tick, and the
        // client draws it ping + tracker interval + three lerp ticks late —
        // the 4.2–6.5 block "reaches" of 18–19.10 in a flock were exactly
        // that. Allow the distance it can have covered; when the view delay is
        // past what any allowance can cover (the multi-second pings of a link
        // drowning in a flock's entity updates), the hit cannot be judged.
        // A player target whose round trip is past the twenty ticks of history
        // cannot be judged either: the band was clamped to boxes far newer
        // than the one the client saw (3.5 build 2).
        double drift = 0;
        boolean judgeable;
        if (playerTarget) {
            judgeable = rtt[1] / 50 + REACH_BAND_SLACK <= HitboxHistory.DEPTH - 1;
        } else {
            double speed = Math.max(target.getVelocity().length(), Math.sqrt(target.squaredDistanceTo(
                    target.lastX, target.lastY, target.lastZ)));
            boolean hurt = target instanceof net.minecraft.entity.LivingEntity le && le.hurtTime > 0;
            drift = mobDriftAllowance(speed, hurt, ping, target.getType().getTrackTickInterval());
            judgeable = !Double.isNaN(drift);
        }
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
        if (!judgeable) {
            // No verdict either way: keep the buffer as it is.
        } else if (best > base + 1.2 + drift) {
            BastionAC.flag(player, d, CheckType.REACH,
                    String.format("%.2f бл (лимит %.2f, пинг %d)", best, base + 1.2 + drift, ping));
            action = Action.max(action, Action.CANCEL); // blatant — always cancel the hit
        } else if (best > base + margin + drift) {
            d.reachBuffer += 1;
            if (d.reachBuffer >= 2) {
                d.reachBuffer = 0;
                if (BastionAC.flag(player, d, CheckType.REACH,
                        String.format("%.2f бл (лимит %.2f, пинг %d)", best, base + margin + drift, ping))) {
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
        // Three targets in six ticks is what a hand does too: clicking ten
        // times a second into a dense flock, each click lands on whichever
        // sheep is under the crosshair now (18.10: eleven alerts on one player,
        // every hit on the sheep they were looking at). A multi-aura's tell is
        // hitting targets it is not looking at, spread around the player. So
        // a switch counts only when the look ray missed the target (with room
        // for a mob's unknown drift) and the distinct targets of the window
        // span a real angle (3.5 build 2).
        boolean lookHits = lookRayHits(player, eye, target, playerTarget ? 0.15 : 0.6 + drift);
        double dirYaw = Math.toDegrees(Math.atan2(-(target.getX() - eye.x), target.getZ() - eye.z));
        int offLook = d.recordTargetAndCountOffLook(target.getId(), tick, 6, lookHits, (float) dirYaw);
        if (offLook >= 2 && d.recentTargetSpread(tick, 6) >= MULTITARGET_MIN_SPREAD_DEG
                && d.distinctRecentTargets(tick, 6) >= 3) {
            BastionAC.flag(player, d, CheckType.MULTITARGET, String.format(java.util.Locale.ROOT,
                    "%d разных целей за 6 тиков, %d мимо прицела, разброс %.0f°",
                    d.distinctRecentTargets(tick, 6), offLook, d.recentTargetSpread(tick, 6)));
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

        action = Action.max(action, criticals(player, d, target));

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

    /**
     * InteractWalls for entities (3.3.1): a right-click on an entity — a
     * villager to trade with, an item frame, an armour stand, a boat or a
     * horse to mount — through a wall. The client picks the entity only when
     * no block outline is nearer along its ray, and it says where on the
     * entity it clicked ({@code at}, relative to the entity's position): one
     * ray to that point. Without it, the centre, the nearest point and the
     * head. Outlines, as the client sees them: trading over a fence or a slab
     * through a one-block gap stays legal. Returns whether to refuse it.
     */
    public static boolean interactWalls(ServerPlayerEntity player, PlayerData d, Entity target, Vec3d at) {
        if (!BastionAC.config().check(CheckType.INTERACTWALLS.key).enabled) return false;
        if (player.isCreative() || player.isSpectator()) return false;
        long tick = BastionAC.serverTick();
        if (d.isFullyExempt(tick) || BastionAC.isServerLagging(tick)) return false;
        Box box = target.getBoundingBox();
        Vec3d eye = player.getEyePos();
        double dist = Math.sqrt(box.squaredMagnitude(eye));
        // Point-blank (a boat being boarded, a horse alongside) and out of range are not this check's business.
        if (dist <= 1.5 || dist > 7.0) return false;
        Vec3d[] points = at != null
                ? new Vec3d[]{target.getEntityPos().add(at)}
                : new Vec3d[]{box.getCenter(), closestPoint(eye, box.expand(0.05)),
                        new Vec3d(box.getCenter().x, box.maxY - 0.1, box.getCenter().z)};
        Boolean seen = WorldChecks.seenFromSomewhere(player, d, from -> {
            for (Vec3d p : points) if (!outlineBlocked(player, from, p)) return true;
            return false;
        });
        if (seen == null) return false;
        if (seen) {
            WorldChecks.interactWallsClean(d);
            return false;
        }
        WorldChecks.interactWallsViolation(player, d, String.format("взаимодействие сквозь блок с %s",
                net.minecraft.registry.Registries.ENTITY_TYPE.getId(target.getType()).getPath()));
        return true;
    }

    /** A block outline (what the client's crosshair stops at) between the two points. */
    private static boolean outlineBlocked(ServerPlayerEntity player, Vec3d from, Vec3d to) {
        HitResult hit = player.getEntityWorld().raycast(new RaycastContext(
                from, to, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getPos().squaredDistanceTo(from) > 1.0e-4;
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

    /** The distinct targets of a multitarget window must span at least this (yaw, degrees). */
    static final double MULTITARGET_MIN_SPREAD_DEG = 60.0;
    /** Ticks a mob is drawn behind the server: client lerp steps on top of ping and tracker interval. */
    static final int MOB_LERP_TICKS = 3;
    /** Past this view delay a mob hit is not judged at all. */
    static final int MOB_MAX_VIEW_DELAY_TICKS = 20;
    /** A struck mob's knockback flight, on top of its current speed. */
    static final double MOB_KNOCKBACK_DRIFT = 2.0;
    static final double MOB_DRIFT_CAP = 4.0;

    /**
     * How far a mob may have moved between the position the client drew and the
     * server's current box: speed (blocks/tick) over the view delay, plus a
     * knockback flight when it was hurt recently. NaN when the delay is too
     * long to judge the hit at all. Pure, unit-tested.
     */
    static double mobDriftAllowance(double speedPerTick, boolean hurtRecently, int pingMs, int trackInterval) {
        int delay = (Math.max(0, pingMs) + 49) / 50 + Math.max(1, trackInterval) + MOB_LERP_TICKS;
        if (delay > MOB_MAX_VIEW_DELAY_TICKS) return Double.NaN;
        double drift = Math.max(0, speedPerTick) * delay + (hurtRecently ? MOB_KNOCKBACK_DRIFT : 0);
        return Math.min(MOB_DRIFT_CAP, drift);
    }

    /** Whether the player's look ray passes through the target's box grown by {@code grow}. */
    private static boolean lookRayHits(ServerPlayerEntity player, Vec3d eye, Entity target, double grow) {
        Box b = target.getBoundingBox().expand(grow);
        if (b.contains(eye)) return true;
        Vec3d look = player.getRotationVec(1.0F);
        return b.raycast(eye, eye.add(look.multiply(8))).isPresent();
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
                // A pitch that ends (or starts) pinned at ±90° was clamped by
                // the client: the mouse moved further than the head did.
                boolean clamped = Math.abs(pitch) >= 89.999f || Math.abs(d.lastPitchDelta2) >= 89.999f;
                d.pushRotationDelta(dy, clamped ? Float.NaN : dp,
                        (float) MathUtil.angleNoise(yaw, d.lastYawDelta2),
                        (float) MathUtil.angleNoise(pitch, d.lastPitchDelta2));
                learnGridStep(d);
            }
        }
        d.lastYawDelta2 = yaw;
        d.lastPitchDelta2 = pitch;
        d.hasLastRotation = true;
    }

    /**
     * Every forty fresh deltas, remember the step the player's mouse sits on —
     * only when it explains nearly all of them. Judging a window of fast swings
     * needs that step: such a window has no small turn to read it from, and
     * every candidate it offers itself is a multiple (3.5 build 2).
     */
    private static void learnGridStep(PlayerData d) {
        if (d.gridLearnSamples < 40) return;
        d.gridLearnSamples = 0;
        MathUtil.GridFit fit = MathUtil.bestGridFit(d.rotationDeltas(), d.rotationNoise(), d.gridStep);
        if (!Double.isNaN(fit.ratio()) && fit.ratio() >= 0.9 && fit.judged() >= 20) d.gridStep = fit.step();
    }

    private static double[] rotationWindow(PlayerData d) {
        return d.rotationDeltas();
    }

    private CombatChecks() {}

    /** Forced crits; see the comment inside. Also runs while the server lags. */
    private static Action criticals(ServerPlayerEntity player, PlayerData d, Entity target) {
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
        //
        // 2.27: "support + airTicks <= 1 + not below the take-off height" was too
        // loose and convicted honest fighters in three ways seen on prod:
        //   - lava: vanilla halves fallDistance there but never zeroes it, and a
        //     liquid counted as "support" with airTicks 0, so every hit while
        //     bobbing in lava read as a forced crit;
        //   - landing on a higher block (or anywhere the wide support probe
        //     already reaches the floor below): a real jump-crit a few tenths
        //     above a block top looked "supported, no descent";
        //   - duels: both fighters hop and land on uneven ground constantly.
        // What a forced crit actually needs is a fall that never happened, and
        // that leaves one exact trace: the client closed its previous tick
        // standing on a floor, and now — still inside the next tick, before any
        // real movement — claims to be airborne and falling while its feet are
        // back exactly on that same top face. Every known implementation (Meteor
        // Packet/Bypass, Wurst, LiquidBounce, BastionTester) ends its fake hop
        // there. A vanilla client cannot: moving down onto a face is a collision,
        // and a collision sets onGround.
        boolean wouldCrit = target instanceof net.minecraft.entity.LivingEntity
                && player.fallDistance > 0.0
                && !player.isOnGround()
                && !player.isClimbing()
                && !player.isTouchingWater()
                && !player.isInLava()
                && !player.hasVehicle()
                && !player.isSprinting()
                && !player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS);
        boolean fakeHop = d.groundedAtTickEnd && d.lastOnFloorFace;
        if (wouldCrit && fakeHop && player.fallDistance < 0.6) {
            d.critBuffer += 1;
            if (d.critBuffer >= 2) {
                d.critBuffer = 0;
                if (BastionAC.flag(player, d, CheckType.CRITICALS,
                        String.format("крит без падения (падение %.3f, ноги на блоке, тик начат на земле)",
                                player.fallDistance))) {
                    return Action.CANCEL;
                }
            }
        } else {
            // Gentle decay. Vanilla refuses a crit while sprinting, so in real PvP
            // most swings cannot qualify at all and a half-point drain per swing
            // ate the evidence faster than the forced crits could add it — which is
            // why this took fifteen-plus hits to reach a verdict.
            d.critBuffer = Math.max(0, d.critBuffer - 0.15);
        }
        return Action.NONE;
    }
}
