package dev.bastionac.core;

import com.google.gson.Gson;
import dev.bastionac.BastionAC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The anticheat's own day-by-day journal, {@code logs/bastionac/journal-YYYY-MM-DD.jsonl}.
 *
 * <p>Until 2.30.1 every flag went only into the server log as a {@code [VL]}
 * line, and history.json keeps a capped tail of alerts — so the folder an
 * owner looks into first, {@code logs/bastionac}, looked empty. Every flag,
 * alert, punishment and context dump is now one JSON line here, in order:
 * readable by eye, and exactly the raw material a behavioural review needs.
 * Writes happen off the server thread; files older than
 * {@link #RETENTION_DAYS} days are deleted at start.
 */
public final class AcJournal {

    public static final int RETENTION_DAYS = 60;

    private static final Gson GSON = new Gson();
    private static volatile Path dir;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BastionAC-Journal");
        t.setDaemon(true);
        return t;
    });

    private AcJournal() {}

    public static void init(Path gameDir) {
        dir = gameDir.resolve("logs").resolve("bastionac");
        try {
            Files.createDirectories(dir);
            LocalDate cutoff = LocalDate.now().minusDays(RETENTION_DAYS);
            try (var files = Files.list(dir)) {
                files.filter(f -> f.getFileName().toString().startsWith("journal-")).forEach(f -> {
                    String n = f.getFileName().toString();
                    try {
                        LocalDate d = LocalDate.parse(n.substring("journal-".length(), n.length() - ".jsonl".length()));
                        if (d.isBefore(cutoff)) Files.deleteIfExists(f);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (IOException e) {
            BastionAC.LOGGER.warn("AcJournal: could not prepare {}: {}", dir, e.toString());
        }
    }

    /** One event: kind (flag, alert, kick, ban, dump…), who, which check, the VL and what was seen. */
    public static void write(String kind, String name, String uuid, String check, double vl,
                             String details, Map<String, String> extra) {
        Path d = dir;
        if (d == null) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", System.currentTimeMillis());
        e.put("kind", kind);
        e.put("name", name);
        if (uuid != null) e.put("uuid", uuid);
        if (check != null) e.put("check", check);
        if (vl != 0) e.put("vl", Math.round(vl * 100.0) / 100.0);
        if (details != null) e.put("details", details);
        if (extra != null && !extra.isEmpty()) e.put("evidence", extra);
        String line = GSON.toJson(e) + "\n";
        Path file = d.resolve("journal-" + LocalDate.now() + ".jsonl");
        IO.execute(() -> {
            try {
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                BastionAC.LOGGER.warn("AcJournal: write failed: {}", ex.toString());
            }
        });
    }

    /** At shutdown: let the last lines reach the disk. */
    public static void flush() {
        try {
            IO.submit(() -> {}).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
