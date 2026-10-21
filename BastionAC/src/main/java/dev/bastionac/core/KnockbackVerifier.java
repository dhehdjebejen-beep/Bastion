package dev.bastionac.core;

import dev.bastionac.BastionAC;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/**
 * Anti-KB (VELOCITY), 2.28: a transaction pinned to the moment the client
 * actually received the knockback.
 *
 * <p>The old check anchored the server position when the velocity packet was
 * SENT and judged it a fixed four SERVER ticks later. Neither end is the
 * client's: the packet arrives half a ping later, the reply another half ping
 * after that, so on 100 ms only two of the four judged ticks contained any
 * knockback at all; and a hit that caught the player at the end of a jump
 * landed them inside the window, where ground friction (0.546, not the 0.98
 * the model assumed) eats most of the travel. That is what the prod log was
 * full of — "отброс 0,92 вместо мин. 0,93", "1,60 вместо 1,62", honest
 * fighters three in a row.
 *
 * <p>Now, right behind the velocity packet the server sends a ping. The
 * vanilla client handles both on its main thread, in order, so its pong goes
 * out the moment the knockback is in its velocity, before the next movement.
 * From the pong on, the next {@code N} movement packets are exactly the ticks
 * the knockback played out in. Each one is replayed with vanilla physics —
 * air friction 0.91, ground friction 0.6·0.91 (both taken, as vanilla does, from
 * the ground state before the move: the client's claim, backed by the server's
 * own collision scan) — against the hardest
 * an honest player can fight it: full movement input straight into the hit
 * every tick, and a sprint-jump "jump reset" whenever they leave the ground.
 * Only travel below even that worst case is a suppressed knockback.
 */
public final class KnockbackVerifier {

    // Ping ids are random (3.5 beta): the old fixed 0xBAC00000 prefix told a
    // cheat which pings were the knockback check's, so it could drop exactly
    // those and keep every other ping answered.

    /** Below this the replay is too short to tell anything from noise. */
    static final double MIN_JUDGED = 0.35;
    /** Hit packets carry at least the base knockback 0.4; smaller nudges are not judged. */
    static final double MIN_HORIZONTAL = 0.3;
    /** Air movement input: 0.02, x1.3 sprinting, plus a margin. */
    static final double AIR_ACCEL = 0.03;
    /** A sprint-jump adds 0.2 along the facing — legitimately straight into the hit. */
    static final double JUMP_BOOST = 0.2;

    private KnockbackVerifier() {}

    /** The velocity packet went out (server thread): remember it, send the ping right after it. */
    public static void onVelocitySent(ServerPlayerEntity player, PlayerData d, double vx, double vy, double vz) {
        double horiz = Math.sqrt(vx * vx + vz * vz);
        if (horiz < MIN_HORIZONTAL || player.isCreative() || player.isSpectator()
                || player.hasVehicle() || player.isGliding()) {
            return;
        }
        d.kbNeedPing = true;
        d.kbNextSpeed = horiz;
        d.kbNextDirX = vx / horiz;
        d.kbNextDirZ = vz / horiz;
        d.kbNextVy = vy;
    }

    /** After the velocity packet itself is queued (server thread): the ping goes right behind it. */
    public static void flushPing(ServerPlayerEntity player, PlayerData d) {
        if (!d.kbNeedPing) return;
        d.kbNeedPing = false;
        // The previous knockback's ping still unanswered after more movement
        // than its flight time: that is a held transaction, whether or not the
        // three seconds of the move-side timeout have run. A new hit used to
        // wipe it without a word, so a client that never answered was never
        // judged as long as it kept getting hit at least every three seconds —
        // all of real PvP (3.5 beta, audit).
        if (d.kbAwaitPong && d.kbMovesSincePing > inFlight(player)) noteUnanswered(player, d);
        int id = java.util.concurrent.ThreadLocalRandom.current().nextInt();
        d.clearKbVerify();
        d.kbPending = true;
        d.kbAwaitPong = true;
        d.kbPingId = id;
        d.kbPingMs = System.currentTimeMillis();
        d.kbMovesSincePing = 0;
        d.kbSpeed = d.kbNextSpeed;
        d.kbDirX = d.kbNextDirX;
        d.kbDirZ = d.kbNextDirZ;
        d.kbVy = d.kbNextVy;
        player.networkHandler.sendPacket(new CommonPingS2CPacket(id));
    }

    /** Movement packets a vanilla client can still send before it has even seen our ping. */
    private static int inFlight(ServerPlayerEntity player) {
        return player.networkHandler.getLatency() / 50 + 20;
    }

    /** One knockback ping went unanswered with movement flowing on; five in a row is the flag. */
    private static void noteUnanswered(ServerPlayerEntity player, PlayerData d) {
        d.kbUnanswered++;
        if (d.kbUnanswered >= 5) {
            d.kbUnanswered = 0;
            BastionAC.flag(player, d, CheckType.VELOCITY,
                    "клиент не отвечает на ping после отброса (5 раз подряд)");
        }
    }

    /** The client answered (server thread, in packet order): the knockback is in its velocity now. */
    public static void onPong(ServerPlayerEntity player, PlayerData d, int id) {
        if (!d.kbAwaitPong || id != d.kbPingId) return;
        d.kbAwaitPong = false;
        d.kbUnanswered = 0;
        d.kbActive = true;
        d.kbAnchorX = player.getX();
        d.kbAnchorZ = player.getZ();
        d.kbLastY = player.getY();
        // The ground state the last movement packet claimed and the server's scan backed;
        // the entity flag can lag behind when a packet was held during a teleport.
        d.kbGroundPrev = d.lastGroundBacked;
        // A knockback that lifts (vy > 0.1) takes a grounded player off the
        // ground for several ticks unless a ceiling is in the way. While it
        // must still be in the air, a "grounded" claim does not get ground
        // friction in the replay — or a client that simply stays put on the
        // floor would talk the expected travel down to nothing.
        d.kbLift = d.kbGroundPrev && d.kbVy > 0.1 && headroom(player);
        d.kbSimH = 0;
        d.kbSimVy = d.kbVy;
        d.kbStartY = player.getY();
        d.kbU = d.kbSpeed;
        d.kbMinDisp = 0;
        d.kbCount = 0;
        d.kbLastX = player.getX();
        d.kbLastZ = player.getZ();
        debug(d, "pong id=%d anchor=%.3f,%.3f ground=%s speed=%.3f vy=%.3f",
                id, d.kbAnchorX, d.kbAnchorZ, d.kbGroundPrev, d.kbSpeed, d.kbVy);
    }

    private static void debug(PlayerData d, String fmt, Object... args) {
        if (BastionAC.config().general.debugChecks) {
            BastionAC.LOGGER.info("[DEBUG velocity] {} {}", d.name, String.format(java.util.Locale.ROOT, fmt, args));
        }
    }

    /**
     * One movement packet (server thread). {@code grounded} must be the client's
     * claim AND the server's own support scan, so a client cannot shorten the
     * replay by claiming ground in the air.
     */
    public static void onMove(ServerPlayerEntity player, PlayerData d, double x, double y, double z,
                              boolean grounded) {
        if (!d.kbPending) return;
        if (d.kbAwaitPong) {
            d.kbMovesSincePing++;
            if (d.kbMovesSincePing > inFlight(player) && System.currentTimeMillis() - d.kbPingMs > 3000) {
                // A vanilla client always answers before its next movement; a
                // packet stall stops the movement too. Movement flowing on with
                // our ping unanswered is a client holding the transaction back.
                d.clearKbVerify();
                noteUnanswered(player, d);
            }
            return;
        }
        if (!d.kbActive) return;
        World world = player.getEntityWorld();
        if (slowingGround(world, x, y, z)) d.kbBlocked = true;

        double dy = y - d.kbLastY;
        boolean firstTick = d.kbCount == 0;
        double accel = d.kbGroundPrev ? groundAccel(player) : AIR_ACCEL;
        boolean jumped = d.kbGroundPrev && (firstTick ? dy > d.kbVy + 0.03 : dy > 0.3);
        double u = (firstTick ? d.kbSpeed : d.kbU) - accel - (jumped ? JUMP_BOOST : 0.0);
        d.kbMinDisp += u;
        // Vanilla takes friction, like the input, from the ground state BEFORE the move.
        d.kbU = u * (d.kbGroundPrev ? 0.546 : 0.91);
        if (firstTick && jumped) d.kbSimVy = 0.42;
        d.kbSimH += d.kbSimVy;
        d.kbSimVy = (d.kbSimVy - 0.08) * 0.98;
        boolean mustBeAirborne = d.kbLift && d.kbSimH > (y - d.kbStartY) + 0.05;
        d.kbGroundPrev = grounded && !mustBeAirborne;
        d.kbLastY = y;
        d.kbLastX = x;
        d.kbLastZ = z;
        d.kbCount++;
        debug(d, "tick %d dy=%.3f grounded=%s jumped=%s u=%.3f min=%.3f pos=%.3f,%.3f",
                d.kbCount, dy, grounded, jumped, u, d.kbMinDisp, x, z);
        if (d.kbCount < BastionAC.config().general.velocityVerifyTicks) return;

        // Verdict.
        double moved = (x - d.kbAnchorX) * d.kbDirX + (z - d.kbAnchorZ) * d.kbDirZ;
        double need = d.kbMinDisp * 0.9 - 0.03;
        boolean judged = d.kbMinDisp >= MIN_JUDGED && !d.kbBlocked && !wallInPath(player, d);
        d.kbPending = false;
        d.kbActive = false;
        debug(d, "verdict moved=%.3f need=%.3f judged=%s blocked=%s", moved, need, judged, d.kbBlocked);
        if (!judged) return;
        if (moved < need) {
            d.kbFailStreak++;
            int streak = BastionAC.config().general.velocityConfirmStreak;
            if (d.kbFailStreak >= streak) {
                d.kbFailStreak = 0;
                BastionAC.flag(player, d, CheckType.VELOCITY, String.format(
                        "отброс %.2f бл вместо мин. %.2f (%d подряд)", moved, need, streak));
            }
        } else {
            d.kbFailStreak = Math.max(0, d.kbFailStreak - 1);
        }
    }

    /**
     * The vanilla worst case, as a pure function for tests: how far an honest
     * client must still travel along the knockback over these ticks.
     */
    static double minDisplacement(double speed, double vy, boolean groundBefore, boolean[] grounded,
                                  double[] dys, double groundAccel, boolean headroom) {
        double u = speed, disp = 0, simH = 0, simVy = vy, h = 0;
        boolean prev = groundBefore;
        boolean lift = groundBefore && vy > 0.1 && headroom;
        for (int k = 0; k < grounded.length; k++) {
            double accel = prev ? groundAccel : AIR_ACCEL;
            boolean jumped = prev && (k == 0 ? dys[k] > vy + 0.03 : dys[k] > 0.3);
            u = u - accel - (jumped ? JUMP_BOOST : 0.0);
            disp += u;
            u *= prev ? 0.546 : 0.91;
            if (k == 0 && jumped) simVy = 0.42;
            simH += simVy;
            simVy = (simVy - 0.08) * 0.98;
            h += dys[k];
            prev = grounded[k] && !(lift && simH > h + 0.05);
        }
        return disp;
    }

    /** Room above the head for the knockback's rise (no ceiling within a block). */
    private static boolean headroom(ServerPlayerEntity player) {
        try {
            return player.getEntityWorld().isSpaceEmpty(player, player.getBoundingBox().offset(0.0, 1.0, 0.0));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Ground movement input: the speed attribute (sprint and Speed included), never under sprint. */
    private static double groundAccel(ServerPlayerEntity player) {
        return Math.max(0.13, player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED)) * 1.1;
    }

    /** Soul sand, honey, berry bushes and the like eat the knockback the model assumes. */
    private static boolean slowingGround(World world, double x, double y, double z) {
        BlockState below = world.getBlockState(BlockPos.ofFloored(x, y - 0.5, z));
        BlockState feet = world.getBlockState(BlockPos.ofFloored(x, y + 0.1, z));
        return below.getBlock().getVelocityMultiplier() < 1.0f || feet.getBlock().getVelocityMultiplier() < 1.0f
                || feet.isOf(Blocks.SWEET_BERRY_BUSH) || feet.isOf(Blocks.COBWEB) || feet.isOf(Blocks.POWDER_SNOW);
    }

    /**
     * True when something solid sits on the path from where the knockback
     * started: the player's box is swept along the push in quarter-block
     * steps, at foot height and at the height the knockback lifts them to.
     */
    private static boolean wallInPath(ServerPlayerEntity player, PlayerData d) {
        try {
            Box box = player.getBoundingBox();
            Box start = box.offset(d.kbAnchorX - player.getX(), 0.0, d.kbAnchorZ - player.getZ());
            double dist = Math.max(0.5, Math.min(2.0, d.kbMinDisp));
            for (double s = 0.25; s <= dist + 1e-9; s += 0.25) {
                Box b = start.offset(d.kbDirX * s, 0.0, d.kbDirZ * s);
                if (!player.getEntityWorld().isSpaceEmpty(player, b)
                        || !player.getEntityWorld().isSpaceEmpty(player, b.offset(0.0, 0.42, 0.0))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true; // unloaded chunk edge — never convict on missing data
        }
    }
}
