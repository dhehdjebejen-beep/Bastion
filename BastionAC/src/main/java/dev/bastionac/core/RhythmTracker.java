package dev.bastionac.core;

import java.util.Locale;

/**
 * Timing-rhythm analysis for repeated player actions (attacks, block
 * interactions). Detects machine-generated input by the SHAPE of the interval
 * series instead of its rate — a rate check (CPS/FastPlace) only sees "too
 * fast", while an autoclicker set to a human-plausible 9 CPS is given away by
 * how inhumanly even its spacing is.
 *
 * <p>Three independent signals, deliberately different so a cheat cannot dodge
 * all of them at once:
 * <ul>
 *   <li><b>constant</b> — the standard deviation of the gaps collapses to
 *       nothing. A plain "click every N ms" macro.</li>
 *   <li><b>periodic</b> — the gaps are not constant but the SEQUENCE repeats
 *       with a short period (e.g. 110/95/130, 110/95/130…). This is what
 *       "randomised" clickers that cycle a fixed jitter table produce.</li>
 *   <li><b>bounded</b> — the gaps jitter, but every single one lands inside a
 *       band narrower than one game tick, and there is not a single outlier.
 *       A human clicking is quantised to client ticks (~50 ms steps) and always
 *       drops or doubles a click eventually; a randomised macro never does.</li>
 * </ul>
 *
 * <p>Everything below the {@link #MIN_MEAN_MS} floor is ignored on purpose: at
 * 16+ CPS every human is clicking once per client tick, so the series looks
 * constant for entirely legitimate reasons. That regime belongs to the CPS
 * check, not to this one.
 *
 * <p>Pure logic, no game classes — unit-tested in {@code RhythmTrackerTest}.
 */
public final class RhythmTracker {

    /** Below this mean gap the series is tick-quantised noise, not a rhythm. */
    public static final double MIN_MEAN_MS = 60.0;
    /** Above this mean gap the samples are too sparse to mean anything. */
    public static final double MAX_MEAN_MS = 4000.0;

    private static final double CONSTANT_SD_MS = 5.0;
    private static final int MAX_PERIOD = 6;
    private static final int MIN_PERIOD_COMPARISONS = 10;
    private static final double PERIOD_SCORE = 0.85;
    private static final double PERIOD_TOL_MS = 8.0;
    private static final double PERIOD_TOL_RATIO = 0.06;
    private static final double BOUNDED_SPREAD = 0.35;
    private static final double BOUNDED_SD_RATIO = 0.12;

    /** Outcome of one full window. Immutable. */
    public static final class Verdict {
        public final double mean;
        public final double sd;
        public final double spread;      // (max-min)/mean
        public final boolean constant;
        public final boolean periodic;
        public final boolean bounded;
        public final int period;         // 0 when not periodic
        public final double periodScore;

        Verdict(double mean, double sd, double spread, boolean constant,
                boolean periodic, boolean bounded, int period, double periodScore) {
            this.mean = mean;
            this.sd = sd;
            this.spread = spread;
            this.constant = constant;
            this.periodic = periodic;
            this.bounded = bounded;
            this.period = period;
            this.periodScore = periodScore;
        }

        public boolean suspicious() {
            return constant || periodic || bounded;
        }

        /** How much confidence this window carries (1 signal = 1, all three = 3). */
        public int signals() {
            return (constant ? 1 : 0) + (periodic ? 1 : 0) + (bounded ? 1 : 0);
        }

        /** Russian one-liner for the staff alert. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (constant) sb.append("постоянный интервал");
            if (periodic) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(String.format(Locale.ROOT, "шаблон с периодом %d (%.0f%%)", period, periodScore * 100));
            }
            if (bounded) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append("джиттер меньше тика");
            }
            return sb + String.format(Locale.ROOT, ": %.0f мс, σ=%.1f, разброс %.2f", mean, sd, spread);
        }
    }

    private final int window;
    private final long pauseMs;
    private final long[] gaps;
    private int count;
    private long lastMs;

    /**
     * @param window  gaps collected before a verdict is produced
     * @param pauseMs a longer idle gap ends the burst and restarts collection,
     *                so a window never spans "player went away and came back"
     */
    public RhythmTracker(int window, long pauseMs) {
        this.window = Math.max(8, window);
        this.pauseMs = pauseMs;
        this.gaps = new long[this.window];
    }

    /**
     * Records one action. Windows do not overlap: a verdict is returned at most
     * once per {@code window} actions, which is what bounds how fast this check
     * can add VL.
     *
     * @return the verdict when the window just filled, otherwise {@code null}
     */
    public Verdict push(long nowMillis) {
        long prev = lastMs;
        lastMs = nowMillis;
        long gap = nowMillis - prev;
        if (prev == 0L || gap <= 0L || gap > pauseMs) {
            count = 0; // burst broken — never analyse across a pause
            return null;
        }
        gaps[count++] = gap;
        if (count < window) return null;
        count = 0;
        return analyse(gaps, window);
    }

    /** Drops the current burst (teleport, world change, exemption). */
    public void reset() {
        count = 0;
        lastMs = 0L;
    }

    // ------------------------------------------------------------------ math

    /** Pure analysis of a gap series. Exposed for tests. */
    public static Verdict analyse(long[] gaps, int n) {
        if (n < 8) return null;
        double sum = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            sum += gaps[i];
            if (gaps[i] < min) min = gaps[i];
            if (gaps[i] > max) max = gaps[i];
        }
        double mean = sum / n;
        if (mean < MIN_MEAN_MS || mean > MAX_MEAN_MS) return null;

        double sqSum = 0;
        for (int i = 0; i < n; i++) {
            double dev = gaps[i] - mean;
            sqSum += dev * dev;
        }
        double sd = Math.sqrt(sqSum / n);
        double spread = (max - min) / mean;

        // (1) flat line
        boolean constant = sd <= CONSTANT_SD_MS;

        // (2) repeating sequence: compare each gap with the one `p` places back
        double tol = Math.max(PERIOD_TOL_MS, mean * PERIOD_TOL_RATIO);
        int bestPeriod = 0;
        double bestScore = 0;
        for (int p = 2; p <= MAX_PERIOD; p++) {
            int comparisons = n - p;
            if (comparisons < MIN_PERIOD_COMPARISONS) break;
            int hits = 0;
            for (int i = p; i < n; i++) {
                if (Math.abs(gaps[i] - gaps[i - p]) <= tol) hits++;
            }
            double score = (double) hits / comparisons;
            if (score > bestScore) {
                bestScore = score;
                bestPeriod = p;
            }
        }
        // A constant series trivially "repeats" at every period — only call it
        // periodic when the values actually vary, otherwise the label is noise.
        boolean periodic = !constant && bestScore >= PERIOD_SCORE;

        // (3) jitter present, but tighter than one tick and without a single
        //     outlier — human input is quantised to ~50 ms steps and always
        //     eventually drops a click.
        boolean bounded = !constant && spread <= BOUNDED_SPREAD && sd <= mean * BOUNDED_SD_RATIO;

        return new Verdict(mean, sd, spread, constant, periodic, bounded,
                periodic ? bestPeriod : 0, bestScore);
    }
}
