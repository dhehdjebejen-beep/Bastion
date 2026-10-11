package dev.bastionac.argus.features;

import dev.bastionac.argus.WorldSample;
import dev.bastionac.telemetry.SessionData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static dev.bastionac.telemetry.TelemetryFormat.*;

/**
 * X-ray, judged by what the digging found rather than by any single break.
 *
 * <p><b>Breaks through walls.</b> A block can only be targeted through a
 * face something can be seen through. A break started on a block with every
 * neighbour an opaque full cube (no air, no liquid, no glass, lichen…) is a
 * break vanilla's raycast could not have aimed — wall-mining, nukers,
 * seed-based ore miners. Breaks right after a refused break next door are
 * not counted: the client simply thinks that neighbour is already gone.
 *
 * <p><b>What the tunnels revealed.</b> Every block dug exposes the blocks
 * around it. Digging blind, those are ore at the rate ore occurs in that
 * rock; seeing through the rock, the tunnels bend towards ore and expose it
 * far more often. With a sample of the world around the tunnels (read on the
 * server thread) the expected number is exact: cells revealed × ore density
 * of the hidden rock there; the observed number against it is a Poisson
 * tail test — the analytic form of "how much would a random tunnel through
 * the same rock have found". Without a sample the same idea runs on the
 * 5×5×5 snapshots taken around every ore broken, against the population.
 */
public final class MiningFeatures {

    /** Block-state raw id → CTX_* category (the server answers it from the registry). */
    public interface Classifier {
        int category(int stateRawId);
    }

    private MiningFeatures() {}

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    public static void analyze(SessionData s, FeatureSet out, Classifier cls, WorldSample sample) {
        wallBreaks(s, out);
        if (cls == null) return;
        Map<Long, Long> dugAt = new HashMap<>();
        Map<Long, Integer> dugCat = new HashMap<>();
        int stoneDug = 0, deepStoneDug = 0;
        for (int i = 0; i < s.brokenT.n; i++) {
            if (s.brokenWorld.a[i] != 0) continue;   // ore statistics are about the overworld
            long pos = s.brokenPos.a[i];
            int cat = cls.category(s.brokenState.a[i]);
            dugAt.putIfAbsent(pos, s.brokenT.a[i]);
            dugCat.putIfAbsent(pos, cat);
            if (cat == CTX_STONE) {
                stoneDug++;
                if (unpackY(pos) < 16) deepStoneDug++;
            }
        }
        discoveries(s, out, dugAt, dugCat, stoneDug, deepStoneDug);
        if (sample != null) revealed(s, out, dugAt, dugCat, sample);
    }

    // ---------------------------------------------------------------- breaks through walls

    static void wallBreaks(SessionData s, FeatureSet out) {
        long starts = 0, walls = 0;
        for (int i = 0; i < s.actionT.n; i++) {
            if (s.actionKind.a[i] != 0) continue;   // START_DESTROY_BLOCK
            if (creativeAt(s, s.actionT.a[i])) continue;
            starts++;
            if (s.actionExposed.a[i] != 0) continue;
            if (recentNeighbourAction(s, i)) continue;   // a refused break next door: the client's view is ahead
            walls++;
        }
        out.rate("mine.wall_breaks", walls, starts);
    }

    private static boolean recentNeighbourAction(SessionData s, int idx) {
        long pos = s.actionPos.a[idx];
        long t = s.actionT.a[idx];
        int x = unpackX(pos), y = unpackY(pos), z = unpackZ(pos);
        for (int j = idx - 1; j >= 0 && t - s.actionT.a[j] <= 3000; j--) {
            long p = s.actionPos.a[j];
            int dx = Math.abs(unpackX(p) - x), dy = Math.abs(unpackY(p) - y), dz = Math.abs(unpackZ(p) - z);
            if (dx + dy + dz <= 2 && p != pos) return true;
        }
        return false;
    }

    private static boolean creativeAt(SessionData s, long t) {
        SessionData.State last = null;
        for (SessionData.State st : s.states) {
            if (st.t > t) break;
            last = st;
        }
        return last != null && (last.gameMode == 1 || last.gameMode == 3);
    }

    // ---------------------------------------------------------------- discoveries from ore snapshots

    static void discoveries(SessionData s, FeatureSet out, Map<Long, Long> dugAt, Map<Long, Integer> dugCat,
                            int stoneDug, int deepStoneDug) {
        int disc = 0, diamondDisc = 0;
        Set<Long> seen = new HashSet<>();
        for (SessionData.OreContext oc : s.oreContexts) {
            if (!seen.add(oc.pos())) continue;
            int ox = unpackX(oc.pos()), oy = unpackY(oc.pos()), oz = unpackZ(oc.pos());
            boolean natural = false, byStone = false, any = false;
            for (int[] f : FACES) {
                int c = oc.at(f[0], f[1], f[2]);
                if (c != CTX_OPEN && c != CTX_LIQUID) continue;
                long np = pack(ox + f[0], oy + f[1], oz + f[2]);
                Long when = dugAt.get(np);
                if (when == null || when > oc.t()) {
                    natural = true;   // open before the player came: anyone could see this ore
                    break;
                }
                any = true;
                Integer cat = dugCat.get(np);
                if (cat != null && !WorldSample.ore(cat)) byStone = true;
            }
            if (natural || !any || !byStone) continue;
            disc++;
            if (oc.at(0, 0, 0) == CTX_DIAMOND && oy < 16) diamondDisc++;
        }
        out.rate("mine.discovery_rate", disc, stoneDug);
        out.rate("mine.diamond_discovery", diamondDisc, deepStoneDug);
    }

    // ---------------------------------------------------------------- revealed vs expected (world sample)

    static void revealed(SessionData s, FeatureSet out, Map<Long, Long> dugAt, Map<Long, Integer> dugCat,
                         WorldSample w) {
        // The rock as it was: dug cells get their recorded state back.
        java.util.function.LongToIntFunction prior = p -> {
            Integer c = dugCat.get(p);
            if (c != null) return c;
            return w.get(unpackX(p), unpackY(p), unpackZ(p));
        };
        // Hidden by nature: opaque, and no neighbour open before the player dug.
        java.util.function.LongPredicate naturallyOpen = p -> {
            if (dugAt.containsKey(p)) return false;
            int c = w.get(unpackX(p), unpackY(p), unpackZ(p));
            return c == CTX_OPEN || c == CTX_LIQUID;
        };
        // Ore density of hidden rock in the sampled band.
        long hiddenOpaque = 0, hiddenOre = 0, hiddenDiamond = 0;
        for (int dx = 1; dx < w.sx - 1; dx++) {
            for (int dy = 1; dy < w.sy - 1; dy++) {
                for (int dz = 1; dz < w.sz - 1; dz++) {
                    int x = w.x0 + dx, y = w.y0 + dy, z = w.z0 + dz;
                    long p = pack(x, y, z);
                    int c = prior.applyAsInt(p);
                    if (!WorldSample.opaque(c)) continue;
                    boolean hidden = true;
                    for (int[] f : FACES) {
                        if (naturallyOpen.test(pack(x + f[0], y + f[1], z + f[2]))) {
                            hidden = false;
                            break;
                        }
                    }
                    if (!hidden) continue;
                    hiddenOpaque++;
                    if (WorldSample.ore(c)) hiddenOre++;
                    if (c == CTX_DIAMOND) hiddenDiamond++;
                }
            }
        }
        if (hiddenOpaque < 500) return;
        double pOre = (double) hiddenOre / hiddenOpaque;
        double pDiamond = (double) hiddenDiamond / hiddenOpaque;

        // Cells each dug stone block exposed for the first time, in the order they were dug.
        java.util.List<Long> order = new java.util.ArrayList<>(dugAt.keySet());
        order.sort(java.util.Comparator.comparingLong(dugAt::get));
        Set<Long> revealedCells = new HashSet<>();
        int oreFound = 0, diamondFound = 0;
        for (long b : order) {
            Integer bc = dugCat.get(b);
            if (bc == null || bc != CTX_STONE) continue;   // a revealing dig is through rock, not along a vein
            long tb = dugAt.get(b);
            int bx = unpackX(b), by = unpackY(b), bz = unpackZ(b);
            for (int[] f : FACES) {
                long n = pack(bx + f[0], by + f[1], bz + f[2]);
                if (revealedCells.contains(n)) continue;
                Long tn = dugAt.get(n);
                if (tn != null && tn < tb) continue;   // already dug: nothing to reveal
                int c = prior.applyAsInt(n);
                if (c == WorldSample.UNKNOWN || !WorldSample.opaque(c)) continue;
                boolean wasHidden = true;
                int nx = unpackX(n), ny = unpackY(n), nz = unpackZ(n);
                for (int[] g : FACES) {
                    long m = pack(nx + g[0], ny + g[1], nz + g[2]);
                    Long tm = dugAt.get(m);
                    if (naturallyOpen.test(m) || (tm != null && tm < tb)) {
                        wasHidden = false;
                        break;
                    }
                }
                if (!wasHidden) continue;
                revealedCells.add(n);
                if (WorldSample.ore(c)) oreFound++;
                if (c == CTX_DIAMOND) diamondFound++;
            }
        }
        double n = revealedCells.size();
        if (n < 50) return;
        out.poisson("mine.reveal_z", oreFound, n * pOre);
        if (pDiamond > 0) out.poisson("mine.diamond_reveal_z", diamondFound, n * pDiamond);
        // The raw counts behind the two scores, for the report.
        FeatureSet.Acc d = out.acc("mine.reveal_detail");
        d.samples.add(oreFound);
        d.samples.add(n * pOre);
        d.samples.add(diamondFound);
        d.samples.add(n * pDiamond);
        d.samples.add(n);
    }

    // ---------------------------------------------------------------- BlockPos packing (vanilla's layout)

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int unpackX(long p) {
        return (int) (p >> 38);
    }

    public static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }
}
