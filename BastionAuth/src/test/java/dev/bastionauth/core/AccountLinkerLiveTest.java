package dev.bastionauth.core;

import dev.bastionauth.db.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composite link analysis over a real (temporary) SQLite database: every
 * signal — device, IP, name, timing, password — is verified against the
 * users table instead of being assumed, and the grades are exactly the
 * composite sums. Weights are constructor-injectable so the pure scorer is
 * covered by the existing AccountLinkerTest; this class covers the live
 * analysis path and its database joins.
 */
class AccountLinkerLiveTest {

    @TempDir
    Path dir;
    Database db;
    AccountLinker linker;

    private final String joinerUuid = UUID.randomUUID().toString();
    private final String twinUuid = UUID.randomUUID().toString();
    private final String strangerUuid = UUID.randomUUID().toString();

    private static final String DEVICE_HASH = "device-hash-1";
    private static final String OTHER_DEVICE = "device-hash-2";
    private static final String IP_A = "ip-hmac-aaa";
    private static final String IP_B = "ip-hmac-bbb";
    private static final String PW_FP_X = "pwfp-x";
    private static final String PW_FP_Y = "pwfp-y";

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(dir.resolve("test-auth.db"));
        linker = new AccountLinker();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void insertUser(String uuid, String name, long createdAt, String regIp, String pwFp) throws Exception {
        db.insertUser(uuid, name, name.toLowerCase(java.util.Locale.ROOT),
                "$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA", createdAt, regIp);
        db.updatePwFp(uuid, pwFp);
    }

    @Test
    void deviceOnlyGradesPossible() throws Exception {
        long now = System.currentTimeMillis();
        // Two long-established accounts on one family PC: the joiner has
        // been registered for months, the sibling was seen an hour ago.
        // Device matches; IP, password and timing all differ — the exact
        // "brothers sharing a computer" case that must stay at POSSIBLE.
        long myCreatedAt = now - 180L * 24 * 3_600_000L;
        insertUser(twinUuid, "twin", now - 90L * 24 * 3_600_000L, IP_B, PW_FP_Y);
        db.recordFingerprint(twinUuid, DEVICE_HASH, "vanilla", "ru", 12, "RIGHT", "1.2.3.0/24", now - 3_600_000);

        List<AccountLinker.Candidate> out = linker.analyse(
                joinerUuid, "joiner", DEVICE_HASH, IP_A, null, now, db, myCreatedAt);
        assertEquals(1, out.size());
        // 60 (device) alone: POSSIBLE, never more.
        assertEquals(AccountLinker.Grade.POSSIBLE, linker.grade(out.get(0).score));
        assertEquals(60, out.get(0).score);
        assertFalse(out.get(0).sameIpHmac, "different reg IP must not raise the IP signal");
        assertFalse(out.get(0).registeredSoonAfterBan, "old sibling account must not raise timing");
    }

    @Test
    void devicePlusIpGradesLikely() throws Exception {
        long now = System.currentTimeMillis();
        // Twin registered from the same IP as today's joiner. The joiner is
        // an established account (months old), so timing stays out of it.
        long myCreatedAt = now - 180L * 24 * 3_600_000L;
        insertUser(twinUuid, "twin", now - 90L * 24 * 3_600_000L, IP_A, PW_FP_Y);
        db.recordFingerprint(twinUuid, DEVICE_HASH, "vanilla", "ru", 12, "RIGHT", "9.9.9.0/24", now - 3_600_000);

        List<AccountLinker.Candidate> out = linker.analyse(
                joinerUuid, "joiner", DEVICE_HASH, IP_A, null, now, db, myCreatedAt);
        assertEquals(1, out.size());
        // 60 + 25 = 85: LIKELY.
        assertEquals(85, out.get(0).score);
        assertEquals(AccountLinker.Grade.LIKELY, linker.grade(out.get(0).score));
    }

    @Test
    void lastIpCountsToo() throws Exception {
        long now = System.currentTimeMillis();
        // Registered elsewhere, but the LAST login came from the joiner's IP.
        insertUser(twinUuid, "twin", now - 90L * 24 * 3_600_000L, IP_B, PW_FP_Y);
        db.recordFingerprint(twinUuid, DEVICE_HASH, "vanilla", "ru", 12, "RIGHT", "9.9.9.0/24", now - 3_600_000);
        db.recordLoginSuccess(twinUuid, now - 60_000, IP_A, "1.2.*.*");

        List<AccountLinker.Candidate> out = linker.analyse(
                joinerUuid, "joiner", DEVICE_HASH, IP_A, null, now, db, now);
        assertEquals(1, out.size());
        assertTrue(out.get(0).sameIpHmac, "last_ip_hmac match must raise the IP signal");
    }

    @Test
    void timingSignalFiresForFreshSpareAccount() throws Exception {
        long now = System.currentTimeMillis();
        // The banned twin's final sighting on this device was an hour ago.
        // Different IP from the joiner's so ONLY the timing signal is
        // measured here.
        insertUser(twinUuid, "twin", now - 30L * 24 * 3_600_000L, IP_B, PW_FP_Y);
        db.recordFingerprint(twinUuid, DEVICE_HASH, "vanilla", "ru", 12, "RIGHT", "9.9.9.0/24", now - 3_600_000);

        // The joiner registered ten minutes after that sighting.
        long myCreatedAt = now - 50 * 60_000;
        List<AccountLinker.Candidate> out = linker.analyse(
                joinerUuid, "joiner", DEVICE_HASH, IP_A, null, now, db, myCreatedAt);
        assertEquals(1, out.size());
        // 60 (device) + 20 (timing) = 80: LIKELY.
        assertEquals(80, out.get(0).score);
        assertTrue(out.get(0).registeredSoonAfterBan);
    }

    @Test
    void timingWindowIs72Hours() {
        long now = System.currentTimeMillis();
        AccountLinker l = new AccountLinker();
        // (myCreatedAt, now, candidateLastSeen) — the joiner's account
        // created AFTER the sibling's last sighting, within the window.
        // Sibling was last seen 4h ago, joiner created 3h ago: 1h gap.
        assertTrue(l.isTimingSignal(now - 3L * 3_600_000, now, now - 4L * 3_600_000));
        // Sibling last seen 72h ago exactly, joiner created now: at the edge.
        assertTrue(l.isTimingSignal(now, now, now - 72L * 3_600_000));
        // Sibling last seen 73h ago: outside.
        assertFalse(l.isTimingSignal(now, now, now - 73L * 3_600_000));
        // Created BEFORE the sibling's last sighting (an older brother
        // account): never a signal.
        assertFalse(l.isTimingSignal(now - 10L * 24 * 3_600_000, now, now - 3_600_000));
        // No known sighting: no signal.
        assertFalse(l.isTimingSignal(now, now, 0));
    }

    @Test
    void passwordFingerprintSignalConfirms() throws Exception {
        long now = System.currentTimeMillis();
        // Old sibling (no timing), other IP, but the SAME password.
        // IP_C: neither the joiner's nor used by any other test row.
        insertUser(twinUuid, "twin", now - 200L * 24 * 3_600_000L, "ip-hmac-ccc", PW_FP_X);
        db.recordFingerprint(twinUuid, DEVICE_HASH, "vanilla", "ru", 12, "RIGHT", "9.9.9.0/24", now - 90L * 24 * 3_600_000);

        List<AccountLinker.Candidate> out = linker.analyse(
                joinerUuid, "joiner", DEVICE_HASH, IP_B, PW_FP_X, now, db, now - 200L * 24 * 3_600_000L);
        assertEquals(1, out.size());
        // 60 (device) + 40 (password) = 100: CONFIRMED.
        assertEquals(100, out.get(0).score);
        assertEquals(AccountLinker.Grade.CONFIRMED, linker.grade(out.get(0).score));
        assertTrue(out.get(0).samePasswordPhc);
    }

    @Test
    void passwordAloneFindsReinstalledTwin() throws Exception {
        long now = System.currentTimeMillis();
        // The twin wiped their client (new device hash), other IP, but the
        // same password habit: the device chain is broken, the fingerprint
        // is not. ip-hmac-ddd: a third, distinct IP for both rows.
        insertUser(twinUuid, "twin", now - 200L * 24 * 3_600_000L, "ip-hmac-ddd", PW_FP_X);
        db.recordFingerprint(twinUuid, OTHER_DEVICE, "vanilla", "ru", 12, "RIGHT", "9.9.9.0/24", now - 90L * 24 * 3_600_000);

        // Joiner arrives with a DIFFERENT device hash AND a different IP —
        // only the password signal can connect them.
        List<AccountLinker.Candidate> out = linker.analyse(
                joinerUuid, "joiner", "device-hash-new", "ip-hmac-eee", PW_FP_X, now, db, now);
        // 40 (password) alone is below 50: reported only when it compounds.
        assertTrue(out.isEmpty(), "password alone must stay below the report line");
    }

    @Test
    void strangerNeverReports() throws Exception {
        long now = System.currentTimeMillis();
        insertUser(strangerUuid, "stranger", now - 90L * 24 * 3_600_000L, IP_B, PW_FP_Y);
        db.recordFingerprint(strangerUuid, OTHER_DEVICE, "vanilla", "en", 12, "LEFT", "8.8.8.0/24", now - 3_600_000);

        List<AccountLinker.Candidate> out = linker.analyse(
                joinerUuid, "joiner", DEVICE_HASH, IP_A, null, now, db, now);
        assertTrue(out.isEmpty(), "no shared device / ip / password / timing: nothing to report");
    }
}
