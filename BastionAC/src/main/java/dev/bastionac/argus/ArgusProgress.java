package dev.bastionac.argus;

import java.util.EnumMap;
import java.util.Map;

/**
 * How far one analysis of a case has got — written by the Argus thread at each
 * step, read by the staff menu twice a second (3.2). One instance per
 * analysis; the last one of a case stays readable after it finished, so the
 * menu can also say how long each stage of the last run took.
 *
 * <p>The analysis itself usually takes a fraction of a second. What can take
 * long is everything around it — the queue, a lagging server Argus steps aside
 * for, reading the rock around a long tunnel — and those are exactly the
 * stages this makes visible.
 */
public final class ArgusProgress {

    /** The stages of one run, in order. */
    public enum Stage {
        QUEUED("в очереди"),
        WAITING_SERVER("ждёт разгрузки сервера"),
        READING("чтение телеметрии"),
        MOVEMENT("повтор физики движения"),
        PACKETS("пакеты и тайминги"),
        COMBAT("бой и прицел"),
        CLIENT("клиент и бренд"),
        WORLD("мир вокруг туннелей"),
        MINING("добыча и X-ray"),
        SCORING("оценка и отчёт"),
        DONE("готово");

        public final String label;

        Stage(String label) {
            this.label = label;
        }

        /** Part of the analysis proper (the bar counts these). */
        public boolean working() {
            return ordinal() >= READING.ordinal() && ordinal() <= SCORING.ordinal();
        }
    }

    /** Stages the progress bar counts. */
    public static final int WORK_STAGES = Stage.SCORING.ordinal() - Stage.READING.ordinal() + 1;

    /** An immutable look at a run, for the menu. */
    public record View(Stage stage, int step, int steps, String detail, long queuedAt, long startedAt,
                       long stageAt, long finishedAt, int ahead, Map<Stage, Long> took) {

        public boolean done() {
            return stage == Stage.DONE;
        }

        /** 0..1 through the analysis stages (queue and waiting count as 0). */
        public double fraction() {
            if (stage == Stage.DONE) return 1;
            if (!stage.working()) return 0;
            double base = stage.ordinal() - Stage.READING.ordinal();
            double inStage = steps > 0 ? Math.min(1.0, (double) step / steps) : 0;
            return Math.min(1.0, (base + inStage) / WORK_STAGES);
        }

        /** Milliseconds of the whole run so far (or in total, once done). */
        public long runMs(long now) {
            if (startedAt <= 0) return 0;
            return (finishedAt > 0 ? finishedAt : now) - startedAt;
        }
    }

    final String caseId;
    final int generation;
    private Stage stage = Stage.QUEUED;
    private int step, steps;
    private String detail;
    private final long queuedAt;
    private long startedAt, stageAt, finishedAt;
    private int ahead;
    private final EnumMap<Stage, Long> took = new EnumMap<>(Stage.class);

    ArgusProgress(String caseId, int generation, long now) {
        this.caseId = caseId;
        this.generation = generation;
        this.queuedAt = now;
        this.stageAt = now;
    }

    /** Moves to {@code next}; the time spent in the previous stage is recorded. */
    synchronized void stage(Stage next, long now) {
        if (stage == Stage.DONE) return;
        if (next != stage) {
            took.merge(stage, Math.max(0, now - stageAt), Long::sum);
            stageAt = now;
            stage = next;
            step = 0;
            steps = 0;
            detail = null;
        }
        if (next.working() && startedAt == 0) startedAt = now;
        if (next == Stage.DONE) finishedAt = now;
    }

    void stage(Stage next) {
        stage(next, System.currentTimeMillis());
    }

    /** Step {@code step} of {@code steps} inside the current stage (a file, a session, cells read). */
    synchronized void step(int step, int steps) {
        this.step = step;
        this.steps = steps;
    }

    synchronized void detail(String detail) {
        this.detail = detail;
    }

    /** Jobs before this one on the Argus thread (the one running counts). */
    synchronized void ahead(int ahead) {
        this.ahead = ahead;
    }

    synchronized Stage stage() {
        return stage;
    }

    public synchronized View view() {
        return new View(stage, step, steps, detail, queuedAt, startedAt, stageAt, finishedAt, ahead,
                new EnumMap<>(took));
    }
}
