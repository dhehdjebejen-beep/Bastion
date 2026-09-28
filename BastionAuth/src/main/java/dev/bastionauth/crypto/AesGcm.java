package dev.bastionauth.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for the few secrets that must be readable back (TOTP seeds),
 * keyed from the master secret: a copy of {@code auth.db} without
 * {@code secret.key} yields nothing. Output is base64 of {@code iv || ciphertext}.
 */
public final class AesGcm {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    public static String encrypt(byte[] key, byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /** Returns null when the blob is corrupt or was written under another key. */
    public static byte[] decrypt(byte[] key, String blob) {
        try {
            byte[] all = Base64.getDecoder().decode(blob);
            if (all.length <= IV_BYTES) return null;
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (Exception e) {
            return null;
        }
    }

    private AesGcm() {}
}
