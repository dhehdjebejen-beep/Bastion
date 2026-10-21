package dev.bastionac.checks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NoSlow's momentum excuse (3.5 beta, second audit). */
class NoSlowSawtoothTest {

    private static final int GRACE = 5;

    /** Replays judged ticks the way MovementChecks does and returns how often the buffer passed 5. */
    private static int flags(double[] speeds, double useLimit) {
        double min = -1, buffer = 0;
        int flags = 0;
        for (int i = 0; i < speeds.length; i++) {
            long usingFor = GRACE + 1 + i;
            double h = speeds[i];
            boolean decaying = MovementChecks.noSlowDecaying(min, h, usingFor, GRACE);
            min = min < 0 ? h : Math.min(min, h);
            if (decaying) continue;
            if (h > useLimit) {
                buffer += 1;
                if (buffer > 5) {
                    buffer = 1;
                    flags++;
                }
            } else {
                buffer = Math.max(0, buffer - 1);
            }
        }
        return flags;
    }

    @Test
    void aSawtoothNoSlowIsCaught() {
        double[] s = new double[60];
        for (int i = 0; i < s.length; i++) s[i] = i % 2 == 0 ? 0.26 : 0.25;
        assertTrue(flags(s, 0.10) >= 3, "the 0.26/0.25 saw that slipped past build 2");
    }

    @Test
    void aSlowGlideDownIsCaughtOnceTheDecayWindowEnds() {
        double[] s = new double[40];
        for (int i = 0; i < s.length; i++) s[i] = 0.30 - 0.005 * i;
        assertTrue(flags(s, 0.10) >= 1);
    }

    @Test
    void honestSprintEatingIsNot() {
        // Sprint momentum bleeding off toward the use penalty, then walking slow.
        double[] s = {0.33, 0.29, 0.22, 0.16, 0.12, 0.09, 0.07, 0.06, 0.06, 0.06, 0.06, 0.06};
        assertTrue(flags(s, 0.10) == 0);
    }

    @Test
    void decayNeedsANewLow() {
        assertTrue(MovementChecks.noSlowDecaying(0.26, 0.25, GRACE + 2, GRACE));
        assertFalse(MovementChecks.noSlowDecaying(0.25, 0.26, GRACE + 2, GRACE), "back up is not decay");
        assertFalse(MovementChecks.noSlowDecaying(0.26, 0.25, GRACE + 20, GRACE), "too late in the use");
        assertFalse(MovementChecks.noSlowDecaying(-1, 0.25, GRACE + 2, GRACE), "nothing to compare with");
    }
}
