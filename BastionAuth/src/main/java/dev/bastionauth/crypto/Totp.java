package dev.bastionauth.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Time-based one-time passwords, RFC 6238 over RFC 4226: six digits,
 * thirty-second steps, HMAC-SHA1 — the profile every authenticator app
 * (Google Authenticator, Aegis, Яндекс Ключ, 1Password) speaks by default.
 *
 * <p>Pure: no clock of its own, no storage. The manager passes the time in
 * and keeps the last accepted step so a code cannot be replayed.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int STEP_SECONDS = 30;
    public static final int SECRET_BYTES = 20;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static byte[] newSecret(SecureRandom rng) {
        byte[] s = new byte[SECRET_BYTES];
        rng.nextBytes(s);
        return s;
    }

    /** The step number a moment in time falls into. */
    public static long stepOf(long millis) {
        return Math.floorDiv(millis / 1000L, STEP_SECONDS);
    }

    /** The code for one step, zero-padded to six digits. */
    public static String code(byte[] secret, long step) {
        byte[] msg = new byte[8];
        long v = step;
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        byte[] h;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            h = mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
        int offset = h[h.length - 1] & 0x0F;
        int binary = ((h[offset] & 0x7F) << 24) | ((h[offset + 1] & 0xFF) << 16)
                | ((h[offset + 2] & 0xFF) << 8) | (h[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format(Locale.ROOT, "%06d", otp);
    }

    /**
     * Checks a typed code against the steps around {@code now} (±{@code window}).
     * Returns the matching step, or -1. Steps at or below {@code lastAccepted}
     * are rejected — a code that already opened the door does not open it twice.
     */
    public static long verify(byte[] secret, String typed, long nowMillis, int window, long lastAccepted) {
        String t = normalizeCode(typed);
        if (t == null) return -1;
        long current = stepOf(nowMillis);
        for (int d = -window; d <= window; d++) {
            long step = current + d;
            if (step <= lastAccepted) continue;
            if (MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                    t.getBytes(StandardCharsets.US_ASCII))) return step;
        }
        return -1;
    }

    /** Six digits, spaces tolerated; anything else is not a code. */
    public static String normalizeCode(String typed) {
        if (typed == null) return null;
        String t = typed.replace(" ", "").trim();
        if (t.length() != DIGITS) return null;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) < '0' || t.charAt(i) > '9') return null;
        return t;
    }

    // ------------------------------------------------------------------ base32 (RFC 4648, no padding)

    public static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    public static byte[] base32Decode(String text) {
        String s = text.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
        byte[] out = new byte[s.length() * 5 / 8];
        int buffer = 0, bits = 0, n = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = BASE32.indexOf(s.charAt(i));
            if (v < 0) throw new IllegalArgumentException("not base32: " + s.charAt(i));
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[n++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out;
    }

    /** "ABCD EFGH IJKL …" for reading a secret out loud or typing it by hand. */
    public static String grouped(String base32) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base32.length(); i += 4) {
            if (i > 0) sb.append(' ');
            sb.append(base32, i, Math.min(base32.length(), i + 4));
        }
        return sb.toString();
    }

    /** The URI authenticator apps read from a QR code. */
    public static String otpauth(String issuer, String account, String secretBase32) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String urlEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return sb.toString();
    }

    private Totp() {}
}
