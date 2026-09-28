package dev.bastionauth.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RFC 6238 test vectors (SHA-1, the six trailing digits of the eight-digit reference values), base32, replay. */
class TotpTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void rfc6238Vectors() {
        assertEquals("287082", Totp.code(RFC_SECRET, Totp.stepOf(59_000L)));
        assertEquals("081804", Totp.code(RFC_SECRET, Totp.stepOf(1_111_111_109_000L)));
        assertEquals("050471", Totp.code(RFC_SECRET, Totp.stepOf(1_111_111_111_000L)));
        assertEquals("005924", Totp.code(RFC_SECRET, Totp.stepOf(1_234_567_890_000L)));
        assertEquals("279037", Totp.code(RFC_SECRET, Totp.stepOf(2_000_000_000_000L)));
        assertEquals("353130", Totp.code(RFC_SECRET, Totp.stepOf(20_000_000_000_000L)));
    }

    @Test
    void verifyAcceptsTheWindowAndRefusesReplay() {
        long now = 1_111_111_111_000L;
        long step = Totp.stepOf(now);
        assertEquals(step, Totp.verify(RFC_SECRET, "050471", now, 1, -1));
        // the previous step's code is still good inside the window (phone clock behind)
        assertEquals(step - 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, 1, -1));
        // two steps back is outside a ±1 window
        assertEquals(-1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, 1, -1));
        // a code at or below the last accepted step is a replay
        assertEquals(-1, Totp.verify(RFC_SECRET, "050471", now, 1, step));
        assertEquals(-1, Totp.verify(RFC_SECRET, "000000", now, 1, -1));
        assertEquals(-1, Totp.verify(RFC_SECRET, "05047", now, 1, -1), "five digits is not a code");
        assertEquals(step, Totp.verify(RFC_SECRET, "050 471", now, 1, -1), "spaces are tolerated");
    }

    @Test
    void base32RoundTripAndGrouping() {
        byte[] secret = Totp.newSecret(new SecureRandom());
        assertEquals(20, secret.length);
        String b32 = Totp.base32(secret);
        assertEquals(32, b32.length());
        assertArrayEquals(secret, Totp.base32Decode(b32));
        assertArrayEquals(secret, Totp.base32Decode(Totp.grouped(b32).toLowerCase()));
        assertEquals("MFRGG===".replace("=", ""), Totp.base32("abc".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(Totp.grouped("ABCDEFGHIJKL").equals("ABCD EFGH IJKL"));
    }

    @Test
    void otpauthUriIsWhatTheAppsExpect() {
        String uri = Totp.otpauth("MaxCora", "Vasya_Pupkin", "ABCDEFGH");
        assertEquals("otpauth://totp/MaxCora:Vasya_Pupkin?secret=ABCDEFGH&issuer=MaxCora&algorithm=SHA1&digits=6&period=30", uri);
        assertTrue(Totp.otpauth("Max Cora", "x", "A").startsWith("otpauth://totp/Max%20Cora:x?"));
    }

    @Test
    void normalizeCode() {
        assertEquals("123456", Totp.normalizeCode(" 123 456 "));
        assertNull(Totp.normalizeCode("12345a"));
        assertNull(Totp.normalizeCode(null));
    }

    @Test
    void aesGcmRoundTripAndTamper() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        byte[] plain = Totp.newSecret(new SecureRandom());
        String blob = AesGcm.encrypt(key, plain);
        assertArrayEquals(plain, AesGcm.decrypt(key, blob));
        byte[] other = new byte[32];
        assertNull(AesGcm.decrypt(other, blob), "another key must not decrypt");
        assertNull(AesGcm.decrypt(key, blob.substring(0, blob.length() - 4) + "AAAA"), "a tampered blob must fail");
    }
}
