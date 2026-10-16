package dev.bastionac.argus;

import dev.bastionac.telemetry.TelemetryIO;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Finds a player's recorded sessions on disk — so Argus can investigate a
 * player who is not online, or the session behind an alert from yesterday
 * (3.3.2). Until then an investigation always read "the last hour before
 * now", which for an offline player is empty: the answer was "too little
 * data" every time.
 */
public final class SessionLocator {

    /** The span an analysis should read, and a line for the staff saying what it is. */
    public record Window(long from, long to, String note) {}

    /** A little before and after the session, so its first and last records are inside. */
    static final long MARGIN_MS = 60_000L;
    /** An alert stamped slightly before its session file's name (clock skew, the first flush). */
    static final long SKEW_MS = 60_000L;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private SessionLocator() {}

    /**
     * The session of this player that contains {@code atMs}, or the latest one
     * when {@code atMs <= 0}. Null when there is no telemetry for it (never
     * recorded, or older than the retention).
     */
    public static Window find(String uuid, long atMs) {
        List<Path> files = TelemetryIO.filesOf(uuid);
        if (files.isEmpty()) return null;
        long[] starts = new long[files.size()];
        long[] ends = new long[files.size()];
        for (int i = 0; i < files.size(); i++) {
            starts[i] = Analysis.startOf(files.get(i));
            try {
                ends[i] = Files.getLastModifiedTime(files.get(i)).toMillis();
            } catch (Exception e) {
                ends[i] = starts[i];
            }
        }
        return pick(starts, ends, atMs);
    }

    /** Pure: which session {@code atMs} falls in (latest for {@code atMs <= 0}), as a window. */
    static Window pick(long[] starts, long[] ends, long atMs) {
        int n = starts.length;
        if (n == 0) return null;
        int idx;
        if (atMs <= 0) {
            idx = n - 1;
        } else {
            idx = -1;
            for (int i = 0; i < n; i++) {
                if (starts[i] > 0 && starts[i] <= atMs + SKEW_MS) idx = i;
            }
            if (idx < 0) return null;   // before the oldest file: gone with the retention
        }
        long start = starts[idx];
        long end = Math.max(start, ends[idx]);
        if (start <= 0) return null;
        String note = "сессия " + DAY.format(Instant.ofEpochMilli(start)) + "–" + TIME.format(Instant.ofEpochMilli(end));
        return new Window(start - MARGIN_MS, end + MARGIN_MS, note);
    }

    /**
     * The uuid and the exact name of a player who left telemetry under this
     * name, ignoring case (the newest file wins). Null when nobody did. An
     * offline-mode uuid computed from a mistyped case would point at nobody.
     */
    public static String[] byName(String name) {
        if (name == null || name.isBlank()) return null;
        for (Path p : TelemetryIO.allFiles()) {
            String fn = p.getFileName().toString();
            int a = fn.indexOf("__");
            int b = fn.lastIndexOf("__");
            if (a <= 0 || b <= a) continue;
            String who = fn.substring(a + 2, b);
            if (who.equalsIgnoreCase(name)) return new String[]{fn.substring(0, a), who};
        }
        return null;
    }
}
