package dev.bastionauth.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory sliding-window counters and temporary blocks, keyed by string. */
public final class RateLimiters {

    /** Sliding window: at most {@code max} hits per {@code windowMillis} per key. */
    public static final class SlidingWindow {
        private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
        private final long windowMillis;
        private final int max;

        public SlidingWindow(long windowMillis, int max) {
            this.windowMillis = Math.max(1000, windowMillis);
            this.max = Math.max(1, max);
        }

        /** Records a hit unless the key is already at the limit. @return false when over the limit. */
        public boolean tryAcquire(String key, long now) {
            Deque<Long> d = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (d) {
                prune(d, now);
                if (d.size() >= max) return false;
                d.addLast(now);
                return true;
            }
        }

        /** Always records the hit. @return the number of hits currently inside the window. */
        public int recordAndCount(String key, long now) {
            Deque<Long> d = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (d) {
                prune(d, now);
                d.addLast(now);
                return d.size();
            }
        }

        public void reset(String key) {
            hits.remove(key);
        }

        public void cleanup(long now) {
            Iterator<Map.Entry<String, Deque<Long>>> it = hits.entrySet().iterator();
            while (it.hasNext()) {
                Deque<Long> d = it.next().getValue();
                synchronized (d) {
                    prune(d, now);
                    if (d.isEmpty()) it.remove();
                }
            }
        }

        private void prune(Deque<Long> d, long now) {
            while (!d.isEmpty() && now - d.peekFirst() > windowMillis) d.pollFirst();
        }
    }

    /** Temporary per-key blocks with an absolute expiry timestamp. */
    public static final class TempBlocks {
        private final Map<String, Long> until = new ConcurrentHashMap<>();

        public void block(String key, long untilMillis) {
            until.merge(key, untilMillis, Math::max);
        }

        public boolean isBlocked(String key, long now) {
            Long u = until.get(key);
            if (u == null) return false;
            if (u <= now) {
                until.remove(key, u);
                return false;
            }
            return true;
        }

        public long remainingMillis(String key, long now) {
            Long u = until.get(key);
            return u == null ? 0 : Math.max(0, u - now);
        }

        public void clear(String key) {
            until.remove(key);
        }

        public void cleanup(long now) {
            until.entrySet().removeIf(e -> e.getValue() <= now);
        }
    }

    private RateLimiters() {}
}
