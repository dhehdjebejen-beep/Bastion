package dev.bastionac.core;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-thread-only observability for anti-cheat tuning.
 *
 * <p>This class deliberately does not flag players, change violation levels,
 * cancel packets, teleport, kick or ban. It measures packet pressure, expensive
 * environment scans, server-confirmed block placements and contextual evidence
 * for existing checks. Future budget enforcement is opt-in and defaults off.
 */
public final class ShadowTelemetry {
    private static final Map<UUID, Integer> MOVE_PACKETS = new LinkedHashMap<>();

    private static long currentTick;
    private static long lastTickNanos;
    private static int totalMovePackets;
    private static int maxPlayerMovePackets;
    private static int environmentScans;
    private static int skippedEnvironmentScans;
    private static long environmentScanNanos;
    private static int placementAttempts;
    private static int placementAccepted;
    private static int placementRejected;
    private static int bigMoveCandidates;
    private static int authBridgeFailures;

    /** Starts a fresh server-tick sample. Must be called exactly once per tick. */
    public static void beginTick(long tick, long durationNanos) {
        currentTick = tick;
        lastTickNanos = Math.max(0L, durationNanos);
        totalMovePackets = 0;
        maxPlayerMovePackets = 0;
        environmentScans = 0;
        skippedEnvironmentScans = 0;
        environmentScanNanos = 0;
        MOVE_PACKETS.clear();
    }

    /** Records a received C2S movement packet. No judgement is made. */
    public static int recordMovePacket(UUID playerId) {
        totalMovePackets++;
        int playerPackets = MOVE_PACKETS.merge(playerId, 1, Integer::sum);
        if (playerPackets > maxPlayerMovePackets) maxPlayerMovePackets = playerPackets;
        return playerPackets;
    }

    /**
     * Reserves one expensive collision/environment probe. A disabled budget always
     * returns true, so enabling telemetry alone cannot alter movement behaviour.
     */
    public static boolean reserveEnvironmentScan(int budgetPerTick, boolean enforceBudget) {
        int safeBudget = Math.max(1, budgetPerTick);
        if (environmentScans >= safeBudget) {
            skippedEnvironmentScans++;
            return !enforceBudget;
        }
        environmentScans++;
        return true;
    }

    /** Accounts measured time spent inside an environment scan. */
    public static void recordEnvironmentScanNanos(long nanos) {
        environmentScanNanos += Math.max(0L, nanos);
    }

    /** Records a C2S interaction attempt with a BlockItem. */
    public static void recordPlacementAttempt() {
        placementAttempts++;
    }

    /** Records the authoritative outcome returned by BlockItem.place. */
    public static void recordPlacementOutcome(boolean accepted) {
        if (accepted) placementAccepted++;
        else placementRejected++;
    }

    /** Records an already-existing BigMove candidate without changing its verdict. */
    public static void recordBigMoveCandidate() {
        bigMoveCandidates++;
    }

    /** Records a degraded BastionAuth reflective bridge state. */
    public static void recordAuthBridgeFailure() {
        authBridgeFailures++;
    }

    /** Snapshot suitable for an existing alert record. The map is self-contained. */
    public static Map<String, String> evidence(ServerPlayerEntity player, PlayerData data) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("tick", Long.toString(currentTick));
        out.put("tickMs", formatMillis(lastTickNanos));
        out.put("pingMs", Integer.toString(player.networkHandler == null ? -1 : player.networkHandler.getLatency()));
        out.put("world", player.getEntityWorld().getRegistryKey().getValue().toString());
        out.put("serverPos", formatPos(player.getX(), player.getY(), player.getZ()));
        out.put("creative", Boolean.toString(player.isCreative()));
        out.put("spectator", Boolean.toString(player.isSpectator()));
        out.put("vehicle", Boolean.toString(player.hasVehicle()));
        out.put("elytra", Boolean.toString(player.isGliding()));
        out.put("riptide", Boolean.toString(player.isUsingRiptide()));
        out.put("velocityBudget", String.format(java.util.Locale.ROOT, "%.3f", data.velocityBudget));
        out.put("fullGrace", Boolean.toString(data.isFullyExempt(currentTick)));
        out.put("movementGrace", Boolean.toString(data.isMovementExempt(currentTick)));
        String detail = data.consumeShadowDetail();
        if (detail != null && !detail.isBlank()) out.put("shadow", detail);
        return out;
    }

    /** One compact, deterministic operational summary; intended for a rate-limited log line. */
    public static String summary() {
        return "tick=" + currentTick
                + " tickMs=" + formatMillis(lastTickNanos)
                + " movePackets=" + totalMovePackets
                + " maxPlayerPackets=" + maxPlayerMovePackets
                + " envScans=" + environmentScans
                + " envScanMs=" + formatMillis(environmentScanNanos)
                + " envBudgetSkipped=" + skippedEnvironmentScans
                + " placeAttempts=" + placementAttempts
                + " placeAccepted=" + placementAccepted
                + " placeRejected=" + placementRejected
                + " bigMoveCandidates=" + bigMoveCandidates
                + " authBridgeFailures=" + authBridgeFailures;
    }

    static int totalMovePacketsForTest() {
        return totalMovePackets;
    }

    static int skippedEnvironmentScansForTest() {
        return skippedEnvironmentScans;
    }

    static int placementAttemptsForTest() {
        return placementAttempts;
    }

    static int placementAcceptedForTest() {
        return placementAccepted;
    }

    static int placementRejectedForTest() {
        return placementRejected;
    }

    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String formatPos(double x, double y, double z) {
        return String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", x, y, z);
    }

    private ShadowTelemetry() {}
}
