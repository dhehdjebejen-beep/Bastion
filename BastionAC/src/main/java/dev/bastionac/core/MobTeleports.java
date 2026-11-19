package dev.bastionac.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * When a mob last teleported (3.7.0 b21). Endermen and shulkers jump away the moment they are
 * hit; the client hit them where it still saw them, and the server measured the reach to their
 * new place. Keyed by entity id, server tick; old entries are pruned as the map grows.
 */
public final class MobTeleports {
    private static final ConcurrentHashMap<Integer, Long> LAST = new ConcurrentHashMap<>();

    private MobTeleports() {}

    public static void note(int entityId, long tick) {
        LAST.put(entityId, tick);
        if (LAST.size() > 4096) {
            LAST.values().removeIf(t -> tick - t > 200);
        }
    }

    /** Whether the entity teleported within {@code windowTicks} before {@code tick}. */
    public static boolean recent(int entityId, long tick, long windowTicks) {
        Long t = LAST.get(entityId);
        return t != null && tick - t <= windowTicks;
    }
}
