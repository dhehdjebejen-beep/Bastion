package dev.bastionac.core;

/**
 * Timer by balance — how far ahead of real time a client's own clock runs.
 *
 * <p>The old Timer counted 30 move packets and flagged when they arrived in
 * under 870 ms. That window is far shorter than an ordinary network stall:
 * One player's connection went quiet for 1,9 s and then delivered the queued 45
 * packets at once, and another player was flagged the same way when the
 * <em>server</em> stalled for 2,6 s ("Can't keep up") and then processed
 * everything it had queued in one millisecond. Both were measured on the
 * server thread, so a server hiccup looked like a client running fast.
 *
 * <p>Now every client tick is worth 50 ms, and the clock is the moment the
 * packet came off the wire (the netty thread), not the moment the server got
 * round to it:
 *
 * <pre>
 *   balance += (arrival - previous arrival) - 50 ms     per client tick
 *   balance  = min(balance, bank)                      a pause is a credit, up to the bank
 *   flag when balance &lt; -maxAhead                       the client is ahead of real time
 * </pre>
 *
 * A pause followed by its own burst nets out to zero — that is what a lag
 * spike is. A timer hack never pays its time back: at ×1.1 it runs 5 ms
 * ahead on every tick, so it overdraws by 100 ms a second whatever the
 * network does. The bank is capped so a long idle cannot buy a long sprint.
 *
 * <p>Thread use: written by the connection's own netty thread only; read
 * for display and telemetry from the server thread (volatile reads).
 */
public final class TimerBalance {

    /** One vanilla client tick. */
    public static final double TICK_MS = 50.0;

    private long lastMs = -1;
    private volatile double balance;
    /** Most negative balance since the last {@link #takeWorst()}. */
    private volatile double worst;
    /** Set by the server thread (teleport, join, world change) and consumed by the netty thread. */
    private volatile boolean resetRequested;

    /**
     * One client tick arrived at {@code nowMs}. Returns the balance after it
     * (negative = ahead of real time).
     */
    public double onTick(long nowMs, double bankMs) {
        if (resetRequested) {
            resetRequested = false;
            lastMs = -1;
            balance = 0;
        }
        if (lastMs < 0) {
            lastMs = nowMs;
            return balance;
        }
        long elapsed = nowMs - lastMs;
        lastMs = nowMs;
        // The system clock can step backwards (NTP); never read that as a burst.
        if (elapsed < 0) elapsed = 0;
        double b = balance + elapsed - TICK_MS;
        if (b > bankMs) b = bankMs;
        balance = b;
        if (b < worst) worst = b;
        return b;
    }

    /** After a verdict: start from neutral, so one episode is one flag, not one per packet. */
    public void settle() {
        balance = 0;
    }

    /** Asks the netty thread to start over on its next tick (server thread side). */
    public void requestReset() {
        resetRequested = true;
    }

    public double balance() {
        return balance;
    }

    /** The deepest overdraw since the previous call — for the 1 Hz telemetry sample. */
    public double takeWorst() {
        double w = worst;
        worst = 0;
        return w;
    }
}
