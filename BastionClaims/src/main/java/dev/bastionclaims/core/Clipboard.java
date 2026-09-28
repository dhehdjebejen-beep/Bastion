package dev.bastionclaims.core;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory copy of a cuboid of blocks, plus where it sat relative to the
 * player who copied it. Pasting puts the corner back at the same offset from
 * wherever the player stands, so what you see when you copy is what you get.
 *
 * <p>Rotation and mirroring transform both the layout <em>and</em> every block
 * state, so stairs, logs, doors and banners keep facing the right way.
 */
public final class Clipboard {

    private static final Map<UUID, Clipboard> BY_PLAYER = new ConcurrentHashMap<>();

    public final int sizeX, sizeY, sizeZ;
    /** Offset of the min corner from the anchor (the copying player's position). */
    public final int offX, offY, offZ;
    private final BlockState[] states;
    /**
     * Block-entity contents, only in the private capture of a move — the
     * things that leave the world and must arrive a few blocks over. The
     * player's buffer (copy/cut/paste) never carries them: a copied chest of
     * diamonds pasted ten times would be ten chests of diamonds. Null when
     * there is nothing to carry.
     */
    private final NbtCompound[] data;

    private Clipboard(int sizeX, int sizeY, int sizeZ, int offX, int offY, int offZ, BlockState[] states) {
        this(sizeX, sizeY, sizeZ, offX, offY, offZ, states, null);
    }

    private Clipboard(int sizeX, int sizeY, int sizeZ, int offX, int offY, int offZ, BlockState[] states, NbtCompound[] data) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.offX = offX;
        this.offY = offY;
        this.offZ = offZ;
        this.states = states;
        this.data = data;
    }

    public boolean hasData() {
        return data != null;
    }

    /** Contents at a 0-based cell, or null. */
    public NbtCompound dataAt(int x, int y, int z) {
        if (data == null || x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return null;
        return data[index(x, y, z, sizeX, sizeZ)];
    }

    // ------------------------------------------------------------------ store

    public static void put(UUID player, Clipboard c) {
        BY_PLAYER.put(player, c);
    }

    public static Clipboard get(UUID player) {
        return BY_PLAYER.get(player);
    }

    public static void forget(UUID player) {
        BY_PLAYER.remove(player);
    }

    // ------------------------------------------------------------------ capture

    /** Reads a cuboid out of the world. {@code anchor} is usually the player's block position. */
    public static Clipboard capture(ServerWorld world, int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ, BlockPos anchor) {
        return capture(world, minX, minY, minZ, maxX, maxY, maxZ, anchor, false);
    }

    /**
     * {@code takeContents}: for a move. The contents are read out and the
     * containers emptied on the spot, in the same tick (see
     * {@link WorldEdit#takeContents}).
     */
    public static Clipboard capture(ServerWorld world, int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ, BlockPos anchor, boolean takeContents) {
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        BlockState[] states = new BlockState[sx * sy * sz];
        NbtCompound[] data = takeContents ? new NbtCompound[states.length] : null;
        boolean any = false;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    m.set(minX + x, minY + y, minZ + z);
                    BlockState st = world.getBlockState(m);
                    states[index(x, y, z, sx, sz)] = st;
                    if (data == null || !st.hasBlockEntity()) continue;
                    NbtCompound nbt = WorldEdit.takeContents(world, m.toImmutable(), st);
                    if (nbt == null) continue;
                    data[index(x, y, z, sx, sz)] = nbt;
                    any = true;
                }
            }
        }
        return new Clipboard(sx, sy, sz,
                minX - anchor.getX(), minY - anchor.getY(), minZ - anchor.getZ(), states, any ? data : null);
    }

    // ------------------------------------------------------------------ access

    private static int index(int x, int y, int z, int sizeX, int sizeZ) {
        return (y * sizeZ + z) * sizeX + x;
    }

    /** State at a 0-based cell, or {@code null} when outside the buffer. */
    public BlockState at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return null;
        return states[index(x, y, z, sizeX, sizeZ)];
    }

    public long volume() {
        return (long) sizeX * sizeY * sizeZ;
    }

    // ------------------------------------------------------------------ transforms

    /**
     * Rotates around the anchor. A relative point {@code (rx, rz)} maps to
     * {@code (-rz, rx)} per 90° step, which is the same convention
     * {@link BlockRotation#CLOCKWISE_90} applies to block states.
     */
    public Clipboard rotated(BlockRotation rotation) {
        if (rotation == BlockRotation.NONE) return this;
        int steps = switch (rotation) {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
        Clipboard out = this;
        for (int i = 0; i < steps; i++) out = out.rotatedOnce();
        return out;
    }

    /**
     * X offset after one 90° clockwise step. Split out as pure integer maths so
     * the geometry can be unit-tested without a Minecraft world: four steps must
     * return the buffer to exactly where it started.
     */
    public static int rotatedOffX(int offZ, int sizeZ) {
        return -(offZ + sizeZ - 1);
    }

    /** Z offset after one 90° clockwise step. */
    public static int rotatedOffZ(int offX) {
        return offX;
    }

    private Clipboard rotatedOnce() {
        int nSizeX = sizeZ, nSizeY = sizeY, nSizeZ = sizeX;
        int nOffX = rotatedOffX(offZ, sizeZ);
        int nOffZ = rotatedOffZ(offX);
        BlockState[] out = new BlockState[nSizeX * nSizeY * nSizeZ];

        for (int ny = 0; ny < nSizeY; ny++) {
            for (int nz = 0; nz < nSizeZ; nz++) {
                for (int nx = 0; nx < nSizeX; nx++) {
                    // Relative coords of the destination cell, then invert the
                    // rotation to find which source cell feeds it.
                    int rx = nOffX + nx;
                    int rz = nOffZ + nz;
                    int srcRx = rz;
                    int srcRz = -rx;
                    int sx = srcRx - offX;
                    int sz = srcRz - offZ;
                    BlockState st = at(sx, ny, sz);
                    out[index(nx, ny, nz, nSizeX, nSizeZ)] =
                            st == null ? Blocks.AIR.getDefaultState() : st.rotate(BlockRotation.CLOCKWISE_90);
                }
            }
        }
        return new Clipboard(nSizeX, nSizeY, nSizeZ, nOffX, offY, nOffZ, out);
    }

    /** Mirrors across the anchor on the X ({@code FRONT_BACK}) or Z ({@code LEFT_RIGHT}) axis. */
    public Clipboard mirrored(BlockMirror mirror) {
        if (mirror == BlockMirror.NONE) return this;
        boolean flipX = mirror == BlockMirror.FRONT_BACK;
        BlockState[] out = new BlockState[states.length];
        int nOffX = flipX ? -(offX + sizeX - 1) : offX;
        int nOffZ = flipX ? offZ : -(offZ + sizeZ - 1);

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int sx = flipX ? sizeX - 1 - x : x;
                    int sz = flipX ? z : sizeZ - 1 - z;
                    BlockState st = at(sx, y, sz);
                    out[index(x, y, z, sizeX, sizeZ)] =
                            st == null ? Blocks.AIR.getDefaultState() : st.mirror(mirror);
                }
            }
        }
        return new Clipboard(sizeX, sizeY, sizeZ, nOffX, offY, nOffZ, out);
    }

    /**
     * Flips top-to-bottom. Block states are left untouched: there is no vanilla
     * transform for "upside down", so stairs and slabs keep their half.
     */
    public Clipboard flippedVertically() {
        BlockState[] out = new BlockState[states.length];
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    out[index(x, y, z, sizeX, sizeZ)] = at(x, sizeY - 1 - y, z);
                }
            }
        }
        return new Clipboard(sizeX, sizeY, sizeZ, offX, -(offY + sizeY - 1), offZ, out);
    }
}
