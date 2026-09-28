package dev.bastionauth.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsTest {

    @Test
    void createThenLoadYieldsSameDerivedKeys(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("secret.key");
        Secrets created = Secrets.loadOrCreate(file);
        Secrets loaded = Secrets.loadOrCreate(file);
        assertEquals(created.ipHmac("203.0.113.7"), loaded.ipHmac("203.0.113.7"));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).startsWith("#"));
    }

    @Test
    void corruptedFileFailsClosed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("secret.key");
        Files.writeString(file, "not-base64!!!\n");
        assertThrows(IOException.class, () -> Secrets.loadOrCreate(file));

        Files.writeString(file, "QUJD\n"); // valid base64 but far too short
        assertThrows(IOException.class, () -> Secrets.loadOrCreate(file));
    }

    @Test
    void ipHmacIsDeterministicAndKeyed() {
        byte[] keyA = new byte[32];
        byte[] keyB = new byte[32];
        keyB[0] = 1;
        Secrets a = new Secrets(keyA);
        Secrets b = new Secrets(keyB);
        assertEquals(a.ipHmac("198.51.100.1"), a.ipHmac("198.51.100.1"));
        assertNotEquals(a.ipHmac("198.51.100.1"), a.ipHmac("198.51.100.2"));
        assertNotEquals(a.ipHmac("198.51.100.1"), b.ipHmac("198.51.100.1"));
    }

    @Test
    void pepperChangesInput() {
        Secrets s = new Secrets(new byte[32]);
        byte[] in = "password1".getBytes(StandardCharsets.UTF_8);
        byte[] out = s.pepperPassword(in);
        assertEquals(32, out.length);
        assertNotEquals(new String(in, StandardCharsets.UTF_8), new String(out, StandardCharsets.ISO_8859_1));
    }

    @Test
    void maskIpHidesHostPart() {
        assertEquals("203.0.*.*", Secrets.maskIp("203.0.113.7"));
        assertEquals("2001:db8::*", Secrets.maskIp("2001:db8:85a3:0:0:8a2e:370:7334"));
        assertEquals("?", Secrets.maskIp(null));
        assertEquals("?", Secrets.maskIp("unknown"));
    }
}
