package dev.bastionclaims.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Transient per-player wand selection (two corners). Not persisted. */
public final class Selection {

    public String dim;
    public Integer x1, y1, z1;
    public Integer x2, y2, z2;

    private static final Map<UUID, Selection> BY_PLAYER = new ConcurrentHashMap<>();

    public static Selection of(UUID player) {
        return BY_PLAYER.computeIfAbsent(player, k -> new Selection());
    }

    public static void clear(UUID player) {
        BY_PLAYER.remove(player);
    }

    /** Setting a corner in a different world resets the other corner. */
    public void setPos1(String dimension, int x, int y, int z) {
        if (dim != null && !dim.equals(dimension)) { x2 = y2 = z2 = null; }
        dim = dimension; x1 = x; y1 = y; z1 = z;
    }

    public void setPos2(String dimension, int x, int y, int z) {
        if (dim != null && !dim.equals(dimension)) { x1 = y1 = z1 = null; }
        dim = dimension; x2 = x; y2 = y; z2 = z;
    }

    public boolean complete() {
        return dim != null && x1 != null && y1 != null && z1 != null
                && x2 != null && y2 != null && z2 != null;
    }

    // Size/volume of the current box. Only valid once complete() is true.
    public int sizeX() { return Math.abs(x2 - x1) + 1; }
    public int sizeY() { return Math.abs(y2 - y1) + 1; }
    public int sizeZ() { return Math.abs(z2 - z1) + 1; }
    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    private Selection() {}
}
