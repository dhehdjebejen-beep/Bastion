package dev.bastionauth.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitersTest {

    @Test
    void slidingWindowBlocksOverLimitAndRecovers() {
        RateLimiters.SlidingWindow window = new RateLimiters.SlidingWindow(60_000, 3);
        long t = 1_000_000;
        assertTrue(window.tryAcquire("ip", t));
        assertTrue(window.tryAcquire("ip", t + 1));
        assertTrue(window.tryAcquire("ip", t + 2));
        assertFalse(window.tryAcquire("ip", t + 3));
        assertTrue(window.tryAcquire("other", t + 3)); // independent keys
        assertTrue(window.tryAcquire("ip", t + 61_000)); // window expired
    }

    @Test
    void recordAndCountCountsWithinWindow() {
        RateLimiters.SlidingWindow window = new RateLimiters.SlidingWindow(10_000, 5);
        long t = 5_000_000;
        assertEquals(1, window.recordAndCount("k", t));
        assertEquals(2, window.recordAndCount("k", t + 1000));
        assertEquals(3, window.recordAndCount("k", t + 2000));
        assertEquals(1, window.recordAndCount("k", t + 20_000));
    }

    @Test
    void tempBlocksExpire() {
        RateLimiters.TempBlocks blocks = new RateLimiters.TempBlocks();
        long t = 42_000_000;
        blocks.block("ip", t + 5000);
        assertTrue(blocks.isBlocked("ip", t));
        assertEquals(5000, blocks.remainingMillis("ip", t));
        assertFalse(blocks.isBlocked("ip", t + 5001));
        assertFalse(blocks.isBlocked("never-blocked", t));
    }

    @Test
    void tempBlocksKeepLongestExpiry() {
        RateLimiters.TempBlocks blocks = new RateLimiters.TempBlocks();
        blocks.block("ip", 10_000);
        blocks.block("ip", 5_000); // shorter block must not shrink the existing one
        assertEquals(10_000, blocks.remainingMillis("ip", 0));
    }

    @Test
    void cleanupDropsStaleEntries() {
        RateLimiters.SlidingWindow window = new RateLimiters.SlidingWindow(1_000, 2);
        long t = 77_000_000;
        window.recordAndCount("a", t);
        window.cleanup(t + 10_000);
        assertEquals(1, window.recordAndCount("a", t + 10_001));

        RateLimiters.TempBlocks blocks = new RateLimiters.TempBlocks();
        blocks.block("b", t + 100);
        blocks.cleanup(t + 200);
        assertFalse(blocks.isBlocked("b", t + 150)); // already cleaned
    }
}
