package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RhythmTrackerTest {

    private static final int N = 24;

    /** Feeds a full window of gaps and returns the verdict it produces. */
    private static RhythmTracker.Verdict verdictOf(long[] gaps) {
        RhythmTracker tracker = new RhythmTracker(gaps.length, 10_000);
        long now = 1_000_000L;
        tracker.push(now); // seeds the first timestamp, produces no gap
        RhythmTracker.Verdict last = null;
        for (long gap : gaps) {
            now += gap;
            last = tracker.push(now);
        }
        return last;
    }

    private static long[] constant(long gap) {
        long[] gaps = new long[N];
        java.util.Arrays.fill(gaps, gap);
        return gaps;
    }

    // ------------------------------------------------------------------ machine

    @Test
    void plainMacroIsConstant() {
        RhythmTracker.Verdict v = verdictOf(constant(100));
        assertNotNull(v);
        assertTrue(v.constant, "100ms flat line must read as constant");
        assertTrue(v.suspicious());
        assertEquals(100.0, v.mean, 0.001);
        assertEquals(0.0, v.sd, 0.001);
    }

    @Test
    void cyclingJitterTableIsPeriodic() {
        // The "randomised" clicker that actually walks a fixed 3-value table.
        long[] pattern = {110, 92, 131};
        long[] gaps = new long[N];
        for (int i = 0; i < N; i++) gaps[i] = pattern[i % pattern.length];

        RhythmTracker.Verdict v = verdictOf(gaps);
        assertNotNull(v);
        assertFalse(v.constant, "the values vary, so this is not the constant case");
        assertTrue(v.periodic, "a repeating 3-value cycle must be caught");
        assertEquals(3, v.period);
        assertTrue(v.suspicious());
    }

    @Test
    void subTickJitterIsBounded() {
        // Randomised around 100ms by ±12ms: too varied to be constant, too tight
        // and too outlier-free to be a hand (human input steps in ~50ms ticks).
        long[] gaps = new long[N];
        for (int i = 0; i < N; i++) gaps[i] = 100 + ((i * 7) % 25) - 12;

        RhythmTracker.Verdict v = verdictOf(gaps);
        assertNotNull(v);
        assertFalse(v.constant);
        assertTrue(v.bounded, "jitter narrower than one tick must be caught");
        assertTrue(v.suspicious());
    }

    // ------------------------------------------------------------------ human

    @Test
    void tickQuantisedHumanClickingIsClean() {
        // A hand produces whole-tick spacing that wanders (2, 3, 4 ticks) plus
        // network jitter — the exact opposite of every signal above.
        int[] ticks = {2, 3, 2, 2, 3, 4, 2, 3, 3, 2, 4, 2, 2, 3, 2, 4, 3, 2, 3, 2, 2, 4, 3, 2};
        long[] gaps = new long[N];
        for (int i = 0; i < N; i++) gaps[i] = 50L * ticks[i] + ((i * 13) % 21) - 10;

        RhythmTracker.Verdict v = verdictOf(gaps);
        assertNotNull(v);
        assertFalse(v.constant, "human spacing is not constant");
        assertFalse(v.periodic, "human spacing does not cycle");
        assertFalse(v.bounded, "human spacing spans more than one tick");
        assertFalse(v.suspicious());
    }

    @Test
    void oneMissedClickBreaksTheBoundedSignal() {
        // Even a machine-tight series is cleared by a single real outlier — this
        // is what protects a player whose macro-looking burst is just a lucky run.
        long[] gaps = new long[N];
        for (int i = 0; i < N; i++) gaps[i] = 100 + ((i * 7) % 25) - 12;
        gaps[N / 2] = 260; // one dropped click

        RhythmTracker.Verdict v = verdictOf(gaps);
        assertNotNull(v);
        assertFalse(v.bounded);
        assertFalse(v.constant);
    }

    // ------------------------------------------------------------------ guards

    @Test
    void tickCeilingRegimeIsNotJudged() {
        // At 20 CPS everyone clicks once per client tick, so a flat 50ms series
        // proves nothing. No verdict at all below the floor.
        assertNull(verdictOf(constant(50)));
        assertNull(RhythmTracker.analyse(constant(59), N));
    }

    @Test
    void verdictOnlyOncePerWindow() {
        RhythmTracker tracker = new RhythmTracker(N, 10_000);
        long now = 1_000_000L;
        tracker.push(now);
        for (int i = 0; i < N - 1; i++) {
            now += 100;
            assertNull(tracker.push(now), "no verdict before the window is full");
        }
        now += 100;
        assertNotNull(tracker.push(now), "verdict exactly when the window fills");
        now += 100;
        assertNull(tracker.push(now), "the next window starts empty");
    }

    @Test
    void pauseDiscardsTheBurst() {
        RhythmTracker tracker = new RhythmTracker(N, 1500);
        long now = 1_000_000L;
        tracker.push(now);
        for (int i = 0; i < N - 1; i++) {
            now += 100;
            tracker.push(now);
        }
        now += 5000; // player stopped, came back
        assertNull(tracker.push(now), "a window must never span a pause");
        for (int i = 0; i < N - 1; i++) {
            now += 100;
            assertNull(tracker.push(now));
        }
        now += 100;
        assertNotNull(tracker.push(now), "collection restarts cleanly after the pause");
    }

    @Test
    void backwardsClockIsIgnored() {
        RhythmTracker tracker = new RhythmTracker(N, 1500);
        tracker.push(1_000_000L);
        assertNull(tracker.push(999_000L));
    }
}
