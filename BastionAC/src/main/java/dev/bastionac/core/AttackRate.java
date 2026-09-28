package dev.bastionac.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared per-player attack-rate window for the correlation trackers.
 * CombatChecks counts attacks in {@link PlayerData} for its own checks; the
 * evasion/recon trackers need the same number by UUID from lifecycle hooks
 * where no PlayerData may exist, so this tiny ring keeps a one-second
 * attack count of its own. Server thread only.
 */
public final class AttackRate {

    private static final Map<UUID, Deque<Long>> HITS = new ConcurrentHashMap<>();

    /** Records one attack. Server thread. */
    public static void record(UUID uuid) {
        Deque<Long> hits = HITS.computeIfAbsent(uuid, u -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (hits) {
            hits.addLast(now);
            while (!hits.isEmpty() && now - hits.peekFirst() > 1000) hits.pollFirst();
        }
    }

    /** Attacks in the last second. */
    public static double currentCps(UUID uuid) {
        Deque<Long> hits = HITS.get(uuid);
        if (hits == null) return 0;
        synchronized (hits) {
            long now = System.currentTimeMillis();
            int n = 0;
            for (long t : hits) {
                if (now - t <= 1000) n++;
            }
            return n;
        }
    }

    public static void forget(UUID uuid) {
        HITS.remove(uuid);
    }

    private AttackRate() {}
}
