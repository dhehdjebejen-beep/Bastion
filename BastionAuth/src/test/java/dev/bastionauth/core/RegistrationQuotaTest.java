package dev.bastionauth.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Accounts per IP: free up to the limit, then one more per growing wait. */
class RegistrationQuotaTest {

    private static final long WEEK = 7 * 86_400_000L;
    private static final long LAST = 1_000_000_000L;

    @Test
    void freeUpToTheLimit() {
        for (int n = 0; n < 6; n++) assertEquals(0, RegistrationQuota.allowedAt(n, LAST, 6, true, WEEK, 0));
    }

    @Test
    void seventhAfterAWeekEighthAfterTwo() {
        assertEquals(LAST + WEEK, RegistrationQuota.allowedAt(6, LAST, 6, true, WEEK, 0), "the 7th account");
        assertEquals(LAST + 2 * WEEK, RegistrationQuota.allowedAt(7, LAST, 6, true, WEEK, 0), "the 8th account");
        assertEquals(LAST + 4 * WEEK, RegistrationQuota.allowedAt(9, LAST, 6, true, WEEK, 0), "the 10th account");
    }

    @Test
    void offOrCappedIsNever() {
        assertEquals(RegistrationQuota.NEVER, RegistrationQuota.allowedAt(6, LAST, 6, false, WEEK, 0), "old hard limit");
        assertEquals(RegistrationQuota.NEVER, RegistrationQuota.allowedAt(10, LAST, 6, true, WEEK, 10), "hard cap");
        assertEquals(LAST + 4 * WEEK, RegistrationQuota.allowedAt(9, LAST, 6, true, WEEK, 10), "just under the cap");
    }

    @Test
    void waitIsReadable() {
        assertEquals("7 д", AuthManager.humanWait(WEEK));
        assertEquals("1 д 2 ч", AuthManager.humanWait(26 * 3_600_000L));
        assertEquals("5 ч 20 мин", AuthManager.humanWait(5 * 3_600_000L + 20 * 60_000L));
        assertEquals("1 мин", AuthManager.humanWait(10_000));
    }

    @Test
    void dormantAccountsStopCounting() {
        long now = 100L * 86_400_000L;
        assertEquals(now - 30L * 86_400_000L, RegistrationQuota.activeSince(now, 30));
        assertEquals(0L, RegistrationQuota.activeSince(now, 0), "0 = every account counts");
    }
}
