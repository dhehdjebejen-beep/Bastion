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
 */
public final class PasswordHasher {

    public enum VerifyResult { NO_MATCH, MATCH, MATCH_NEEDS_REHASH }

    public record Params(int memoryKib, int iterations, int parallelism) {}

    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d{1,9}),t=(\\d{1,4}),p=(\\d{1,3})\\$([A-Za-z0-9+/]{16,128})\\$([A-Za-z0-9+/]{16,128})");
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();
    private final Secrets secrets;
    private volatile boolean usePepper;
    private volatile Params params;

    public PasswordHasher(Secrets secrets, boolean usePepper, Params params) {
        this.secrets = secrets;
        this.usePepper = usePepper && secrets != null;
        this.params = sanitize(params);
    }

    public void reconfigure(boolean usePepper, Params params) {
        this.usePepper = usePepper && secrets != null;
        this.params = sanitize(params);
    }

    private static Params sanitize(Params p) {
        int mem = Math.clamp(p.memoryKib(), 8 * 1024, 1024 * 1024);
        int it = Math.clamp(p.iterations(), 1, 20);
        int par = Math.clamp(p.parallelism(), 1, 8);
        return new Params(mem, it, par);
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        Params p = this.params;
        byte[] digest = compute(password, salt, p, this.usePepper, HASH_LENGTH);
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

        boolean pepperFirst = this.usePepper;
        byte[] actual = compute(password, salt, stored, pepperFirst, expected.length);
        boolean ok = MessageDigest.isEqual(expected, actual);
        boolean usedFallback = false;
        Arrays.fill(actual, (byte) 0);
        if (!ok && secrets != null) {
            // The pepper setting may have been toggled since this hash was created —
            // accept the other form once; the caller rehashes to the current form.
            byte[] fallback = compute(password, salt, stored, !pepperFirst, expected.length);
            ok = MessageDigest.isEqual(expected, fallback);
            usedFallback = true;
            Arrays.fill(fallback, (byte) 0);
        }
        if (!ok) return VerifyResult.NO_MATCH;
        boolean needsRehash = usedFallback || !stored.equals(this.params);
        return needsRehash ? VerifyResult.MATCH_NEEDS_REHASH : VerifyResult.MATCH;
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
