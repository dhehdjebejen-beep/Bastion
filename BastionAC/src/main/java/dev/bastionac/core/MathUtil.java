package dev.bastionac.core;

/** Pure-math helpers (unit-testable, no Minecraft imports). */
public final class MathUtil {

    /** Flags per second the CPS check is allowed to raise. */
    public static final double CPS_FLAGS_PER_SECOND = 4.0;

    /**
     * How much VL one CPS violation is worth, as a multiple of the check's
     * configured weight. Grows with the overshoot so the response time scales
     * with how blatant the clicker is: one click over the ceiling is barely
     * evidence, six over is a machine and nothing else.
     *
     * @return 0 when the rate is legal, otherwise a multiplier in [0.5, 6.0]
     */
    public static double cpsSeverity(int cps, int maxCps) {
        if (cps <= maxCps) return 0.0;
        return Math.min(6.0, Math.max(0.5, (cps - maxCps) / 2.0));
    }

    /**
     * Seconds of sustained clicking at {@code cps} before VL reaches
     * {@code targetVl}, given the check's weight and decay. Returns
     * {@link Double#POSITIVE_INFINITY} when decay outruns the flag rate — i.e.
     * when the configuration silently disables the response.
     */
    public static double cpsSecondsToReach(int cps, int maxCps, double weight,
                                           double decayPerSecond, double targetVl) {
        double perSecond = CPS_FLAGS_PER_SECOND * cpsSeverity(cps, maxCps) * weight - decayPerSecond;
        if (perSecond <= 0 || targetVl <= 0) return Double.POSITIVE_INFINITY;
        return targetVl / perSecond;
    }

    /** Distance from a point to the closest point of an axis-aligned box. */
    public static double distanceToBox(double px, double py, double pz,
                                       double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        double cx = Math.max(minX, Math.min(px, maxX));
        double cy = Math.max(minY, Math.min(py, maxY));
        double cz = Math.max(minZ, Math.min(pz, maxZ));
        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Cosine between a look vector and the direction to the closest box point. */
    public static double lookDotToBox(double px, double py, double pz,
                                      double lookX, double lookY, double lookZ,
                                      double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        double cx = Math.max(minX, Math.min(px, maxX));
        double cy = Math.max(minY, Math.min(py, maxY));
        double cz = Math.max(minZ, Math.min(pz, maxZ));
        double dx = cx - px;
        double dy = cy - py;
        double dz = cz - pz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6) return 1.0; // inside the box — treat as looking at it
        double lookLen = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        if (lookLen < 1.0e-6) return 1.0;
        return (dx * lookX + dy * lookY + dz * lookZ) / (len * lookLen);
    }

    public static double horizontal(double dx, double dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Expected horizontal displacement of a knockback impulse over {@code ticks}
     * airborne ticks: the closed-form sum of the decaying geometric series
     * {@code v * (1 - drag^n) / (1 - drag)}. Vanilla air drag is 0.98. Used by the
     * Anti-KB check to compute the minimum travel a real impulse must produce.
     */
    public static double knockbackDisplacement(double horizontalVelocity, int ticks, double drag) {
        if (horizontalVelocity <= 0 || ticks <= 0) return 0.0;
        return horizontalVelocity * (1.0 - Math.pow(drag, ticks)) / (1.0 - drag);
    }

    // ------------------------------------------------------------------
    // GCD (aim-grid) mathematics
    // ------------------------------------------------------------------

    /**
     * Quantisation step of a vanilla client's rotation at a given
     * sensitivity, in degrees: {@code mouseDelta * f * 0.15} per pixel,
     * where {@code f = sensitivity * 0.6 + 0.2}, squared and scaled. The
     * standard client caps sensitivity at 200%, giving steps in
     * [0.009, 0.586] degrees. Pure, unit-tested.
     */
    public static double sensitivityStep(double sensitivityPercent) {
        double s = Math.max(0, Math.min(200, sensitivityPercent)) / 100.0;
        double f = s * 0.6 + 0.2;
        f = f * f * 0.6; // vanilla's cubed-ish scaling of the turn factor
        return f * 0.15;
    }

    /**
     * Greatest common divisor of a set of rotation deltas, in the fixed
     * microdegree grain the server sees. Vanilla look deltas are integer
     * multiples of the player's sensitivity step, so their GCD (computed at
     * a fine fixed grain) settles on the step itself. Interpolated aim
     * (smooth aim-assist/killaura paths) produces deltas that never share
     * such a divisor. Pure, unit-tested.
     *
     * @param deltas rotation deltas in degrees (both axes mixed is fine —
     *               the step is shared by yaw and pitch)
     * @return the GCD in degrees, or 0 when no common divisor ≥ the floor
     */
    public static double rotationGcd(double[] deltas) {
        if (deltas == null || deltas.length < 2) return 0;
        // Work in microdegrees: 1e-5 deg grain survives float rounding of the
        // packet's fixed-point angles and is far finer than any sensitivity step.
        long floorMicro = 150; // 0.0015 deg — the smallest vanilla step (0% sens)
        long a = 0;
        for (double d : deltas) {
            if (Math.abs(d) < 1.0e-4) continue; // zero deltas carry no grid info
            long micro = Math.round(Math.abs(d) * 100_000.0);
            if (micro < floorMicro) continue;
            a = a == 0 ? micro : gcd(a, micro);
            if (a <= floorMicro && a > 0) break; // already at the floor — done
        }
        if (a < floorMicro) return 0;
        return a / 100_000.0;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /**
     * Fraction of deltas that are near-integer multiples of the given step.
     * A vanilla client is at ~1.0 for its own step; interpolated aim sits
     * well below. Pure, unit-tested.
     */
    public static double onGridRatio(double[] deltas, double stepDeg) {
        if (deltas == null || deltas.length == 0 || stepDeg <= 0) return 0;
        int on = 0;
        int total = 0;
        for (double d : deltas) {
            if (Math.abs(d) < 1.0e-4) continue;
            total++;
            double multiple = Math.abs(d) / stepDeg;
            double nearest = Math.round(multiple);
            if (nearest >= 1 && Math.abs(multiple - nearest) <= 0.02) on++;
        }
        return total == 0 ? 0 : (double) on / total;
    }

    /**
     * The sensitivity step that explains the most deltas, the share it explains,
     * and how many deltas could be judged at all.
     */
    public record GridFit(double step, double ratio, int judged) {
        public GridFit(double step, double ratio) {
            this(step, ratio, 0);
        }
    }

    /** A delta counts as on the grid within this share of one step. */
    static final double GRID_TOLERANCE = 0.02;
    /**
     * Deltas of more mouse counts than this are not judged: the client sums a
     * tick's turn frame by frame in float, so a fast swing of a few thousand
     * counts is off its exact multiple by more than the tolerance even on a
     * plain mouse.
     */
    static final int GRID_MAX_COUNTS = 1000;
    /** Fewer judgeable deltas than this give no verdict. */
    static final int GRID_MIN_JUDGED = 12;
    /** The smallest step considered (a spyglass at 0 % sensitivity is 0.0012°). */
    static final double GRID_FLOOR = 0.0015;

    /** {@link #bestGridFit(double[], double[], double)} without noise bounds or a known step. */
    public static GridFit bestGridFit(double[] deltas) {
        return bestGridFit(deltas, null, 0);
    }

    /**
     * The best grid the deltas sit on, found robustly. {@link #rotationGcd}
     * returns 0 whenever the deltas share no common divisor — which is exactly
     * what interpolated aim looks like — and its callers then skipped the
     * feature: the cleaner the aimbot, the surer it was to go unjudged.
     *
     * <p>Every candidate step comes from the data: each of the smallest
     * distinct deltas divided by 1..12 (a fast window may hold no turn smaller
     * than a dozen counts), the exact GCD, and the step this player was already
     * seen to use ({@code knownStep}, 0 = none). Each candidate is then refined
     * by least squares over its own multiples, small ones first: a step read
     * off one measured delta is only as exact as that delta, and judging a
     * 300-count turn with it multiplied its error by 300 — on 3.5 build 1 that
     * put an honest fast mouse at 30–80 % "off the grid" (the GCD false alerts
     * of 19.10, steps 0.0311/0.0622/0.0933 = the true 0.01556 doubled, quadrupled...).
     *
     * <p>A delta is judged only when the measurement can tell: at most
     * {@link #GRID_MAX_COUNTS} counts, and its float noise ({@code noise[i]},
     * the precision of the angles it was computed from; null = exact) small
     * against the step. A hand scores ~1.0 for its own step; computed aim lands
     * at the few per cent random chance gives. The ratio is NaN when fewer than
     * {@link #GRID_MIN_JUDGED} deltas could be judged. Pure, unit-tested.
     */
    public static GridFit bestGridFit(double[] deltas, double[] noise, double knownStep) {
        if (deltas == null || deltas.length < 2) return new GridFit(0, Double.NaN, 0);
        java.util.TreeSet<Long> small = new java.util.TreeSet<>();
        for (double d : deltas) {
            double a = Math.abs(d);
            if (a >= GRID_FLOOR && a <= 4.0) small.add(Math.round(a * 100_000.0));
        }
        java.util.List<Double> candidates = new java.util.ArrayList<>();
        int taken = 0;
        for (long micro : small) {
            if (taken++ >= 16) break;
            double v = micro / 100_000.0;
            for (int k = 1; k <= 12; k++) if (v / k >= GRID_FLOOR) candidates.add(v / k);
        }
        double gcd = rotationGcd(deltas);
        if (gcd > 0) candidates.add(gcd);
        if (knownStep >= GRID_FLOOR) candidates.add(knownStep);
        if (candidates.isEmpty()) return new GridFit(0, Double.NaN, 0);
        GridFit best = null;
        for (double c : candidates) {
            double step = refineStep(deltas, noise, c);
            GridFit fit = gridFit(deltas, noise, step);
            if (Double.isNaN(fit.ratio())) continue;
            if (best == null || fit.ratio() > best.ratio() + 1e-12
                    || (Math.abs(fit.ratio() - best.ratio()) <= 1e-12 && fit.step() > best.step())) {
                best = fit;
            }
        }
        return best != null ? best : new GridFit(0, Double.NaN, 0);
    }

    /**
     * Least-squares step over the deltas a candidate already explains, widening
     * the multiples it may use (4, 32, 256, then all judged) so each pass is
     * exact enough for the next. Pure.
     */
    static double refineStep(double[] deltas, double[] noise, double step) {
        double s = step;
        for (int limit : new int[]{4, 32, 256, GRID_MAX_COUNTS}) {
            double num = 0, den = 0;
            int used = 0;
            for (int i = 0; i < deltas.length; i++) {
                double a = Math.abs(deltas[i]);
                if (a < 1.0e-4 || !readable(noise, i, s)) continue;
                double k = a / s;
                long n = Math.round(k);
                if (n < 1 || n > limit || Math.abs(k - n) > 0.15) continue;
                num += a * n;
                den += (double) n * n;
                used++;
            }
            if (used >= 3 && den > 0) s = num / den;
        }
        return s;
    }

    /** The share of judgeable deltas on the given step's grid (NaN = too few to judge). Pure. */
    static GridFit gridFit(double[] deltas, double[] noise, double step) {
        if (step < GRID_FLOOR * 0.5) return new GridFit(step, Double.NaN, 0);
        int on = 0, judged = 0;
        for (int i = 0; i < deltas.length; i++) {
            double a = Math.abs(deltas[i]);
            if (a < 1.0e-4 || !readable(noise, i, step)) continue;
            double k = a / step;
            long n = Math.round(k);
            if (n > GRID_MAX_COUNTS) continue;
            judged++;
            double tol = Math.max(GRID_TOLERANCE, noise == null ? 0 : 2 * noise[i] / step);
            if (n >= 1 && Math.abs(k - n) <= tol) on++;
        }
        if (judged < GRID_MIN_JUDGED) return new GridFit(step, Double.NaN, judged);
        return new GridFit(step, (double) on / judged, judged);
    }

    /** False when the delta's float noise is too coarse for this step: it would land on any grid. */
    private static boolean readable(double[] noise, int i, double step) {
        return noise == null || i >= noise.length || 2 * noise[i] <= 0.05 * step;
    }

    /**
     * The precision of a rotation delta computed from two absolute angles the
     * client sent as floats: one unit in the last place of the larger. The
     * client never wraps its yaw, so a player who spun the camera a few dozen
     * times reads coarser than one who did not.
     */
    public static double angleNoise(float a, float b) {
        return Math.ulp(Math.max(Math.abs(a), Math.abs(b)));
    }

    private MathUtil() {}
}
