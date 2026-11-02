package dev.bastionac.checks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NoSlow judged by the vanilla step (3.6). The honest case is a real
 * recording: 28.10, a player sprint-jumping up stairs with an apple, which the
 * fixed 0.10 cap flagged as "0,32 бл/тик с предметом".
 */
class NoSlowVanillaStepTest {

    private static final double SPRINT = 0.13;
    private static final float FOOD = 0.2f, GRASS = 0.6f;

    /** Horizontal distance, vertical step and claimed ground of each packet after the apple went up. */
    private static final double[] H = {0.2469, 0.2298, 0.4347, 0.2425, 0.2257, 0.2106, 0.1966, 0.1842, 0.1724,
            0.1622, 0.1525, 0.1645, 0.3152, 0.1774, 0.1663, 0.1565, 0.1475, 0.1392, 0.1318, 0.125, 0.1191, 0.1133,
            0.1086, 0.1046, 0.322};
    private static final double[] DY = {-0.2278, -0.2966, 0.4199, 0.3333, 0.248, 0.1648, 0.0833, 0.0029, -0.0754,
            -0.1524, -0.0244, 0.0, 0.4199, 0.3333, 0.248, 0.1648, 0.0833, 0.0029, -0.0754, -0.1524, -0.2278, -0.3015,
            -0.3738, -0.1213, 0.4199};
    private static final boolean[] G = {false, true, false, false, false, false, false, false, false, false, true,
            true, false, false, false, false, false, false, false, false, false, false, false, true, false};

    /** Replays packets the way MovementChecks does and returns how often the buffer passed 5. */
    private static int flags(double[] h, double[] dy, boolean[] g, boolean sprinting) {
        double buffer = 0, shadow = -1;
        int flags = 0;
        boolean before = false, last = false;
        for (int i = 0; i < h.length; i++) {
            if (i > 0) {
                double base = shadow >= 0 ? shadow : h[i - 1];
                double step = MovementChecks.noSlowStep(base, before, last, dy[i], sprinting, SPRINT, FOOD, GRASS, false);
                double bound = MovementChecks.noSlowSlack(step, last);
                shadow = Math.min(step, h[i]);
                if (h[i] > bound) {
                    if (++buffer > 5) {
                        buffer = 1;
                        flags++;
                    }
                } else {
                    buffer = Math.max(0, buffer - 1);
                }
            }
            before = last;
            last = g[i];
        }
        return flags;
    }

    @Test
    void theVanillaStepMatchesTheRecording() {
        // Landing tick began in the air (drag 0.91), the jump tick on the ground: 0.2298·0.91 + 0.0255 + 0.2.
        double jump = MovementChecks.noSlowStep(0.2298, false, true, 0.4199, true, SPRINT, FOOD, GRASS, false);
        assertEquals(0.4347, jump, 0.002);
        // Second jump: the tick before began on the ground, friction 0.546.
        double second = MovementChecks.noSlowStep(0.1645, true, true, 0.4199, true, SPRINT, FOOD, GRASS, false);
        assertEquals(0.3152, second, 0.002);
    }

    @Test
    void sprintJumpingUpStairsWithAnAppleIsHonest() {
        assertEquals(0, flags(H, DY, G, true));
    }

    @Test
    void noSlowHoldingSprintSpeedOnTheGroundIsCaught() {
        double[] h = new double[30], dy = new double[30];
        boolean[] g = new boolean[30];
        java.util.Arrays.fill(h, 0.28);
        java.util.Arrays.fill(g, true);
        assertTrue(flags(h, dy, g, true) >= 3);
    }

    @Test
    void noSlowHoldingWalkSpeedIsCaught() {
        double[] h = new double[30], dy = new double[30];
        boolean[] g = new boolean[30];
        java.util.Arrays.fill(h, 0.2158);
        java.util.Arrays.fill(g, true);
        assertTrue(flags(h, dy, g, false) >= 3);
    }

    @Test
    void aSawtoothNoSlowIsCaught() {
        double[] h = new double[60], dy = new double[60];
        boolean[] g = new boolean[60];
        for (int i = 0; i < h.length; i++) h[i] = i % 2 == 0 ? 0.26 : 0.25;
        java.util.Arrays.fill(g, true);
        assertTrue(flags(h, dy, g, true) >= 3, "the 0.26/0.25 saw that slipped past an earlier build");
    }

    @Test
    void bunnyHoppingAtFullSpeedWithAnItemIsCaught() {
        // A NoSlow sprint-hopping: the same vanilla physics with the 0.2 left out.
        int n = 72;
        double[] h = new double[n], dy = new double[n];
        boolean[] g = new boolean[n];
        double v = 0.28;
        boolean before = true, last = true;
        for (int i = 0; i < n; i++) {
            boolean jump = i % 12 == 0;
            dy[i] = jump ? 0.42 : -0.05;
            double s2 = v * (before ? GRASS * 0.91 : 0.91);
            s2 += last ? SPRINT * (0.21600002 / (GRASS * GRASS * GRASS)) * 0.98 + (jump ? 0.2 : 0) : 0.026 * 0.98;
            h[i] = s2;
            v = s2;
            g[i] = i % 12 == 11;
            before = last;
            last = g[i];
        }
        assertTrue(flags(h, dy, g, true) >= 1);
    }
}
