package dev.bastionac.core;

import dev.bastionac.BastionAC;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RECON producer: sustained aim tracking of a target the player cannot see.
 *
 * <p>An ESP/radar module or a "legit-looking" killaura in scout mode keeps
 * the crosshair glued to a player through solid walls — rotating to follow
 * them long before any attack happens, so no combat check ever fires. That
 * behaviour is invisible to single-event checks by construction, but it is
 * a strong, low-noise signal when measured as a <em>streak</em>:
 *
 * <ul>
 *   <li>the look ray hits the target's (history-lagged) hitbox — the aim
 *       really is on them, dot ≥ 0.9 of the closest-point direction;</li>
 *   <li>the eye→hitbox ray is blocked by a solid block (they are behind a
 *       wall — using the same double-raycast discipline as the Walls
 *       combat check);</li>
 *   <li>the target is another player (mob ESP is far cheaper to fake and
 *       far less useful to punish).</li>
 * </ul>
 *
 * <p>A legitimate player can hold all three for a moment while chasing
 * someone around a corner, so the verdict needs a sustained streak of
 * {@value #STREAK_TICKS} consecutive qualifying move packets (~2–3 s of
 * glued aim) and then feeds a single RECON event into the chain — the chain
 * scoring decides what it means, this only measures the geometry.
 *
 * <p>Cost discipline: the blocked-ray probe is the expensive part (two
 * raycasts per sample), so it only runs once every {@value #SAMPLE_EVERY}
 * packets and the streak counter carries the rest. Frozen (pre-login)
 * players are exempt, and the whole tracker resets on world change.
 */
public final class ReconTracker {

    /** Consecutive qualifying samples before a RECON event is fed. */
    static final int STREAK_TICKS = 50;
    /** Sample the expensive raycast every N move packets (~1 s at 20 tps). */
    private static final int SAMPLE_EVERY = 4;
    /** Aim must be within this cosine of the closest point of the hitbox. */
    private static final double AIM_DOT = 0.9;
    /** Skip targets closer than this (point-blank behind a thin wall is a fight, not ESP). */
    private static final double MIN_DIST = 2.0;

    private static final class State {
        int sinceSample;
        int streak;
        UUID trackedUuid;
        String trackedName;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private ReconTracker() {}

    /**
     * Feeds one rotation sample from the move-packet hook (server thread).
     * The cheap part (find a player target near the look ray) runs on every
     * call; the raycast confirmation only on every {@link #SAMPLE_EVERY}-th.
     */
    public static void onRotation(ServerPlayerEntity player, float yaw, float pitch) {
        State st = STATES.get(player.getUuid());
        if (st == null) return;
        st.sinceSample++;
        if (st.sinceSample < SAMPLE_EVERY) return;
        st.sinceSample = 0;

        if (player.isCreative() || player.isSpectator() || BastionAC.isAuthFrozen(player)) {
            st.streak = 0;
            return;
        }

        Vec3d eye = player.getEyePos();
        Vec3d look = Vec3d.fromPolar(pitch, yaw);

        // Find the closest other player the look ray plausibly covers.
        ServerPlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayerEntity other : player.getEntityWorld().getServer().getPlayerManager().getPlayerList()) {
            if (other == player || other.isInvisible()) continue;
            double dist = eye.distanceTo(other.getBoundingBox().getCenter());
            if (dist < MIN_DIST || dist > 40) continue;
            Box eb = other.getBoundingBox().expand(0.6); // generous cone at distance
            if (MathUtil.lookDotToBox(eye.x, eye.y, eye.z, look.x, look.y, look.z,
                    eb.minX, eb.minY, eb.minZ, eb.maxX, eb.maxY, eb.maxZ) >= 0.5
                    || eye.distanceTo(closestPoint(eye, eb)) < MIN_DIST + 0.6) {
                if (dist < bestDist) {
                    bestDist = dist;
                    best = other;
                }
            }
        }
        if (best == null) {
            decay(st);
            return;
        }

        // Precise test against the lag-compensated hitbox: the aim must be
        // genuinely on the target (dot to the closest point ≥ AIM_DOT) while
        // a solid block fully occludes the same line of sight.
        int pingTicks = best.networkHandler == null ? 2 : Math.min(12, best.networkHandler.getLatency() / 50 + 2);
        List<Box> history = HitboxHistory.recent(best.getId(), pingTicks);
        boolean onAim = false;
        boolean blocked = false;
        Vec3d eye2 = eye;
        for (Box box : history.isEmpty() ? List.of(best.getBoundingBox()) : history) {
            Box eb = box.expand(0.1);
            if (MathUtil.lookDotToBox(eye.x, eye.y, eye.z, look.x, look.y, look.z,
                    eb.minX, eb.minY, eb.minZ, eb.maxX, eb.maxY, eb.maxZ) >= AIM_DOT) {
                onAim = true;
                Vec3d closest = closestPoint(eye2, eb);
                if (rayBlocked(player, eye2, closest) && rayBlocked(player, eye2, box.getCenter())) {
                    blocked = true;
                }
                break;
            }
        }

        if (onAim && blocked) {
            if (st.trackedUuid == null || !st.trackedUuid.equals(best.getUuid())) {
                st.trackedUuid = best.getUuid();
                st.trackedName = best.getGameProfile().name();
                st.streak = 0; // following a NEW hidden player restarts the clock
            }
            st.streak++;
            if (st.streak >= STREAK_TICKS) {
                st.streak = 0;
                AttackChainDetector.recon(new ChainPlayerAdapter(player), "esp-tracking",
                        String.format(java.util.Locale.ROOT,
                                "ведение %s сквозь стену %.1f бл, dot≥%.2f, %d подряд",
                                st.trackedName, bestDist, AIM_DOT, STREAK_TICKS));
            }
        } else {
            decay(st);
        }
    }

    private static void decay(State st) {
        st.streak = Math.max(0, st.streak - 2);
        st.trackedUuid = null;
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

    public static void attach(ServerPlayerEntity player) {
        STATES.putIfAbsent(player.getUuid(), new State());
    }

    public static void forget(UUID uuid) {
        STATES.remove(uuid);
    }

    /** Exposed for tests. */
    static int streakOf(UUID uuid) {
        State st = STATES.get(uuid);
        return st == null ? -1 : st.streak;
    }
}
