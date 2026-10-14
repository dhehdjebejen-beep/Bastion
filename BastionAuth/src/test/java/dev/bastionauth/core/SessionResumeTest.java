package dev.bastionauth.core;

import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A session resumes only for the same IP AND the same client install. */
class SessionResumeTest {

    private static final long NOW = 1_000_000L;
    /** A stand-in for the keyed HMAC: any injective function will do here. */
    private static final UnaryOperator<String> H = m -> Integer.toHexString(m.hashCode()) + m.length();

    private static DeviceFingerprint.SyncedClientOptionsView opts(String lang, int view, int skin, String particles,
                                                                  String hand, boolean chatColours) {
        return new DeviceFingerprint.SyncedClientOptionsView(lang, view, "FULL", chatColours, skin, hand, false, true,
                particles);
    }

    private static final String DEV = DeviceFingerprint.sessionProfile("vanilla",
            opts("ru_ru", 12, 127, "ALL", "RIGHT", true), H);

    @Test
    void sameIpAndProfileResumes() {
        AuthManager.Session s = new AuthManager.Session("ip", NOW + 60_000, DEV);
        assertTrue(AuthManager.sessionMatches(s, "ip", DEV, NOW));
    }

    @Test
    void oneEverydaySettingChangedStillResumes() {
        AuthManager.Session s = new AuthManager.Session("ip", NOW + 60_000, DEV);
        String lowerView = DeviceFingerprint.sessionProfile("vanilla", opts("ru_ru", 6, 127, "ALL", "RIGHT", true), H);
        String noCape = DeviceFingerprint.sessionProfile("vanilla", opts("ru_ru", 12, 126, "ALL", "RIGHT", true), H);
        assertTrue(AuthManager.sessionMatches(s, "ip", lowerView, NOW), "render distance turned down for lag");
        assertTrue(AuthManager.sessionMatches(s, "ip", noCape, NOW), "cape toggled");
    }

    @Test
    void twoSettingsOrTheInstallChangedDoNot() {
        AuthManager.Session s = new AuthManager.Session("ip", NOW + 60_000, DEV);
        String two = DeviceFingerprint.sessionProfile("vanilla", opts("ru_ru", 6, 126, "ALL", "RIGHT", true), H);
        String brand = DeviceFingerprint.sessionProfile("fabric", opts("ru_ru", 12, 127, "ALL", "RIGHT", true), H);
        String lang = DeviceFingerprint.sessionProfile("vanilla", opts("en_us", 12, 127, "ALL", "RIGHT", true), H);
        String hand = DeviceFingerprint.sessionProfile("vanilla", opts("ru_ru", 12, 127, "ALL", "LEFT", true), H);
        assertFalse(AuthManager.sessionMatches(s, "ip", two, NOW));
        assertFalse(AuthManager.sessionMatches(s, "ip", brand, NOW));
        assertFalse(AuthManager.sessionMatches(s, "ip", lang, NOW));
        assertFalse(AuthManager.sessionMatches(s, "ip", hand, NOW));
    }

    @Test
    void strangerBehindTheSameNatDoesNot() {
        AuthManager.Session s = new AuthManager.Session("ip", NOW + 60_000, DEV);
        String stranger = DeviceFingerprint.sessionProfile("vanilla", opts("ru_ru", 8, 95, "DECREASED", "RIGHT", false), H);
        assertFalse(AuthManager.sessionMatches(s, "ip", stranger, NOW));
        assertFalse(AuthManager.sessionMatches(s, "ip", null, NOW));
    }

    @Test
    void otherIpExpiredOrLegacyDoesNot() {
        assertFalse(AuthManager.sessionMatches(new AuthManager.Session("ip", NOW + 60_000, DEV), "other", DEV, NOW));
        assertFalse(AuthManager.sessionMatches(new AuthManager.Session("ip", NOW - 1, DEV), "ip", DEV, NOW));
        assertFalse(AuthManager.sessionMatches(new AuthManager.Session("ip", NOW + 60_000, null), "ip", DEV, NOW),
                "a session from an older build has no profile and asks for the password once");
        assertFalse(AuthManager.sessionMatches(new AuthManager.Session("ip", NOW + 60_000, "oldSingleHmac"), "ip", DEV, NOW),
                "nor does a 1.12.0-1.12.1 single-hash profile");
    }
}
