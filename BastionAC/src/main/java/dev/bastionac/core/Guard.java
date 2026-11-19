package dev.bastionac.core;

import dev.bastionac.BastionAC;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Keeps an anti-cheat failure inside the anti-cheat. The server-tick handlers, the world and
 * chunk hooks and the outgoing-packet hooks run on paths where an exception stops the whole
 * server; a packet handler is already caught by vanilla, these are not. A failure is logged —
 * in full once a minute per place, counted in between — and the game goes on as if the
 * anti-cheat had not been there for that one call.
 */
public final class Guard {

    private static final long REPORT_EVERY_MS = 60_000;
    private static final Map<String, Long> LAST_REPORT = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> SUPPRESSED = new ConcurrentHashMap<>();

    private Guard() {
    }

    public static void run(String where, Runnable body) {
        try {
            body.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            report(where, t);
        }
    }

    /** The body's answer, or {@code fallback} — the vanilla answer — when it failed. */
    public static <T> T call(String where, Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            report(where, t);
            return fallback;
        }
    }

    static void report(String where, Throwable t) {
        long now = System.currentTimeMillis();
        Long last = LAST_REPORT.get(where);
        if (last != null && now - last < REPORT_EVERY_MS) {
            SUPPRESSED.computeIfAbsent(where, k -> new AtomicInteger()).incrementAndGet();
            return;
        }
        LAST_REPORT.put(where, now);
        AtomicInteger skipped = SUPPRESSED.remove(where);
        BastionAC.LOGGER.error("[GUARD] {} failed{}; the server carries on", where,
                skipped == null ? "" : " (and " + skipped.get() + " more times in the last minute)", t);
    }
}
