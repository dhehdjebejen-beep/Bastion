package dev.bastionac.core;

/**
 * When a packet actually came off the wire.
 *
 * <p>Vanilla hands every play packet to its handler twice: first on the
 * connection's netty thread (which only re-queues it for the server thread),
 * then on the server thread, where the checks run. Timing read on the server
 * thread is the time the server got round to the packet — a 2-second server
 * stall delivers a perfectly paced client as one 0-ms burst. The netty pass
 * sees the true arrival, so it stamps the packet here, and the server pass
 * looks the stamp up by the packet object itself.
 *
 * <p>The packet object is the same instance in both passes, so the key is its
 * identity hash. A small ring is enough: the server thread consumes stamps in
 * the order they were made, at most a few hundred behind; a lookup scans
 * forward from the oldest unconsumed stamp and skips any that were never
 * consumed (a packet another mod cancelled before our hook).
 */
public final class ArrivalClock {

    private static final int SIZE = 512;
    private final int[] keys = new int[SIZE];
    private final long[] times = new long[SIZE];
    /** The client's tick count when each packet came in (tick ends seen before it on the wire). */
    private final long[] ticks = new long[SIZE];
    private long lastTicks = -1;
    private long head;   // next write
    private long tail;   // oldest unconsumed

    /** Netty thread: this packet arrived now. */
    public synchronized void stamp(Object packet, long nowMs) {
        stamp(packet, nowMs, -1);
    }

    /** Netty thread: this packet arrived now, after {@code clientTicks} tick ends. */
    public synchronized void stamp(Object packet, long nowMs, long clientTicks) {
        int i = (int) (head % SIZE);
        keys[i] = System.identityHashCode(packet);
        times[i] = nowMs;
        ticks[i] = clientTicks;
        head++;
        if (head - tail > SIZE) tail = head - SIZE;   // overrun: forget the oldest
    }

    /**
     * Server thread: when this packet arrived, or {@code fallbackMs} when it
     * was never stamped (sent before the player was tracked, a test, …).
     */
    public synchronized long arrivalOf(Object packet, long fallbackMs) {
        int key = System.identityHashCode(packet);
        int scanned = 0;
        for (long k = tail; k < head && scanned < 64; k++, scanned++) {
            int i = (int) (k % SIZE);
            if (keys[i] == key) {
                tail = k + 1;
                lastTicks = ticks[i];
                return times[i];
            }
        }
        lastTicks = -1;
        return fallbackMs;
    }

    /** The client tick count stamped on the packet {@link #arrivalOf} last found, or -1. */
    public synchronized long lastTicks() {
        return lastTicks;
    }

    /** Stamps still waiting for the server thread (diagnostics). */
    public synchronized int pending() {
        return (int) (head - tail);
    }
}
