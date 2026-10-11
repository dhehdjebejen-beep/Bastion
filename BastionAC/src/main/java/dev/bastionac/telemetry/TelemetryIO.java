package dev.bastionac.telemetry;

import dev.bastionac.BastionAC;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * The disk side of the telemetry: one I/O thread, one gzip stream per open
 * session, and the janitor that keeps the folder inside its budget.
 *
 * <p>The server thread never touches a file. It hands finished byte chunks
 * here; this thread appends them to the session's gzip stream and flushes
 * with SYNC_FLUSH, so a crash loses at most the last few seconds and every
 * file stays readable up to its last chunk even while it is still open.
 *
 * <p><b>Budget.</b> At start and every quarter of an hour: files older than
 * the retention are deleted, then the oldest files go until the folder is
 * under the size cap. A file is never deleted while it is open, nor while an
 * Argus case still needs it (the pinned set).
 */
public final class TelemetryIO {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BastionAC-Telemetry");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    /** Open streams by file name. Touched on the I/O thread only. */
    private static final Map<String, OutputStream> OPEN = new HashMap<>();
    private static volatile Path root;
    private static volatile long totalBytes;
    private static volatile int fileCount;
    private static volatile long lastJanitorMs;

    private TelemetryIO() {}

    public static void init(Path sessionsDir) {
        root = sessionsDir;
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            BastionAC.LOGGER.warn("Telemetry: could not create {}: {}", sessionsDir, e.toString());
        }
    }

    public static Path root() {
        return root;
    }

    /** Opens the session's file (day folder of its start) and writes the first chunk. */
    public static void open(SessionWriter w, byte[] first) {
        Path base = root;
        if (base == null) return;
        IO.execute(() -> {
            try {
                Path file = fileOf(w.fileName());
                Files.createDirectories(file.getParent());
                OutputStream out = new GZIPOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024), 64 * 1024, true);
                OPEN.put(w.fileName(), out);
                out.write(first);
                out.flush();
            } catch (IOException e) {
                BastionAC.LOGGER.warn("Telemetry: could not open session {}: {}", w.fileName(), e.toString());
            }
        });
    }

    /** Appends a chunk and makes it durable (SYNC_FLUSH). */
    public static void append(SessionWriter w, byte[] chunk) {
        if (chunk.length == 0) return;
        IO.execute(() -> {
            OutputStream out = OPEN.get(w.fileName());
            if (out == null) return;
            try {
                out.write(chunk);
                out.flush();
            } catch (IOException e) {
                BastionAC.LOGGER.warn("Telemetry: write failed for {}: {}", w.fileName(), e.toString());
            }
        });
    }

    /** Last chunk, gzip trailer, close. */
    public static void close(SessionWriter w, byte[] last) {
        IO.execute(() -> {
            OutputStream out = OPEN.remove(w.fileName());
            if (out == null) return;
            try {
                if (last.length > 0) out.write(last);
                out.close();
            } catch (IOException e) {
                BastionAC.LOGGER.warn("Telemetry: close failed for {}: {}", w.fileName(), e.toString());
            }
        });
    }

    /** {@code sessions/<yyyy-MM-dd>/<name>}; the day is part of the file name. */
    public static Path fileOf(String fileName) {
        // <uuid>__<name>__<yyyyMMdd-HHmmss>.btl.gz
        String day = "unknown";
        int i = fileName.lastIndexOf("__");
        if (i > 0 && fileName.length() >= i + 10) {
            String d = fileName.substring(i + 2, i + 10);
            day = d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
        }
        return root.resolve(day).resolve(fileName);
    }

    /** Waits for everything queued so far (shutdown, tests). */
    public static void drain(long timeoutMs) {
        try {
            IO.submit(() -> {}).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    /** Runs a task on the I/O thread (the reader uses it to read a file only after its pending chunks). */
    public static void execute(Runnable r) {
        IO.execute(r);
    }

    // ---------------------------------------------------------------- janitor

    /** One budget pass, off the server thread. */
    public static void janitor(int retentionDays, long maxBytes, Supplier<Set<String>> pinned) {
        lastJanitorMs = System.currentTimeMillis();
        IO.execute(() -> runJanitor(retentionDays, maxBytes, pinned.get()));
    }

    public static long lastJanitorMs() {
        return lastJanitorMs;
    }

    private static void runJanitor(int retentionDays, long maxBytes, Set<String> pinned) {
        Path base = root;
        if (base == null || !Files.isDirectory(base)) return;
        record F(Path path, String name, long size, long modified) {}
        List<F> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(base, 2)) {
            s.filter(p -> p.getFileName().toString().endsWith(".btl.gz")).forEach(p -> {
                try {
                    files.add(new F(p, p.getFileName().toString(), Files.size(p),
                            Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            BastionAC.LOGGER.warn("Telemetry janitor: {}", e.toString());
            return;
        }
        long cutoff = LocalDate.now().minusDays(retentionDays).atStartOfDay()
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long total = 0;
        int deleted = 0;
        files.sort(Comparator.comparingLong(F::modified));
        List<F> kept = new ArrayList<>();
        for (F f : files) {
            boolean busy = OPEN.containsKey(f.name()) || pinned.contains(f.name());
            if (!busy && f.modified() < cutoff) {
                if (delete(f.path())) deleted++;
                continue;
            }
            kept.add(f);
            total += f.size();
        }
        for (F f : kept) {
            if (total <= maxBytes) break;
            if (OPEN.containsKey(f.name()) || pinned.contains(f.name())) continue;
            if (delete(f.path())) {
                total -= f.size();
                deleted++;
            }
        }
        totalBytes = total;
        fileCount = files.size() - deleted;
        // Empty day folders.
        try (Stream<Path> s = Files.list(base)) {
            s.filter(Files::isDirectory).forEach(d -> {
                try (Stream<Path> in = Files.list(d)) {
                    if (in.findAny().isEmpty()) Files.deleteIfExists(d);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        if (deleted > 0) {
            BastionAC.LOGGER.info("Telemetry janitor: {} old session file(s) removed, {} MB in {} files",
                    deleted, total / (1024 * 1024), fileCount);
        }
    }

    private static boolean delete(Path p) {
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            return false;
        }
    }

    public static long totalBytes() {
        return totalBytes;
    }

    public static int fileCount() {
        return fileCount;
    }

    /** All session files of one player, oldest first (I/O-free listing of the day folders). */
    public static List<Path> filesOf(String uuid) {
        Path base = root;
        List<Path> out = new ArrayList<>();
        if (base == null || !Files.isDirectory(base)) return out;
        try (Stream<Path> s = Files.walk(base, 2)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(uuid + "__") && n.endsWith(".btl.gz");
            }).forEach(out::add);
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString().substring(
                p.getFileName().toString().lastIndexOf("__"))));
        return out;
    }

    /** Every session file, newest first (baseline seeding). */
    public static List<Path> allFiles() {
        Path base = root;
        List<Path> out = new ArrayList<>();
        if (base == null || !Files.isDirectory(base)) return out;
        try (Stream<Path> s = Files.walk(base, 2)) {
            s.filter(p -> p.getFileName().toString().endsWith(".btl.gz")).forEach(out::add);
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing((Path p) -> {
            String n = p.getFileName().toString();
            return n.substring(n.lastIndexOf("__"));
        }).reversed());
        return out;
    }
}
