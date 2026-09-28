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
