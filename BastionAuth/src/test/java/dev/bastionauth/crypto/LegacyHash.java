package dev.bastionauth.crypto;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Test helper: a PHC string exactly as BastionAuth 1.12 wrote it (16-byte salt). */
final class LegacyHash {

    private LegacyHash() {}

    static String make(Secrets secrets, boolean pepper, byte[] salt, String password, PasswordHasher.Params p) {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] input = pepper ? secrets.pepperPassword(pw) : pw;
        Argon2Parameters ap = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(p.memoryKib()).withIterations(p.iterations()).withParallelism(p.parallelism())
                .withSalt(salt).build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(ap);
        byte[] out = new byte[32];
        gen.generateBytes(input, out);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "$argon2id$v=19$m=" + p.memoryKib() + ",t=" + p.iterations() + ",p=" + p.parallelism()
                + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(out);
    }
}
