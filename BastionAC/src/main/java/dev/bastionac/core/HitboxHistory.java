package dev.bastionac.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ring buffer of player bounding boxes for the last {@value #TICKS} server
 * ticks. Used by the reach/angle checks for lag compensation: a high-ping
 * player legitimately hits where the target was {@code ping} ago, so the
 * checks test the claim against every historical box in the ping window
 * instead of only the current position.
 */
public final class HitboxHistory {
    private static final int TICKS = 20;
    /** History depth in ticks — the deepest lag compensation a check may ask for. */
    public static final int DEPTH = TICKS;

    private static final Map<Integer, Box[]> BOXES = new ConcurrentHashMap<>();
    private static int cursor;
    private static long tick;

    /** Server thread, once per tick. */
    public static void snapshot(MinecraftServer server) {
        tick++;
        cursor = (int) (tick % TICKS);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Box[] ring = BOXES.computeIfAbsent(p.getId(), id -> new Box[TICKS]);
            ring[cursor] = p.getBoundingBox();
        }
        if (tick % 200 == 0) {
            // Drop entries of entities that no longer exist.
            List<Integer> online = new ArrayList<>();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) online.add(p.getId());
            BOXES.keySet().retainAll(online);
        }
    }

    /**
     * Boxes recorded within the last {@code ticksBack} ticks (most recent
     * first). Empty when the entity is not tracked (non-player targets).
     */
    public static List<Box> recent(int entityId, int ticksBack) {
        Box[] ring = BOXES.get(entityId);
        List<Box> out = new ArrayList<>();
        if (ring == null) return out;
        int n = Math.min(ticksBack, TICKS);
        for (int i = 0; i < n; i++) {
            Box b = ring[Math.floorMod(cursor - i, TICKS)];
            if (b != null) out.add(b);
        }
        return out;
    }

    /**
     * Boxes recorded between {@code fromTicksBack} and {@code toTicksBack}
     * ticks ago, inclusive (most recent first). 0 is the current tick.
     */
    public static List<Box> between(int entityId, int fromTicksBack, int toTicksBack) {
        Box[] ring = BOXES.get(entityId);
        List<Box> out = new ArrayList<>();
        if (ring == null) return out;
        int lo = Math.max(0, fromTicksBack);
        int hi = Math.min(toTicksBack, TICKS - 1);
        for (int i = lo; i <= hi; i++) {
            Box b = ring[Math.floorMod(cursor - i, TICKS)];
            if (b != null) out.add(b);
        }
        return out;
    }

    /**
     * The ticks-back band a hit may legitimately have been aimed at: where the
     * target was about one round trip ago (what the client saw when it
     * clicked), give or take {@code slack} ticks for jitter and the client's
     * entity interpolation. Taking every box from now back to the full ping
     * instead — the old window — let a player who delays all of his traffic
     * pick the nearest of up to twenty positions: Backtrack by real lag.
     *
     * @return {from, to} ticks back
     */
    public static int[] reachBand(int pingMs, int slack) {
        int centre = Math.max(0, pingMs) / 50;
        int from = Math.max(0, centre - slack);
        int to = Math.min(DEPTH - 1, Math.max(2, centre + slack));
        return new int[]{Math.min(from, to), to};
    }

    public static void remove(int entityId) {
        BOXES.remove(entityId);
    }

    private HitboxHistory() {}
}
