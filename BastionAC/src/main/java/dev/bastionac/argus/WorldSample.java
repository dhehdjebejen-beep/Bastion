package dev.bastionac.argus;

import dev.bastionac.telemetry.TelemetryFormat;

/**
 * A box of the world as categories (TelemetryFormat.CTX_*), read on the
 * server thread in slices and handed to the analyzer thread, which never
 * touches the live world itself.
 */
public final class WorldSample {

    /** A cell outside the loaded world. */
    public static final byte UNKNOWN = 15;

    public final int x0, y0, z0, sx, sy, sz;
    public final byte[] cells;

    public WorldSample(int x0, int y0, int z0, int sx, int sy, int sz) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = new byte[sx * sy * sz];
        java.util.Arrays.fill(cells, UNKNOWN);
    }

    public int index(int x, int y, int z) {
        int dx = x - x0, dy = y - y0, dz = z - z0;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sx || dy >= sy || dz >= sz) return -1;
        return (dx * sy + dy) * sz + dz;
    }

    public int get(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? UNKNOWN : cells[i];
    }

    public static boolean opaque(int c) {
        return c == TelemetryFormat.CTX_STONE || c == TelemetryFormat.CTX_DIAMOND
                || c == TelemetryFormat.CTX_ORE || c == TelemetryFormat.CTX_SOLID;
    }

    public static boolean ore(int c) {
        return c == TelemetryFormat.CTX_DIAMOND || c == TelemetryFormat.CTX_ORE;
    }

    public long volume() {
        return (long) sx * sy * sz;
    }
}
