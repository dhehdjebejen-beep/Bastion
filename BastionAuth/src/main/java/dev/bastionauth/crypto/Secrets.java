package dev.bastionauth.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Server-local secret material. A single 32-byte master key is stored in
 * {@code secret.key}; purpose-specific keys are derived from it via HMAC so a
 * leak of one derived key never exposes the others.
 *
 * <p>The master key is used for:
 * <ul>
 *   <li>peppering passwords before Argon2 (a stolen database alone is not
 *       enough for offline cracking),</li>
 *   <li>HMAC of client IPs (IPs are never stored or compared in plain text).</li>
 * </ul>
 *
 * <p>Losing this file invalidates all peppered hashes and all sessions, which
 * is why loading fails closed on corruption instead of regenerating the key.
 */
public final class Secrets {
    private static final int KEY_LENGTH = 32;

    private final byte[] pepperKey;
    private final byte[] ipKey;
    private final byte[] deviceKey;
    private final byte[] pwFpKey;
    private final byte[] totpKey;

    public Secrets(byte[] masterKey) {
        if (masterKey == null || masterKey.length < KEY_LENGTH) {
            throw new IllegalArgumentException("master key must be at least " + KEY_LENGTH + " bytes");
        }
        this.pepperKey = hmacSha256(masterKey, "bastionauth:pepper:v1".getBytes(StandardCharsets.UTF_8));
        this.ipKey = hmacSha256(masterKey, "bastionauth:ip:v1".getBytes(StandardCharsets.UTF_8));
        this.deviceKey = hmacSha256(masterKey, "bastionauth:device:v1".getBytes(StandardCharsets.UTF_8));
        this.pwFpKey = hmacSha256(masterKey, "bastionauth:pwfp:v1".getBytes(StandardCharsets.UTF_8));
        this.totpKey = hmacSha256(masterKey, "bastionauth:totp:v1".getBytes(StandardCharsets.UTF_8));
    }

    public static Secrets loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                byte[] key;
                try {
                    key = Base64.getDecoder().decode(s);
                } catch (IllegalArgumentException e) {
                    throw new IOException("secret.key is corrupted (invalid base64). Restore it from a backup; "
                            + "deleting it would invalidate ALL stored passwords and sessions.", e);
                }
                if (key.length < KEY_LENGTH) {
                    throw new IOException("secret.key is corrupted (too short). Restore it from a backup.");
                }
                return new Secrets(key);
            }
            throw new IOException("secret.key exists but contains no key. Restore it from a backup.");
        }
        byte[] key = new byte[KEY_LENGTH];
        new SecureRandom().nextBytes(key);
        String content = "# BastionAuth master secret. BACK THIS FILE UP together with auth.db.\n"
                + "# Losing it invalidates all stored passwords (pepper) and IP sessions.\n"
                + Base64.getEncoder().encodeToString(key) + "\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        tryRestrictPermissions(file);
        return new Secrets(key);
    }

    private static void tryRestrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Non-POSIX file system (Windows) — nothing to do.
        }
    }

    /** Keys the password through HMAC with the server pepper before Argon2. */
    public byte[] pepperPassword(byte[] passwordUtf8) {
        return hmacSha256(pepperKey, passwordUtf8);
    }

    /** Keyed, non-reversible representation of a client IP for storage and comparison. */
    public String ipHmac(String ip) {
        return Base64.getEncoder().withoutPadding()
                .encodeToString(hmacSha256(ipKey, ip.getBytes(StandardCharsets.UTF_8)));
    }

    /** HMAC of arbitrary device-fingerprint material (brand+options+subnet),
     *  keyed separately from the IP key so the two never correlate. */
    public String deviceHmac(String material) {
        return Base64.getEncoder().withoutPadding()
                .encodeToString(hmacSha256(deviceKey, material.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Key material for the deterministic password fingerprint (twin
     * detection). Derived from the master secret with its own purpose
     * string, so neither the pepper key nor any other derived key can be
     * used to forge or verify password fingerprints, and this key cannot
     * be used to attack the password hashes themselves.
     */
    /** AES-256 key for the second-factor seeds at rest. */
    public byte[] totpKey() {
        return totpKey.clone();
    }

    public byte[] pwFingerprintKey() {
        return pwFpKey.clone();
    }

    /**
     * Fixed salt for the password fingerprint's low-cost Argon2 pass. It
     * must be stable forever (equal passwords across accounts have to
     * produce equal fingerprints) but secret (a leaked database must not
     * let anyone verify password guesses against fingerprints offline),
     * which is exactly the property of a keyed, purpose-derived salt.
     */
    public byte[] pwFingerprintSalt() {
        return hmacSha256(pwFpKey, "bastionauth:pwfp-salt:v1".getBytes(StandardCharsets.UTF_8));
    }

    /** Human-readable partially masked IP for logs and "last login" messages. */
    public static String maskIp(String ip) {
        if (ip == null || ip.isEmpty() || "unknown".equals(ip)) return "?";
        if (ip.indexOf(':') >= 0) {
            String[] groups = ip.split(":");
            if (groups.length >= 2 && !groups[0].isEmpty()) return groups[0] + ":" + groups[1] + "::*";
            return "ipv6:*";
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) return parts[0] + "." + parts[1] + ".*.*";
        return "*";
    }

    static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
