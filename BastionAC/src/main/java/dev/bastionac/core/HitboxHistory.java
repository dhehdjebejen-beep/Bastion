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

    public static void remove(int entityId) {
        BOXES.remove(entityId);
    }

    private HitboxHistory() {}
}
