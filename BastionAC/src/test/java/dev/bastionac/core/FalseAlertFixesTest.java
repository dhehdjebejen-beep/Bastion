package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The false GCD and MultiTarget alerts of 18–19.10 (3.5 build 2): an honest
 * fast mouse on a plain 1.21.11 client, and clicking into a dense flock.
 */
class FalseAlertFixesTest {

    /** 1.21.11 Mouse.updateMouse: step per mouse count at a sensitivity in [0, 1]. */
    private static double step(double sensitivity) {
        double f = sensitivity * 0.6000000238418579 + 0.20000000298023224;
        return f * f * f * 8.0 * 0.15;
    }

    /**
     * What the server receives from a vanilla client: per frame it adds
     * (float) (counts * f³·8) * 0.15f to a float yaw/pitch that it never wraps;
     * one packet per tick carries the sum of that tick's frames.
     */
    private static double[][] vanillaTicks(double sensitivity, int ticks, int maxCountsPerFrame,
                                           float startYaw, long seed) {
        Random r = new Random(seed);
        double h = step(sensitivity) / 0.15;
        float yaw = startYaw, pitch = 10f;
        double[] deltas = new double[ticks * 2];
        double[] noise = new double[ticks * 2];
        int n = 0;
        for (int t = 0; t < ticks; t++) {
            float y0 = yaw, p0 = pitch;
            int frames = 3 + r.nextInt(15);
            for (int f = 0; f < frames; f++) {
                int cx = r.nextInt(2 * maxCountsPerFrame + 1) - maxCountsPerFrame;
                int cy = r.nextInt(maxCountsPerFrame / 4 + 1) - maxCountsPerFrame / 8;
                float gx = (float) (cx * h) * 0.15f;
                float gy = (float) (cy * h) * 0.15f;
                yaw = yaw + gx;
                pitch = Math.max(-89f, Math.min(89f, pitch + gy));
            }
            float dy = yaw - y0, dp = pitch - p0;
            if (Math.abs(dy) > 1e-3) {
                deltas[n] = Math.abs(dy);
                noise[n++] = MathUtil.angleNoise(yaw, y0);
            }
            if (Math.abs(dp) > 1e-3) {
                deltas[n] = Math.abs(dp);
                noise[n++] = MathUtil.angleNoise(pitch, p0);
            }
        }
        return new double[][]{java.util.Arrays.copyOf(deltas, n), java.util.Arrays.copyOf(noise, n)};
    }

    @Test
    void aFastHonestMouseSitsOnItsGrid() {
        // The 19.10 test: low in-game sensitivity on a 1200-DPI mouse, the camera
        // spun hard, yaw hundreds of degrees past zero. Build 1 read 26–85 % of
        // this as "off the grid" and alerted on nearly every click.
        for (long seed = 1; seed <= 20; seed++) {
            double[][] w = vanillaTicks(0.17, 20, 40, -666f - seed * 50, seed);
            MathUtil.GridFit fit = MathUtil.bestGridFit(w[0], w[1], 0);
            assertTrue(Double.isNaN(fit.ratio()) || fit.ratio() >= 0.9, "seed " + seed + ": " + fit);
        }
    }

    @Test
    void aWindowOfOnlyBigSwingsIsJudgedWithTheLearnedStep() {
        double s = step(0.17);
        double[][] w = vanillaTicks(0.17, 20, 60, 1500f, 7);
        MathUtil.GridFit fit = MathUtil.bestGridFit(w[0], w[1], s);
        assertTrue(fit.ratio() >= 0.9, "the step seen earlier explains the swings: " + fit);
    }

    @Test
    void interpolatedAimOfTheSameSizeIsStillOffTheGrid() {
        // The same turn sizes, but each landing between mouse counts: a smooth
        // aim path. The fix must not have bought its silence with blindness.
        Random r = new Random(11);
        for (long seed = 1; seed <= 10; seed++) {
            double[][] w = vanillaTicks(0.17, 20, 40, -666f, seed);
            double s = step(0.17);
            double[] off = w[0].clone();
            for (int i = 0; i < off.length; i++) off[i] += s * (0.15 + 0.7 * r.nextDouble());
            MathUtil.GridFit fit = MathUtil.bestGridFit(off, w[1], s);
            assertTrue(fit.ratio() < 0.5, "seed " + seed + ": " + fit);
        }
    }

    @Test
    void anglesTooCoarseToReadAreLeftOut() {
        // A yaw spun out to a million degrees has float steps (1/16°) far past
        // the sensitivity grid: those deltas are left out rather than read as
        // off-grid; the pitch, still exact, is what gets judged.
        double[][] w = vanillaTicks(0.17, 20, 10, 1.0e6f, 3);
        MathUtil.GridFit fit = MathUtil.bestGridFit(w[0], w[1], 0);
        assertTrue(Double.isNaN(fit.ratio()) || fit.ratio() >= 0.9, fit.toString());
    }

    @Test
    void aSoftlyMovingFlockSwitchIsNotMultitarget() {
        PlayerData d = new PlayerData(UUID.randomUUID(), "t");
        // Ten clicks a second, the crosshair on a different sheep each time,
        // all of them in front of the player.
        assertEquals(0, d.recordTargetAndCountOffLook(1, 100, 6, true, 10f));
        assertEquals(0, d.recordTargetAndCountOffLook(2, 102, 6, true, 14f));
        assertEquals(0, d.recordTargetAndCountOffLook(3, 104, 6, true, 8f));
        assertEquals(3, d.distinctRecentTargets(104, 6));
        assertTrue(d.recentTargetSpread(104, 6) < 10);
    }

    @Test
    void anAuraHittingAroundThePlayerIsMultitarget() {
        PlayerData d = new PlayerData(UUID.randomUUID(), "t");
        d.recordTargetAndCountOffLook(1, 100, 6, false, 0f);
        d.recordTargetAndCountOffLook(2, 101, 6, false, 120f);
        int off = d.recordTargetAndCountOffLook(3, 102, 6, true, -110f);
        assertEquals(2, off);
        assertEquals(3, d.distinctRecentTargets(102, 6));
        assertTrue(d.recentTargetSpread(102, 6) >= 120, "spread " + d.recentTargetSpread(102, 6));
    }

    @Test
    void spreadWrapsAroundTheBackOfThePlayer() {
        PlayerData d = new PlayerData(UUID.randomUUID(), "t");
        d.recordTargetAndCountOffLook(1, 100, 6, true, 175f);
        d.recordTargetAndCountOffLook(2, 101, 6, true, -175f);
        assertEquals(10, d.recentTargetSpread(101, 6), 1e-3);
    }
}
