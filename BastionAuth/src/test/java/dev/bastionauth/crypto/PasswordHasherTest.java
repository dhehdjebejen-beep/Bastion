package dev.bastionauth.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    /** Fast parameters so tests do not spend seconds hashing. */
    private static final PasswordHasher.Params FAST = new PasswordHasher.Params(8 * 1024, 1, 1);

    private static Secrets secrets(int seed) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 31 + seed);
        return new Secrets(key);
    }

    @Test
    void hashAndVerifyRoundTrip() {
        PasswordHasher hasher = new PasswordHasher(secrets(1), true, FAST);
        String phc = hasher.hash("correctHorse1");
        assertTrue(phc.startsWith("$argon2id$v=19$m=8192,t=1,p=1$"), phc);
        assertEquals(PasswordHasher.VerifyResult.MATCH, hasher.verify(phc, "correctHorse1"));
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, hasher.verify(phc, "wrongPassword1"));
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, hasher.verify(phc, ""));
    }

    @Test
    void saltsMakeHashesUnique() {
        PasswordHasher hasher = new PasswordHasher(secrets(1), true, FAST);
        assertNotEquals(hasher.hash("samePassword1"), hasher.hash("samePassword1"));
    }

    @Test
    void parameterUpgradeTriggersRehash() {
        PasswordHasher old = new PasswordHasher(secrets(1), true, FAST);
        String phc = old.hash("myPassword1");
        PasswordHasher upgraded = new PasswordHasher(secrets(1), true, new PasswordHasher.Params(16 * 1024, 2, 1));
        assertEquals(PasswordHasher.VerifyResult.MATCH_NEEDS_REHASH, upgraded.verify(phc, "myPassword1"));
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, upgraded.verify(phc, "notMyPassword1"));
    }

    @Test
    void pepperToggleStillVerifiesAndRequestsRehash() {
        Secrets s = secrets(2);
        PasswordHasher peppered = new PasswordHasher(s, true, FAST);
        String phc = peppered.hash("myPassword1");

        PasswordHasher unpeppered = new PasswordHasher(s, false, FAST);
        assertEquals(PasswordHasher.VerifyResult.MATCH_NEEDS_REHASH, unpeppered.verify(phc, "myPassword1"));
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, unpeppered.verify(phc, "wrongPassword1"));
    }

    @Test
    void aNewHashSaysWhetherItIsPeppered() {
        Secrets s = secrets(3);
        String peppered = new PasswordHasher(s, true, FAST).hash("myPassword1");
        String plain = new PasswordHasher(s, false, FAST).hash("myPassword1");
        assertEquals(Boolean.TRUE, PasswordHasher.pepperedBySalt(saltOf(peppered).length));
        assertEquals(Boolean.FALSE, PasswordHasher.pepperedBySalt(saltOf(plain).length));
        // Checked in its own form whatever the setting says now, and moved to the current one.
        assertEquals(PasswordHasher.VerifyResult.MATCH_NEEDS_REHASH,
                new PasswordHasher(s, true, FAST).verify(plain, "myPassword1"));
        assertEquals(PasswordHasher.VerifyResult.MATCH, new PasswordHasher(s, false, FAST).verify(plain, "myPassword1"));
    }

    /** A hash as 1.12 wrote it: a 16-byte salt that does not say whether it is peppered. */
    private static String legacy(Secrets s, boolean pepper, String password) {
        String phc = new PasswordHasher(s, pepper, FAST).hash(password);
        byte[] salt = java.util.Arrays.copyOf(saltOf(phc), 16);
        // Re-derive with the legacy salt through a hasher that cannot tell: hash, then swap in the salt.
        return LegacyHash.make(s, pepper, salt, password, FAST);
    }

    @Test
    void anOldHashIsTriedInTheCurrentFormOnly() {
        Secrets s = secrets(4);
        String oldPeppered = legacy(s, true, "myPassword1");
        PasswordHasher now = new PasswordHasher(s, true, FAST);
        assertEquals(PasswordHasher.VerifyResult.MATCH_NEEDS_REHASH, now.verify(oldPeppered, "myPassword1"),
                "right, and rewritten with the mark");
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, now.verify(oldPeppered, "wrongPassword1"));
        // Made before the pepper was switched off: without the fallback it is not tried the other way.
        PasswordHasher plainNow = new PasswordHasher(s, false, FAST);
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, plainNow.verify(oldPeppered, "myPassword1"));
        PasswordHasher withFallback = new PasswordHasher(s, false, FAST, true);
        assertEquals(PasswordHasher.VerifyResult.MATCH_NEEDS_REHASH, withFallback.verify(oldPeppered, "myPassword1"),
                "hashing.legacyPepperFallback for a server that switched it");
    }

    private static byte[] saltOf(String phc) {
        return java.util.Base64.getDecoder().decode(phc.split("\\$")[4]);
    }

    @Test
    void differentSecretNeverMatches() {
        PasswordHasher a = new PasswordHasher(secrets(1), true, FAST);
        String phc = a.hash("myPassword1");
        PasswordHasher b = new PasswordHasher(secrets(99), true, FAST);
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, b.verify(phc, "myPassword1"));
    }

    @Test
    void malformedPhcIsRejected() {
        PasswordHasher hasher = new PasswordHasher(secrets(1), true, FAST);
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, hasher.verify("", "x"));
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, hasher.verify("garbage", "x"));
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH, hasher.verify(null, "x"));
        assertEquals(PasswordHasher.VerifyResult.NO_MATCH,
                hasher.verify("$argon2id$v=19$m=abc,t=1,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAA", "x"));
    }

    @Test
    void insaneParametersAreClamped() {
        PasswordHasher hasher = new PasswordHasher(secrets(1), true, new PasswordHasher.Params(1, 0, 0));
        PasswordHasher.Params p = hasher.currentParams();
        assertEquals(8 * 1024, p.memoryKib());
        assertEquals(1, p.iterations());
        assertEquals(1, p.parallelism());
    }
}
