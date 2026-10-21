package dev.bastionauth.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit of 16.10 (1.13): sessions, pre-login channels, command text, link noise. */
class AuditFixesTest {

    private static final long H = 3_600_000L;
    private static final UnaryOperator<String> HMAC = m -> Integer.toHexString(m.hashCode()) + m.length();
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static DeviceFingerprint.SyncedClientOptionsView defaults() {
        return new DeviceFingerprint.SyncedClientOptionsView("ru_ru", 12, "FULL", true, 0x7F, "RIGHT", false, true, "ALL");
    }

    // ---------------------------------------------------------------- A2: sessions

    @Test
    void theLauncherUuidIsPartOfTheInstall() {
        String mine = DeviceFingerprint.sessionProfile("vanilla", defaults(), "1b2c3d4e-0000-4000-8000-00000000abcd", HMAC);
        String none = DeviceFingerprint.sessionProfile("vanilla", defaults(), null, HMAC);
        String other = DeviceFingerprint.sessionProfile("vanilla", defaults(), "99999999-0000-4000-8000-00000000abcd", HMAC);
        AuthManager.Session s = new AuthManager.Session("ip", 10 * H, mine);
        assertTrue(AuthManager.sessionMatches(s, "ip", mine, H));
        assertFalse(AuthManager.sessionMatches(s, "ip", none, H), "the same defaults without the owner's launcher");
        assertFalse(AuthManager.sessionMatches(s, "ip", other, H), "another launcher");
    }

    @Test
    void howManySettingsMayChangeIsConfigured() {
        String before = DeviceFingerprint.sessionProfile("vanilla", defaults(), null, HMAC);
        String lowerView = DeviceFingerprint.sessionProfile("vanilla",
                new DeviceFingerprint.SyncedClientOptionsView("ru_ru", 6, "FULL", true, 0x7F, "RIGHT", false, true, "ALL"),
                null, HMAC);
        AuthManager.Session s = new AuthManager.Session("ip", 10 * H, before);
        assertTrue(AuthManager.sessionMatches(s, "ip", lowerView, H, 1));
        assertFalse(AuthManager.sessionMatches(s, "ip", lowerView, H, 0), "settingChangesAllowed = 0: exactly the same");
    }

    @Test
    void aSharedAddressIsNoSecret() {
        long ttl = 8 * H;
        AddressSightings seen = new AddressSightings();
        // The owner leaves at 12:00; the session waits until 20:00.
        AuthManager.Session s = new AuthManager.Session("ip", 20 * H, "p");
        seen.note("ip", OWNER, 12 * H);
        assertFalse(AuthManager.addressShared(seen, s, OWNER, ttl), "only the owner was here");
        seen.note("other-ip", STRANGER, 13 * H);
        assertFalse(AuthManager.addressShared(seen, s, OWNER, ttl), "somebody elsewhere");
        seen.note("ip", STRANGER, 13 * H);
        assertTrue(AuthManager.addressShared(seen, s, OWNER, ttl), "another account came from the same address");
    }

    @Test
    void anAccountSeenWhileTheOwnerPlayedCountsToo() {
        long ttl = 8 * H;
        AddressSightings seen = new AddressSightings();
        seen.note("ip", STRANGER, 9 * H);   // a brother on the same Wi-Fi, three hours before the owner left
        AuthManager.Session s = new AuthManager.Session("ip", 20 * H, "p");
        assertTrue(AuthManager.addressShared(seen, s, OWNER, ttl));
        AddressSightings old = new AddressSightings();
        old.note("ip", STRANGER, 2 * H);    // two days' worth of TTL ago: forgotten
        assertFalse(AuthManager.addressShared(old, s, OWNER, ttl));
        old.prune(4 * H - 1);
        assertEquals(0, old.addresses());
    }

    // ---------------------------------------------------------------- A3: channels

    @Test
    void beforeThePasswordOnlyTheBrandAndTheRegistrationGetThrough() {
        assertTrue(PacketFirewall.channelAllowedWhileFrozen("minecraft:brand"));
        assertTrue(PacketFirewall.channelAllowedWhileFrozen("minecraft:register"));
        assertTrue(PacketFirewall.channelAllowedWhileFrozen("minecraft:unregister"));
        assertFalse(PacketFirewall.channelAllowedWhileFrozen("voicechat:request_secret"));
        assertFalse(PacketFirewall.channelAllowedWhileFrozen("xaerominimap:main"));
        assertFalse(PacketFirewall.channelAllowedWhileFrozen(null));
    }

    // ---------------------------------------------------------------- A8: command text

    @Test
    void secretsAreMaskedInTheCommandTextOthersSee() {
        assertEquals("login ***", CommandMask.mask("login hunter2"));
        assertEquals("/l ***", CommandMask.mask("/l hunter2"));
        assertEquals("register ***", CommandMask.mask("register a b"));
        assertEquals("changepassword ***", CommandMask.mask("changepassword old new"));
        assertEquals("2fa ***", CommandMask.mask("2fa 123456"));
        assertEquals("слово ***", CommandMask.mask("слово рыба"));
        assertEquals("LOGIN ***", CommandMask.mask("LOGIN x"), "the root keeps its case");
        assertEquals("login", CommandMask.mask("login"), "nothing to hide");
        assertEquals("msg Bob hello", CommandMask.mask("msg Bob hello"));
        assertEquals("loginfo x", CommandMask.mask("loginfo x"), "only the exact command");
    }

    // ---------------------------------------------------------------- A7: link noise

    @Test
    void defaultSettingsAreACommonProfile() {
        assertTrue(DeviceFingerprint.everydayDefaults(defaults()));
        assertFalse(DeviceFingerprint.everydayDefaults(
                new DeviceFingerprint.SyncedClientOptionsView("ru_ru", 12, "FULL", true, 0x7E, "RIGHT", false, true, "ALL")),
                "the cape is off: a choice somebody made");
        assertFalse(DeviceFingerprint.everydayDefaults(
                new DeviceFingerprint.SyncedClientOptionsView("ru_ru", 12, "FULL", true, 0x7F, "LEFT", false, true, "ALL")));
        assertTrue(DeviceFingerprint.everydayDefaults(
                new DeviceFingerprint.SyncedClientOptionsView("en_us", 20, "FULL", true, 0x7F, "RIGHT", false, true, "ALL")),
                "render distance and language are not the everyday settings");
    }

    @Test
    void strangersWithDefaultsOnOneTowerAreNoLongerLikely() {
        AccountLinker linker = new AccountLinker();
        // Same device hash (defaults + the tower's /24), the same CGNAT address, the same typed host.
        int common = linker.score(true, true, false, false, false, false, false, false, true, true);
        int own = linker.score(true, true, false, false, false, false, false, false, true, false);
        assertEquals(AccountLinker.Grade.POSSIBLE, linker.grade(common), "was LIKELY (90) — reported to staff");
        assertEquals(AccountLinker.Grade.LIKELY, linker.grade(own), "a profile somebody set up still weighs in full");
        // The same password or the same launcher still convicts.
        assertTrue(linker.grade(linker.score(true, true, false, false, true, false, false, false, true, true))
                .ordinal() >= AccountLinker.Grade.LIKELY.ordinal());
    }
}
