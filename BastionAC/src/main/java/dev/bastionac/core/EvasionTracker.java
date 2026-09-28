package dev.bastionac.core;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EVASION producer: the "soft toggle" patterns a chain needs beyond the
 * relog-during-punishment hook.
 *
 * <p>Two measured patterns, both classic cheat-client behaviour and both
 * invisible to single-event checks (which is exactly why they belong in the
 * correlation layer, not in a per-packet check):
 *
 * <ol>
 *   <li><b>Staff-join collapse.</b> While the player is in combat-ish
 *       activity (a healthy attack/click rate), an admin joins or comes
 *       within range — and within the following 30 s the click rate or the
 *       aim spread collapses by half or more. A human does not get calmer
 *       because a moderator appeared; a cheat with a "panic on staff" module
 *       does. Requires a real before/after drop, so quiet players never
 *       trip it.</li>
 *   <li><b>Post-alert freeze.</b> Right after a staff alert or a setback
 *       lands, the player who was sprint-attacking stops attacking entirely
 *       for 10–15 s — the panic pause of a module that toggled off. Judged
 *       only for players whose activity was high before the alert.</li>
 * </ol>
 *
 * <p>Both feed a single EVASION event into the chain; the chain score
 * decides the consequence. Each pattern can fire at most once per episode
 * per player (the cooldown map), so a nervous legit player produces at most
 * one event, which alone never reaches any threshold.
 */
public final class EvasionTracker {

    /** Window after a staff appearance in which the collapse is judged (ms). */
    private static final long STAFF_WINDOW_MS = 30_000;
    /** Window after an alert in which the freeze is judged (ms). */
    private static final long ALERT_WINDOW_MS = 15_000;
    /** Activity (attacks/s) that counts as "busy" before the trigger. */
    private static final double BUSY_CPS = 5.0;
    /** Collapse factor: after must be at most this fraction of before. */
    private static final double COLLAPSE_FACTOR = 0.5;
    /** A player must be within this range of the admin for the proximity variant. */
    private static final double STAFF_RANGE = 64.0;
    /** Cooldown per pattern so one episode yields at most one event each. */
    private static final long PATTERN_COOLDOWN_MS = 5 * 60_000L;

    private static final class State {
        long staffSeenAt;
        double cpsBeforeStaff;
        boolean staffArmed;
        long alertAt;
        double cpsBeforeAlert;
        boolean alertArmed;
        long lastStaffToggleAt;
        long lastPanicAt;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private EvasionTracker() {}

    // ------------------------------------------------------------------ hooks

    /** Called when an admin joins (server thread). Arms the collapse watch for everyone in combat-ish activity. */
    public static void onStaffJoin(ServerPlayerEntity staff) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, State> e : STATES.entrySet()) {
            State st = e.getValue();
            if (now - st.staffSeenAt < STAFF_WINDOW_MS) continue; // already armed
            double cps = AttackRate.currentCps(e.getKey());
            if (cps >= BUSY_CPS) {
                st.cpsBeforeStaff = cps;
                st.staffArmed = true;
            }
            st.staffSeenAt = now;
        }
    }

    /** Called when an admin comes within range of the player (tick hook, cheap). */
    public static void onStaffNearby(ServerPlayerEntity player, ServerPlayerEntity staff) {
        State st = STATES.get(player.getUuid());
        if (st == null) return;
        long now = System.currentTimeMillis();
        if (now - st.staffSeenAt < STAFF_WINDOW_MS) return;
        if (player.getEntityPos().distanceTo(staff.getEntityPos()) > STAFF_RANGE) return;
        double cps = AttackRate.currentCps(player.getUuid());
        if (cps >= BUSY_CPS) {
            st.cpsBeforeStaff = cps;
            st.staffArmed = true;
        }
        st.staffSeenAt = now;
    }

    /** Called when a staff alert or setback lands on the player. */
    public static void onAlert(ServerPlayerEntity player) {
        State st = STATES.get(player.getUuid());
        if (st == null) return;
        long now = System.currentTimeMillis();
        double cps = AttackRate.currentCps(player.getUuid());
        if (cps >= BUSY_CPS) {
            st.cpsBeforeAlert = cps;
            st.alertArmed = true;
        }
        st.alertAt = now;
    }

    /** Called every second per player (tick hook): evaluates the armed patterns. */
    public static void evaluate(ServerPlayerEntity player) {
        State st = STATES.get(player.getUuid());
        if (st == null) return;
        long now = System.currentTimeMillis();
        double cps = AttackRate.currentCps(player.getUuid());
        ChainPlayerAdapter adapter = new ChainPlayerAdapter(player);

        if (st.staffArmed && now - st.staffSeenAt <= STAFF_WINDOW_MS
                && st.cpsBeforeStaff >= BUSY_CPS && cps <= st.cpsBeforeStaff * COLLAPSE_FACTOR) {
            st.staffArmed = false;
            if (now - st.lastStaffToggleAt > PATTERN_COOLDOWN_MS) {
                st.lastStaffToggleAt = now;
                AttackChainDetector.evasion(adapter, "staff-toggle",
                        String.format(java.util.Locale.ROOT,
                                "клик %.1f→%.1f/сек после появления персонала",
                                st.cpsBeforeStaff, cps));
            }
        }

        if (st.alertArmed && now - st.alertAt >= 3_000 && now - st.alertAt <= ALERT_WINDOW_MS
                && st.cpsBeforeAlert >= BUSY_CPS && cps <= st.cpsBeforeAlert * COLLAPSE_FACTOR * 0.5) {
            st.alertArmed = false;
            if (now - st.lastPanicAt > PATTERN_COOLDOWN_MS) {
                st.lastPanicAt = now;
                AttackChainDetector.evasion(adapter, "panic-pause",
                        String.format(java.util.Locale.ROOT,
                                "клик %.1f→%.1f/сек сразу после алерта",
                                st.cpsBeforeAlert, cps));
            }
        }
    }

    public static void attach(ServerPlayerEntity player) {
        STATES.putIfAbsent(player.getUuid(), new State());
    }

    public static void forget(UUID uuid) {
        STATES.remove(uuid);
    }
}
