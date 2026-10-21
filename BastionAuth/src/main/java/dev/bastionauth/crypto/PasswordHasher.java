package dev.bastionauth.crypto;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Argon2id password hashing with PHC-string encoding
 * ({@code $argon2id$v=19$m=...,t=...,p=...$salt$hash}).
 *
 * <p>Verification recomputes with the parameters stored in the hash itself and
 * compares in constant time. When the configured parameters differ from the
 * stored ones the caller is told to rehash, so cost upgrades roll out
 * transparently on the next successful login.
 *
 * <p>With the pepper enabled the password is first keyed through HMAC-SHA256
 * with a server-local secret, so database theft alone is not sufficient for
 * offline cracking.
 *
 * <h2>The hash says whether it is peppered (1.13)</h2>
 * Up to 1.12 a hash did not say, so every failed check ran Argon2 a second
 * time in the other form, in case the setting had been switched since — on
 * every wrong password, on the very path a brute force takes. The mark is
 * the salt length: 24 bytes for a peppered hash, 20 for a plain one. It keeps
 * the PHC string exactly as before, so an older build still reads it. A hash
 * with the old 16-byte salt is tried in the current form only (and in the
 * other one as well with {@code hashing.legacyPepperFallback}), and is
 * rewritten with its mark at the next successful login.
 */
public final class PasswordHasher {

    public enum VerifyResult { NO_MATCH, MATCH, MATCH_NEEDS_REHASH }

    public record Params(int memoryKib, int iterations, int parallelism) {}

    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d{1,9}),t=(\\d{1,4}),p=(\\d{1,3})\\$([A-Za-z0-9+/]{16,128})\\$([A-Za-z0-9+/]{16,128})");
    /** The salt of a hash made before 1.13: peppered or not, it does not say. */
    static final int LEGACY_SALT_LENGTH = 16;
    static final int PEPPERED_SALT_LENGTH = 24;
    static final int PLAIN_SALT_LENGTH = 20;
    private static final int HASH_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();
    private final Secrets secrets;
    private volatile boolean usePepper;
    private volatile boolean legacyFallback;
    private volatile Params params;

    public PasswordHasher(Secrets secrets, boolean usePepper, Params params) {
        this(secrets, usePepper, params, false);
    }

    public PasswordHasher(Secrets secrets, boolean usePepper, Params params, boolean legacyFallback) {
        this.secrets = secrets;
        this.usePepper = usePepper && secrets != null;
        this.legacyFallback = legacyFallback;
        this.params = sanitize(params);
    }

    public void reconfigure(boolean usePepper, Params params) {
        reconfigure(usePepper, params, this.legacyFallback);
    }

    public void reconfigure(boolean usePepper, Params params, boolean legacyFallback) {
        this.usePepper = usePepper && secrets != null;
        this.legacyFallback = legacyFallback;
        this.params = sanitize(params);
    }

    private static Params sanitize(Params p) {
        int mem = Math.clamp(p.memoryKib(), 8 * 1024, 1024 * 1024);
        int it = Math.clamp(p.iterations(), 1, 20);
        int par = Math.clamp(p.parallelism(), 1, 8);
        return new Params(mem, it, par);
    }

    public String hash(String password) {
        boolean pepper = this.usePepper;
        byte[] salt = new byte[pepper ? PEPPERED_SALT_LENGTH : PLAIN_SALT_LENGTH];
        random.nextBytes(salt);
        Params p = this.params;
        byte[] digest = compute(password, salt, p, pepper, HASH_LENGTH);
        String phc = "$argon2id$v=19$m=" + p.memoryKib() + ",t=" + p.iterations() + ",p=" + p.parallelism()
                + "$" + b64(salt) + "$" + b64(digest);
        Arrays.fill(digest, (byte) 0);
        return phc;
    }

    public VerifyResult verify(String phc, String password) {
        if (phc == null || password == null) return VerifyResult.NO_MATCH;
        Matcher m = PHC.matcher(phc);
        if (!m.matches()) return VerifyResult.NO_MATCH;
        Params stored;
        byte[] salt;
        byte[] expected;
        try {
            stored = new Params(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            salt = Base64.getDecoder().decode(m.group(4));
            expected = Base64.getDecoder().decode(m.group(5));
        } catch (IllegalArgumentException e) {
            return VerifyResult.NO_MATCH;
        }

        Boolean marked = pepperedBySalt(salt.length);
        boolean current = this.usePepper;
        // A marked hash is checked once, in its own form, whatever the setting
        // says now. Without the mark: the current form, and the other one only
        // when the server asked for it.
        boolean pepperFirst = marked != null ? marked : current;
        if (pepperFirst && secrets == null) return VerifyResult.NO_MATCH;
        byte[] actual = compute(password, salt, stored, pepperFirst, expected.length);
        boolean ok = MessageDigest.isEqual(expected, actual);
        boolean usedPepper = pepperFirst;
        Arrays.fill(actual, (byte) 0);
        if (!ok && marked == null && legacyFallback && secrets != null) {
            byte[] fallback = compute(password, salt, stored, !pepperFirst, expected.length);
            ok = MessageDigest.isEqual(expected, fallback);
            usedPepper = !pepperFirst;
            Arrays.fill(fallback, (byte) 0);
        }
        if (!ok) return VerifyResult.NO_MATCH;
        // Rewritten: without a mark, in the other form than configured, or with old parameters.
        boolean needsRehash = marked == null || usedPepper != current || !stored.equals(this.params);
        return needsRehash ? VerifyResult.MATCH_NEEDS_REHASH : VerifyResult.MATCH;
    }

    /** What a salt length says: peppered, plain, or null for a hash made before 1.13. */
    static Boolean pepperedBySalt(int saltLength) {
        if (saltLength == PEPPERED_SALT_LENGTH) return Boolean.TRUE;
        if (saltLength == PLAIN_SALT_LENGTH) return Boolean.FALSE;
        return null;
    }

    public Params currentParams() {
        return params;
    }

    private byte[] compute(String password, byte[] salt, Params p, boolean pepper, int outLen) {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] input = pepper && secrets != null ? secrets.pepperPassword(pw) : pw;
        try {
            Argon2Parameters ap = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(p.memoryKib())
                    .withIterations(p.iterations())
                    .withParallelism(p.parallelism())
                    .withSalt(salt)
                    .build();
            Argon2BytesGenerator gen = new Argon2BytesGenerator();
            gen.init(ap);
            byte[] out = new byte[Math.max(16, outLen)];
            gen.generateBytes(input, out);
            return out;
        } finally {
            Arrays.fill(pw, (byte) 0);
            if (input != pw) Arrays.fill(input, (byte) 0);
        }
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().withoutPadding().encodeToString(data);
    }

    /**
     * Deterministic password fingerprint for twin-account detection
     * (composite account linking): the SAME password on two accounts
     * must produce the SAME value, so it can be compared across rows —
     * but it is NOT a password hash:
     *
     * <ul>
     *   <li>the input is first keyed through HMAC with the server's
     *       pwfp secret, so a stolen database alone cannot be used to
     *       verify offline password guesses against fingerprints;</li>
     *   <li>the salt is fixed and secret (derived from the master key),
     *       which is what makes the output deterministic across
     *       accounts — the one property a real password hash must never
     *       have and this fingerprint must;</li>
     *   <li>the cost is deliberately tiny (8 MiB, 1 iteration, 16 bytes
     *       out): it runs on every registration, password change and
     *       successful login on the auth worker, and equality-of-password
     *       needs collision resistance, not preimage resistance.</li>
     * </ul>
     *
     * Equal fingerprints across two accounts is a strong LINK signal
     * (38% of real users reuse passwords exactly), never a password
     * disclosure: the value reveals nothing about the password itself.
     */
    public String fingerprint(String password) {
        if (password == null || secrets == null) return null;
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] keyed = secrets.pepperPassword(pw);
        try {
            byte[] salt = secrets.pwFingerprintSalt();
            Argon2Parameters ap = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(8 * 1024)
                    .withIterations(1)
                    .withParallelism(1)
                    .withSalt(salt)
                    .build();
            Argon2BytesGenerator gen = new Argon2BytesGenerator();
            gen.init(ap);
            byte[] out = new byte[16];
            gen.generateBytes(keyed, out);
            return b64(out);
        } finally {
            Arrays.fill(pw, (byte) 0);
            Arrays.fill(keyed, (byte) 0);
        }
    }
}
