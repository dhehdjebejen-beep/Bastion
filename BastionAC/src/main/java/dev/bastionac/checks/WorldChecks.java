package dev.bastionac.checks;

import dev.bastionac.BastionAC;
import dev.bastionac.core.Action;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.PlayerData;
import dev.bastionac.core.ShadowTelemetry;
import dev.bastionac.core.RhythmTracker;
import dev.bastionac.core.XrayTracker;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * World interaction checks.
 *
 * <p>FastBreak uses the vanilla mining formula
 * ({@code calcBlockBreakingDelta}) so legitimate insta-mining with maxed
 * tools/haste is never flagged: if the formula says the block needs N ticks,
 * finishing dramatically earlier is a violation.
 *
 * <p>PacketMine is deliberately limited to the two rules the protocol actually
 * makes impossible — a break armed or finished from outside interaction range.
 * The "a vanilla client cannot switch slots / attack while mining" heuristics
 * that used to live here were simply false (see {@link #invalidateMining}) and
 * auto-banned legitimate players.
 */
public final class WorldChecks {

    /** Flag margin on top of the interaction range. Arming uses +1; a violation
     *  needs +3 so latency and a fast turn can never manufacture one. */
    private static final double REACH_FLAG_MARGIN = 3.0;

    public static Action onAction(ServerPlayerEntity player, PlayerData d, PlayerActionC2SPacket packet) {
        long tick = BastionAC.serverTick();
        if (player.isCreative() || player.isSpectator()) return Action.NONE;
        if (d.isFullyExempt(tick) || BastionAC.isServerLagging(tick)) {
            d.clearMining();
            return Action.NONE;
        }

        PlayerActionC2SPacket.Action a = packet.getAction();
        World world = player.getEntityWorld();

        if (a == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
            BlockPos pos = packet.getPos();
            double reach = eyeDistanceTo(player, pos);
            // Anti "packet mine": a break may only be armed on a block that is
            // actually within reach. Otherwise a cheat sends START from far away
            // to pre-inflate the elapsed time and later STOP up close, dodging
            // FastBreak. An out-of-reach START is ignored, so the timer never
            // starts; well past the range it is also a violation in its own right
            // — a vanilla client raycasts within reach and cannot produce one.
            if (reach > player.getBlockInteractionRange() + 1.0) {
                d.clearMining();
                if (reach > player.getBlockInteractionRange() + REACH_FLAG_MARGIN) {
                    d.packetMineBuffer += 1;
                    if (d.packetMineBuffer >= 2) {
                        d.packetMineBuffer = 0;
                        if (BastionAC.flag(player, d, CheckType.PACKETMINE,
                                String.format("начало добычи с %.1f бл", reach))) {
                            return Action.CANCEL;
                        }
                    }
                }
                return Action.NONE;
            }

            // Nuker runs BEFORE the insta-mine exit: grass, torches and crops are
            // insta-mined, carry no timing signal, and are exactly what a nuker
            // sweeps. Rate and aim are the only things left to measure on them.
            if (nuker(player, d, pos)) return Action.CANCEL;

            double delta = breakingDelta(player, world, pos);
            // Insta-mine: vanilla destroys the block inside this very packet
            // (ServerPlayerInteractionManager -> finishMining "insta mine") and the
            // client, having broken it locally, sends neither STOP nor ABORT. Arming
            // a timer here left a session that could never be closed, and every later
            // attack or hotbar scroll was then read as "mining in the background".
            // That stale session is what auto-banned a legitimate player.
            if (delta >= 1.0 || world.getBlockState(pos).isAir()) {
                // X-ray statistics must see every break, including insta-mined
                // ones: an Efficiency V pickaxe insta-mines stone, and a tracer
                // with a good tool produces a perfectly readable session shape
                // made entirely of insta-broken blocks. The state is still live
                // here (finishMining runs after the START hook), so the
                // hidden-ore geometry is still measurable.
                if (BastionAC.config().check(CheckType.XRAY.key).enabled) {
                    var state = world.getBlockState(pos);
                    XrayTracker.noteBreak(player, d, state, pos,
                            XrayTracker.hasAdjacentAir(world, pos));
                }
                d.clearMining();
                return Action.NONE;
            }

            d.miningPos = pos;
            d.miningStartTick = tick;
            d.miningStartDelta = delta;
            d.miningSlot = player.getInventory().getSelectedSlot();
            return Action.NONE;
        }

        if (a == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK) {
            d.clearMining();
            return Action.NONE;
        }

        if (a == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) {
            BlockPos pos = packet.getPos();
            BlockPos armed = d.miningPos;
            d.clearMining();
            if (armed == null || !armed.equals(pos)) return Action.NONE;

            // The finishing block must be reachable now — a pre-armed START is
            // worthless if the STOP has to happen in range.
            double reach = eyeDistanceTo(player, pos);
            if (reach > player.getBlockInteractionRange() + 1.0) {
                if (reach > player.getBlockInteractionRange() + REACH_FLAG_MARGIN) {
                    d.packetMineBuffer += 1;
                    if (d.packetMineBuffer >= 2) {
                        d.packetMineBuffer = 0;
                        if (BastionAC.flag(player, d, CheckType.PACKETMINE,
                                String.format("завершение добычи с %.1f бл", reach))) {
                            return Action.CANCEL;
                        }
                    }
                }
                return Action.NONE;
            }

            // The block may have been destroyed by someone else meanwhile.
            if (world.getBlockState(pos).isAir()) return Action.NONE;

            // X-ray statistics: judge the break's shape before FastBreak
            // consumes it — the state (and its neighbours) are still live
            // here, which is exactly what the hidden-ore probe needs.
            // Observe-only: feeds EDR probes, never acts on its own.
            if (BastionAC.config().check(CheckType.XRAY.key).enabled) {
                var state = world.getBlockState(pos);
                XrayTracker.noteBreak(player, d, state, pos, XrayTracker.hasAdjacentAir(world, pos));
            }

            double nowDelta = breakingDelta(player, world, pos);
            long elapsed = tick - d.miningStartTick;

            double delta = Math.max(d.miningStartDelta, nowDelta);
            if (delta > 0 && delta < 1.0) {
                double requiredTicks = 1.0 / delta;
                // Tolerance, and why it is where it is. Vanilla accepts a STOP once
                // delta*(elapsed+1) >= 0.7, i.e. it already tolerates ~1.4x — so a
                // check that only fires past 1.25x (the old 0.8 factor minus a whole
                // tick) leaves a cheat the entire 1.1x-1.25x band, which is exactly
                // the 20-25% a FastBreak module grants. 0.85 closes it to ~1.18x.
                // The whole-tick floor stays for short breaks: a two-tick block
                // cannot be judged at all once packet jitter is worth one tick.
                double minAllowed = Math.min(requiredTicks * 0.85, requiredTicks - 1.0);
                if (elapsed < minAllowed) {
                    d.fastBreakBuffer += 1;
                    if (d.fastBreakBuffer >= 2) {
                        d.fastBreakBuffer = 0;
                        if (BastionAC.flag(player, d, CheckType.FASTBREAK,
                                String.format("%d тиков вместо ~%.0f", elapsed, requiredTicks))) {
                            return Action.CANCEL;
                        }
                    }
                } else {
                    d.fastBreakBuffer = Math.max(0, d.fastBreakBuffer - 0.5);
                }
            }
        }
        return Action.NONE;
    }

    /**
     * Nuker: the two things that stay measurable when the block breaks instantly.
     *
     * <p><b>Rate.</b> A vanilla client breaks one block at a time and arms a
     * 5-tick cooldown after each, so holding the button through a field of grass
     * yields about four blocks a second; by hand it is capped by the click rate.
     * A nuker clears everything inside its radius and runs far past both.
     *
     * <p><b>Aim.</b> The client picks its target by raycasting from the crosshair,
     * so the block must be in front of the camera. A nuker breaks whatever is in
     * range including behind the player's back, which no raycast can return. The
     * block CENTRE is compared rather than the hit point (the action packet
     * carries no hit position), so the threshold sits well past 90 degrees —
     * at point-blank the centre alone can be tens of degrees off the crosshair.
     */
    private static boolean nuker(ServerPlayerEntity player, PlayerData d, BlockPos pos) {
        int distinct = d.recordBreakAndCountDistinct(pos.asLong(), System.currentTimeMillis());
        if (distinct > 14) {
            d.nukerBuffer += 1;
            if (d.nukerBuffer >= 2) {
                d.nukerBuffer = 0;
                if (BastionAC.flag(player, d, CheckType.NUKER, distinct + " блоков/сек")) return true;
            }
        } else {
            d.nukerBuffer = Math.max(0, d.nukerBuffer - 0.5);
        }

        Vec3d eye = player.getEyePos();
        Vec3d toBlock = Vec3d.ofCenter(pos).subtract(eye);
        if (toBlock.lengthSquared() > 0.25) {
            double cos = toBlock.normalize().dotProduct(player.getRotationVec(1.0F));
            if (cos < -0.1) {
                d.nukerAimBuffer += 1;
                if (d.nukerAimBuffer >= 3) {
                    d.nukerAimBuffer = 1;
                    if (BastionAC.flag(player, d, CheckType.NUKER,
                            String.format("добыча блока за спиной (cos=%.2f)", cos))) {
                        return true;
                    }
                }
            } else {
                d.nukerAimBuffer = Math.max(0, d.nukerAimBuffer - 0.5);
            }
        }
        return false;
    }

    /**
     * Drops the armed mining session, silently.
     *
     * <p>Called on a hotbar switch and on an attack. Both used to raise a
     * PACKETMINE violation on the theory that a vanilla client cannot do either
     * while a break is running. Both are wrong:
     * <ul>
     *   <li>Nothing in the vanilla client aborts a break on a slot change —
     *       {@code cancelBlockBreaking()} has exactly one call site,
     *       {@code MinecraftClient.handleBlockBreaking}, reached only when the
     *       crosshair leaves the block or the button is released. Scrolling the
     *       hotbar mid-break is ordinary play.</li>
     *   <li>{@code doAttack()} sets {@code attackCooldown = 5}, and
     *       {@code handleBlockBreaking} returns early while that cooldown runs —
     *       so clicking a block and then an entity within five ticks sends the
     *       attack with no ABORT in front of it.</li>
     * </ul>
     * Invalidating the session still costs a cheat the pre-armed timer, which is
     * all this was ever able to prove.
     */
    public static void invalidateMining(PlayerData d) {
        d.clearMining();
    }

    /** Distance from the eye to the centre of the block. */
    private static double eyeDistanceTo(ServerPlayerEntity player, BlockPos pos) {
        return player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
    }

    private static double breakingDelta(ServerPlayerEntity player, World world, BlockPos pos) {
        try {
            return world.getBlockState(pos).calcBlockBreakingDelta(player, world, pos);
        } catch (RuntimeException e) {
            return 0; // unloaded chunk edge — never flag on missing data
        }
    }

    public static Action onInteractBlock(ServerPlayerEntity player, PlayerData d, PlayerInteractBlockC2SPacket packet) {
        long tick = BastionAC.serverTick();
        if (player.isCreative() || player.isSpectator()) return Action.NONE;
        if (d.isFullyExempt(tick) || BastionAC.isServerLagging(tick)) {
            d.actionRhythm.reset();
            return Action.NONE;
        }
        // BOTH hands count. Filtering on MAIN_HAND used to be a complete bypass:
        // the server places blocks from the off hand just as happily, and a cheat
        // client that only ever sends OFF_HAND interactions was never counted at
        // all. Vanilla can emit two packets for one right-click (main hand result
        // not accepted, then off hand), so de-duplicate per tick instead — a
        // vanilla client can never produce two distinct right-clicks in one tick.
        if (tick == d.lastInteractTick) return Action.NONE;
        d.lastInteractTick = tick;

        // AUTOPLACE: where on the face the click landed. A vanilla client sends
        // the point its crosshair ray hit — an arbitrary float. Placement
        // modules compute it: Wurst's ScaffoldWalk, InstantBunker, CrystalAura
        // and AnchorAura, Meteor's BlockUtils.place, all click
        // Vec3d.ofCenter(neighbour) + side*0.5 — the exact centre of the face,
        // both in-plane coordinates 0.5 to the last bit. Every interaction
        // counts, not only blocks: an end crystal is placed the same way.
        if (autoPlace(player, d, packet)) return Action.CANCEL;

        // Only actual block placements are analysed. The rate and geometry rules
        // below describe building; opening a chest, flicking a lever or spamming a
        // door is none of their business and used to feed both of them.
        boolean placing = player.getStackInHand(packet.getHand()).getItem() instanceof BlockItem;
        if (!placing) return Action.NONE;
        if (BastionAC.config().general.shadowTelemetryEnabled) {
            ShadowTelemetry.recordPlacementAttempt();
        }

        Action action = Action.NONE;
        if (airPlace(player, d, packet)) action = Action.max(action, Action.CANCEL);
        if (scaffold(player, d, packet)) action = Action.max(action, Action.CANCEL);

        long now = System.currentTimeMillis();
        int perSecond = d.recordPlaceAndCountLastSecond(now);
        // Vanilla hold-to-place is ~5/s; fast manual clicking reaches ~8-10/s, so
        // 11 keeps legit players clear while catching 2x+ FastPlace scaffolding.
        // Violations are rate-limited: flagging on every packet added VL as fast as
        // the cheat sent packets, which turned a one-second burst into a kick.
        if (perSecond > 11) {
            if (now - d.lastFastPlaceFlagMs >= 250) {
                d.lastFastPlaceFlagMs = now;
                if (BastionAC.flag(player, d, CheckType.FASTPLACE, perSecond + " блоков/сек")) {
                    action = Action.max(action, Action.CANCEL);
                }
            }
            if (perSecond > 16) action = Action.max(action, Action.CANCEL);
        }
        rhythm(player, d, now);
        return action;
    }

    /**
     * AirPlace: the clicked block has nothing to click.
     *
     * <p>A vanilla client picks its target by raycasting from the crosshair,
     * and that raycast can only return a block with an outline shape. Air has
     * none, and neither does a bare fluid — so an interact packet naming one
     * of those is a position the client never actually looked at. That is
     * exactly what AirPlace (and most "place anywhere" reach modules) send.
     *
     * <p>Buffered rather than instant, for one honest reason: a block can be
     * destroyed by somebody else in the milliseconds between the client's
     * raycast and the packet arriving here, and that leaves air behind
     * through no fault of the sender. A single occurrence is latency; a
     * stream of them is a module.
     */
    /**
     * True when the click is a computed face centre: the two in-plane
     * coordinates of the hit point, relative to the clicked block, are exactly
     * 0.5. The packet carries them as floats, so a raycast hitting that point
     * by chance is a one-in-2^48 event per click.
     */
    static boolean isFaceCentre(double fx, double fy, double fz, net.minecraft.util.math.Direction.Axis normal) {
        final double eps = 1.0e-6;
        boolean cx = Math.abs(fx - 0.5) < eps, cy = Math.abs(fy - 0.5) < eps, cz = Math.abs(fz - 0.5) < eps;
        return switch (normal) {
            case X -> cy && cz;
            case Y -> cx && cz;
            case Z -> cx && cy;
        };
    }

    private static boolean autoPlace(ServerPlayerEntity player, PlayerData d, PlayerInteractBlockC2SPacket packet) {
        BlockHitResult hit = packet.getBlockHitResult();
        BlockPos bp = hit.getBlockPos();
        Vec3d at = hit.getPos();
        if (!isFaceCentre(at.x - bp.getX(), at.y - bp.getY(), at.z - bp.getZ(), hit.getSide().getAxis())) {
            d.placeCenterStreak = 0;
            return false;
        }
        d.placeCenterStreak++;
        if (d.placeCenterStreak < 4) return false;
        int streak = d.placeCenterStreak;
        d.placeCenterStreak = 2;
        return BastionAC.flag(player, d, CheckType.AUTOPLACE,
                streak + " кликов подряд точно в центр грани блока (координаты вычислены, а не наведены)");
    }

    private static boolean airPlace(ServerPlayerEntity player, PlayerData d, PlayerInteractBlockC2SPacket packet) {
        if (!BastionAC.config().check(CheckType.AIRPLACE.key).enabled) return false;
        BlockHitResult hit = packet.getBlockHitResult();
        if (hit == null) return false;
        World world = player.getEntityWorld();
        BlockPos pos = hit.getBlockPos();
        net.minecraft.block.BlockState state;
        try {
            state = world.getBlockState(pos);
        } catch (RuntimeException e) {
            return false; // unloaded chunk — never convict on missing data
        }
        boolean nothingThere;
        try {
            nothingThere = state.isAir()
                    || (!state.getFluidState().isEmpty() && state.getOutlineShape(world, pos).isEmpty());
        } catch (RuntimeException e) {
            return false;
        }
        if (!nothingThere) {
            d.airPlaceBuffer = Math.max(0, d.airPlaceBuffer - 0.5);
            return false;
        }
        d.airPlaceBuffer += 1;
        if (d.airPlaceBuffer >= 3) {
            d.airPlaceBuffer = 1;
            return BastionAC.flag(player, d, CheckType.AIRPLACE, String.format(
                    "клик по пустоте на %d %d %d (%s)",
                    pos.getX(), pos.getY(), pos.getZ(),
                    state.isAir() ? "воздух" : "жидкость без формы"));
        }
        return false;
    }

    /**
     * Scaffold geometry. Two independent rules, because the obvious one cannot
     * be trusted on its own:
     *
     * <p><b>(1) Eye side of the face — hard.</b> The click ray enters the block
     * through the face it reports, so the eye must lie on the OUTER side of that
     * face's surface plane. This is measured against the hit POSITION from the
     * packet (not the block's full-cube boundary, which would false-flag every
     * slab, stair and snow layer) and needs no rotation at all, so it cannot be
     * knocked over by packet ordering.
     *
     * <p><b>(2) Look vector vs face normal — soft.</b> Geometrically a legit
     * placement has {@code look · normal < 0}, but
     * {@code PlayerInteractBlockC2SPacket} carries no rotation in 1.21.11 and the
     * interact packet is sent BEFORE the move packet of the same client tick, so
     * the server's yaw/pitch here are one tick old. A single mouse flick can flip
     * the sign on a near-tangent placement, which is why this one only counts
     * through a streak: a hand produces one such placement, a Scaffold module
     * produces them continuously.
     */
    private static boolean scaffold(ServerPlayerEntity player, PlayerData d, PlayerInteractBlockC2SPacket packet) {
        BlockHitResult hit = packet.getBlockHitResult();
        if (hit == null || hit.getSide() == null || hit.isInsideBlock()) return false;
        var side = hit.getSide();
        double nx = side.getOffsetX();
        double ny = side.getOffsetY();
        double nz = side.getOffsetZ();

        Vec3d eye = player.getEyePos();
        Vec3d hitPos = hit.getPos();
        Vec3d look = player.getRotationVec(1.0F);
        // Signed distance of the eye from the clicked surface, along its normal.
        double outside = (eye.x - hitPos.x) * nx + (eye.y - hitPos.y) * ny + (eye.z - hitPos.z) * nz;
        double dot = look.x * nx + look.y * ny + look.z * nz;
        Vec3d toHit = hitPos.subtract(eye);
        double dist = toHit.length();
        double aim = dist > 1.0e-4 ? toHit.multiply(1.0 / dist).dotProduct(look) : 1.0;

        if (BastionAC.config().general.debugChecks) {
            BastionAC.LOGGER.info("[DEBUG place] {} side={} outside={} dot={} aim={} dist={} hit={} eye={} look={}",
                    d.name, side.name(), String.format("%.3f", outside), String.format("%.3f", dot),
                    String.format("%.3f", aim), String.format("%.2f", dist), hitPos, eye, look);
        }

        // The eye position is itself one tick old, so allow a full tick of travel
        // (a jump start is 0.42 blocks) before calling the geometry impossible.
        if (outside < -0.5) {
            if (BastionAC.flag(player, d, CheckType.SCAFFOLD,
                    String.format("клик по грани %s изнутри (%.2f бл)", side.name(), outside))) {
                return true;
            }
            return false;
        }

        // AIM. The client picks the block face by raycasting from the crosshair, so
        // the hit position always lies ON the look ray. This is what a flat bridge
        // gives away: the module clicks the face of the block beside the player's
        // feet while the player walks forward looking level, putting the hit point
        // 70-120 degrees off the camera. The face-normal rule below cannot see that
        // (a horizontal look against a horizontal face normal reads as legitimate),
        // which is why holding W produced a hundred blocks of bridge in silence.
        //
        // The floor is generous on purpose. Both the eye position and the rotation
        // the server holds are one tick old, and at point-blank a tick of travel is
        // worth tens of degrees, so this only convicts a placement pointing away
        // from the camera, and only as a streak.
        if (dist > 0.6 && aim < 0.4) {
            d.scaffoldAimBuffer += 1;
            if (d.scaffoldAimBuffer >= 3) {
                d.scaffoldAimBuffer = 1;
                if (BastionAC.flag(player, d, CheckType.SCAFFOLD, String.format(
                        "постановка мимо взгляда (%.0f° от прицела, %.1f бл)",
                        Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, aim)))), dist))) {
                    return true;
                }
            }
        } else {
            d.scaffoldAimBuffer = Math.max(0, d.scaffoldAimBuffer - 0.5);
        }

        if (dot > 0.05) {
            d.scaffoldBuffer += 1;
            if (d.scaffoldBuffer >= 4) {
                d.scaffoldBuffer = 2;
                return BastionAC.flag(player, d, CheckType.SCAFFOLD,
                        String.format("взгляд изнутри грани %s (dot=%.2f) x4", side.name(), dot));
            }
        } else {
            d.scaffoldBuffer = Math.max(0, d.scaffoldBuffer - 1);
        }
        return false;
    }

    /**
     * Vanilla's hold-to-use auto-repeat ({@code itemUseCooldown = 4} client
     * ticks). A player simply holding right-click to bridge or build a wall
     * places a block every 200 ms with machine precision, so this one cadence
     * must be waved through — it is the only perfectly even interaction series
     * a hand can produce. The window is wide because the gaps are measured on
     * ARRIVAL inside a server tick, so server-side jitter shifts the mean.
     */
    private static final double VANILLA_HOLD_MS = 200.0;
    /**
     * Tight on purpose. Vanilla's hold rate is a fixed four client ticks and does
     * NOT vary with what the player is doing, so a legitimate holder always sits
     * at 200 ms. A Scaffold, by contrast, is paced by movement — one block per
     * block travelled, about 178 ms at sprint — and a 45 ms window swallowed
     * exactly that, which is why a hundred-block bridge produced no rhythm flag.
     */
    private static final double VANILLA_HOLD_TOLERANCE_MS = 20.0;

    /**
     * AutoAction: the same rhythm heuristic the combat side uses, pointed at
     * world interaction. Catches what a rate check cannot — build macros and
     * AFK farm bots that stay under the per-second ceiling but hit their button
     * on a metronome.
     *
     * <p>Block BREAKING is deliberately not analysed: mining time is a fixed
     * function of block and tool, so holding the button produces a perfectly
     * even series for entirely legitimate reasons and an auto-miner looks
     * exactly the same. There is no signal there to read.
     */
    private static void rhythm(ServerPlayerEntity player, PlayerData d, long now) {
        RhythmTracker.Verdict verdict = d.actionRhythm.push(now);
        if (verdict == null) return;
        if (Math.abs(verdict.mean - VANILLA_HOLD_MS) <= VANILLA_HOLD_TOLERANCE_MS) {
            d.autoActionBuffer = Math.max(0, d.autoActionBuffer - 1);
            return;
        }
        if (verdict.suspicious()) {
            d.autoActionBuffer += verdict.signals() >= 2 ? 2 : 1;
            if (d.autoActionBuffer >= 3) {
                d.autoActionBuffer = 0;
                BastionAC.flag(player, d, CheckType.AUTOACTION, verdict.describe());
            }
        } else {
            d.autoActionBuffer = Math.max(0, d.autoActionBuffer - 1);
        }
    }

    private WorldChecks() {}
}
