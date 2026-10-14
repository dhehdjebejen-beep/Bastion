package dev.bastionauth.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A session resumes only for the same IP AND the same client install. */
class SessionResumeTest {

    private static final long NOW = 1_000_000L;

    @Test
    void sameIpAndProfileResumes() {
        AuthManager.Session s = new AuthManager.Session("ip", NOW + 60_000, "dev");
        assertTrue(AuthManager.sessionMatches(s, "ip", "dev", NOW));
    }

    @Test
    void strangerBehindTheSameNatDoesNot() {
        AuthManager.Session s = new AuthManager.Session("ip", NOW + 60_000, "owner-client");
        assertFalse(AuthManager.sessionMatches(s, "ip", "stranger-client", NOW));
        assertFalse(AuthManager.sessionMatches(s, "ip", null, NOW));
    }

    @Test
    void otherIpExpiredOrLegacyDoesNot() {
        assertFalse(AuthManager.sessionMatches(new AuthManager.Session("ip", NOW + 60_000, "dev"), "other", "dev", NOW));
        assertFalse(AuthManager.sessionMatches(new AuthManager.Session("ip", NOW - 1, "dev"), "ip", "dev", NOW));
        assertFalse(AuthManager.sessionMatches(new AuthManager.Session("ip", NOW + 60_000, null), "ip", "dev", NOW),
                "a session from an older build has no profile and asks for the password once");
    }
}
