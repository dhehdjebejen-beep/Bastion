package dev.bastionac.telemetry;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A growable byte buffer with the few encodings the session format uses:
 * varints (LEB128), zigzag for signed deltas, IEEE floats/doubles and short
 * UTF-8 strings. Not thread-safe; {@link SessionWriter} guards it.
 */
public final class ByteSink {

    private byte[] buf;
    private int len;

    public ByteSink(int initial) {
        buf = new byte[Math.max(64, initial)];
    }

    private void ensure(int extra) {
        if (len + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
        }
    }

    public int size() {
        return len;
    }

    /** The bytes written so far; the sink is empty afterwards (its array is kept). */
    public byte[] take() {
        byte[] out = Arrays.copyOf(buf, len);
        len = 0;
        // Do not keep a huge array around after a burst.
        if (buf.length > 1 << 20) buf = new byte[64 * 1024];
        return out;
    }

    public ByteSink u8(int v) {
        ensure(1);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteSink varint(int v) {
        ensure(5);
        while ((v & ~0x7F) != 0) {
            buf[len++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[len++] = (byte) v;
        return this;
    }

    public ByteSink varlong(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) {
            buf[len++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[len++] = (byte) v;
        return this;
    }

    public ByteSink zigzag(long v) {
        return varlong((v << 1) ^ (v >> 63));
    }

    public ByteSink f32(float v) {
        int bits = Float.floatToRawIntBits(v);
        ensure(4);
        buf[len++] = (byte) (bits >>> 24);
        buf[len++] = (byte) (bits >>> 16);
        buf[len++] = (byte) (bits >>> 8);
        buf[len++] = (byte) bits;
        return this;
    }

    public ByteSink f64(double v) {
        long bits = Double.doubleToRawLongBits(v);
        ensure(8);
        for (int s = 56; s >= 0; s -= 8) buf[len++] = (byte) (bits >>> s);
        return this;
    }

    public ByteSink i64(long v) {
        ensure(8);
        for (int s = 56; s >= 0; s -= 8) buf[len++] = (byte) (v >>> s);
        return this;
    }

    /** A string of at most {@code max} UTF-8 bytes (cut on a character boundary). */
    public ByteSink str(String s, int max) {
        if (s == null) s = "";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(b.length, max);
        // Never cut a multi-byte character in half.
        while (n > 0 && n < b.length && (b[n] & 0xC0) == 0x80) n--;
        varint(n);
        ensure(n);
        System.arraycopy(b, 0, buf, len, n);
        len += n;
        return this;
    }
}
