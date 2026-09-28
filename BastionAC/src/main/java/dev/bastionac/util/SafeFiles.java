package dev.bastionac.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Crash-safe file writing for the mod's data files.
 *
 * <p>A plain {@code Files.writeString} truncates the target first: a crash or a
 * power cut mid-write leaves a zero-length or half-written file, and
 * {@code bans.json} / {@code history.json} / {@code config.json} are then lost.
 * Every write here goes to a sibling {@code .tmp} and is promoted with an
 * atomic rename, so the target is always either the old content or the new one.
 *
 * <p>{@link #writeAsync} additionally moves the disk I/O off the server thread.
 * One shared single-thread executor keeps writes to the same file ordered
 * (last submitted wins) and never overlaps two renames.
 */
public final class SafeFiles {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BastionAC-IO");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Atomic, synchronous write. Use on shutdown or when the result must be on disk now. */
    public static void write(Path target, String content) throws IOException {
        if (target == null) return;
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Exotic filesystem: still better than truncating the target in place.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Atomic write on the shared I/O thread.
     *
     * <p>{@code content} is a supplier, not a string, so serialisation happens
     * off the server thread too — turning a few thousand objects into JSON is
     * the expensive half, and doing it inline is what made the history flush
     * visible in the tick time. The caller must hand over data nothing else
     * will mutate afterwards (a snapshot), since the supplier runs later.
     */
    public static void writeAsync(Path target, java.util.function.Supplier<String> content,
                                  java.util.function.Consumer<Throwable> onError) {
        if (target == null || content == null) return;
        IO.execute(() -> {
            try {
                write(target, content.get());
            } catch (Throwable t) {
                if (onError != null) onError.accept(t);
            }
        });
    }

    /** Drains pending writes (server shutdown). Best-effort, bounded wait. */
    public static void flush() {
        try {
            IO.submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A stuck disk must never hold the server shutdown hostage.
        }
    }

    private SafeFiles() {}
}
