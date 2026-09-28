package dev.bastionac.core;

import dev.bastionac.BastionAC;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Statistical X-ray detection — observe-only, EDR-fed.
 *
 * <p>X-ray cannot be caught by a single packet: the cheat's whole point is
 * that every individual block break looks perfectly legitimate. What it
 * cannot hide is the <em>statistical shape</em> of a mining session, so
 * this tracker measures three independent signals over a session and feeds
 * each confirmed one into the attack-chain detector as a PROBE — never a
 * ban, never even an alert on its own (unless the chain already arms):
 *
 * <ol>
 *   <li><b>Hidden-ore break</b> — a valuable overworld ore broken with no
 *       air adjacent on any of the 6 sides at the moment of the break. A
 *       vanilla client has no way to even know that ore exists: every
 *       legitimate discovery path (tunneling, caving, following a revealed
 *       vein) produces air-adjacency first. Ancient debris is excluded —
 *       blind bed-bombing in the Nether is the legitimate meta and produces
 *       hidden debris by the dozen.</li>
 *   <li><b>Pure-ore session</b> — 5+ valuable ores with under 20 stone-like
 *       blocks mined, of which at least 3 were hidden. A cave explorer
 *       mines exposed ores and produces stone alongside; the hidden
 *       requirement is what separates a lucky spelunker from a tracer.</li>
 *   <li><b>High ratio</b> — diamond-to-stone-like ratio above 0.12 once
 *       100+ stone-like blocks have been mined. Natural mining sits around
 *       0.05-0.08; a tunneling X-ray sees the diamonds through the wall and
 *       skips everything worthless, pushing the ratio up regardless of the
 *       hidden signal (which tunneling legitimately produces by removing
 *       the cover first).</li>
 * </ol>
 *
 * <p>Each hidden break is one PROBE (weight 1); confirmed pure-ore and
 * high-ratio verdicts are worth 3 and 4 probes respectively. Twenty chain
 * points arm the engine's stricter thresholds; a burned chain (repeated
 * confirmed pattern) escalates to the top of the ban ladder via
 * {@link BanManager#chainBan} — a temp-ban, never an automatic permaban.
 * That is the EDR philosophy: single triggers warn, correlated chains act.
 */
public final class XrayTracker {

    /** Valuable-ore count that starts a pure-ore verdict. */
    static final int PURE_ORE_MIN = 5;
    /** Stone-like cap for a pure-ore verdict (fewer than this = no digging happened). */
    static final int PURE_ORE_MAX_STONE = 20;
    /** Hidden breaks required inside a pure-ore verdict. */
    static final int PURE_ORE_MIN_HIDDEN = 3;
    /** Ore-to-stone-like ratio that confirms a high-ratio verdict. */
    static final double HIGH_RATIO = 0.35;
    /** Stone-like count before the ratio is judged at all. */
    static final int RATIO_STONE_BASELINE = 100;
    /** Stone-like break that freezes the "honest prefix" for the purity-drop verdict. */
    static final int HONEST_PREFIX_STONE = 60;
    /** Window of stone-like blocks after the prefix in which the drop is judged. */
    static final int DROP_WINDOW_STONE = 40;
    /** Valuable ores inside the window that confirm the drop. */
    static final int DROP_MIN_ORES = 6;

    /** Pure verdict for tests. */
    public static final String KIND_HIDDEN = "hidden-ore";
    public static final String KIND_PURE = "pure-ore";
    public static final String KIND_RATIO = "high-ratio";
    public static final String KIND_DROP = "purity-drop";

    private XrayTracker() {}

    // ------------------------------------------------------------------ classification

    /**
     * Ore families the tracker counts as valuable: diamond and emerald are
     * the crown jewels, but a tracer hoovers iron, gold, copper, lapis,
     * redstone and coal too — an iron-and-diamond mix is the most common
     * xray shape and would be invisible to a diamond-only ratio. Ancient
     * debris is excluded (Nether bed-bombing is the legitimate meta).
     */
    public enum OreFamily { DIAMOND, EMERALD, OTHER_VALUABLE, OTHER }

    /**
     * Classifies a broken block state into the families this tracker counts.
     * Pure function over the state so unit tests can drive it with fake
     * classifiers — see {@link #classify(boolean, boolean)}.
     */
    public static OreFamily classify(BlockState state) {
        if (state == null) return OreFamily.OTHER;
        if (state.isIn(BlockTags.DIAMOND_ORES)) return OreFamily.DIAMOND;
        if (state.isIn(BlockTags.EMERALD_ORES)) return OreFamily.EMERALD;
        if (state.isIn(BlockTags.IRON_ORES) || state.isIn(BlockTags.COPPER_ORES)
                || state.isIn(BlockTags.REDSTONE_ORES) || state.isIn(BlockTags.LAPIS_ORES)
                || state.isIn(BlockTags.COAL_ORES) || state.isIn(BlockTags.GOLD_ORES)) {
            return OreFamily.OTHER_VALUABLE;
        }
        return OreFamily.OTHER;
    }

    /**
     * Pure classification from tag booleans (test-friendly form of
     * {@link #classify(BlockState)}).
     */
    public static OreFamily classify(boolean diamondOre, boolean emeraldOre) {
        if (diamondOre) return OreFamily.DIAMOND;
        if (emeraldOre) return OreFamily.EMERALD;
        return OreFamily.OTHER;
    }

    /**
     * Pure classification with the extended families (test-friendly).
     */
    public static OreFamily classify(boolean diamondOre, boolean emeraldOre, boolean otherValuable) {
        if (diamondOre) return OreFamily.DIAMOND;
        if (emeraldOre) return OreFamily.EMERALD;
        if (otherValuable) return OreFamily.OTHER_VALUABLE;
        return OreFamily.OTHER;
    }

    /**
     * True for stone-like blocks: the ground an honest miner moves through
     * and the material every ore spawns inside of. Counting only these (and
     * not dirt, gravel or netherrack) keeps the ratio about the decision
     * "skip the worthless, take the valuable" that defines X-ray.
     */
    public static boolean isStoneLike(BlockState state) {
        return state != null && state.isIn(BlockTags.STONE_ORE_REPLACEABLES);
    }

    // ------------------------------------------------------------------ verdicts (pure)

    /**
     * Pure-ore verdict. {@code hidden} must have been confirmed per-ore at
     * break time; this only aggregates.
     */
    public static boolean isPureOreSession(int valuableOres, int stoneLike, int hiddenOres) {
        return valuableOres >= PURE_ORE_MIN
                && stoneLike < PURE_ORE_MAX_STONE
                && hiddenOres >= PURE_ORE_MIN_HIDDEN;
    }

    /** High-ratio verdict over the session counters (all valuable ores). */
    public static boolean isHighRatio(int valuableOres, int stoneLike) {
        return stoneLike >= RATIO_STONE_BASELINE
                && (double) valuableOres / stoneLike > HIGH_RATIO;
    }

    /**
     * Purity-drop verdict: an honest prefix (60+ stone-like) followed by a
     * window of near-pure valuable mining. Pure over the window counters.
     */
    public static boolean isPurityDrop(int oresInWindow, int stoneInWindow) {
        return oresInWindow >= DROP_MIN_ORES
                && stoneInWindow <= DROP_WINDOW_STONE;
    }

    // ------------------------------------------------------------------ live path

    /**
     * Records one confirmed block break (accepted by WorldChecks — the
     * STOP_DESTROY_BLOCK path or the insta-mine START path) and updates the
     * session counters. Returns the ore family when the break was a hidden
     * valuable ore, or null when nothing noteworthy happened.
     */
    public static OreFamily noteBreak(ServerPlayerEntity player, PlayerData d,
                                       BlockState state, BlockPos pos, boolean hasAdjacentAir) {
        if (state == null) return null;
        if (isStoneLike(state)) {
            d.xrayStoneLike++;
            judgeRatio(player, d);
            judgeDrop(player, d);
            return null;
        }
        OreFamily fam = classify(state);
        if (fam == OreFamily.OTHER) return null;

        // Nether dimension guard is implicit: tracked ore families do not
        // generate there, and ancient debris never matches those tags, so
        // the bed-bombing meta is excluded by classification alone.
        d.xrayValuable++;
        d.xrayWindowOres++;
        if (!hasAdjacentAir) {
            d.xrayHidden++;
            d.xrayHiddenOreFamily = fam;
            feed(player, d, KIND_HIDDEN, weightOf(fam));
        }
        if (isPureOreSession(d.xrayValuable, d.xrayStoneLike, d.xrayHidden)
                && d.xrayValuable == PURE_ORE_MIN) {
            feed(player, d, KIND_PURE, 3);
        }
        judgeDrop(player, d);
        return fam;
    }

    private static void judgeRatio(ServerPlayerEntity player, PlayerData d) {
        // Fire once per threshold crossing, not per block: compare against
        // the baseline so the verdict lands on the first stone-like break
        // that completes the evidence window.
        if (d.xrayStoneLike == RATIO_STONE_BASELINE && isHighRatio(d.xrayValuable, d.xrayStoneLike)) {
            feed(player, d, KIND_RATIO, 4);
        }
    }

    /**
     * Purity-drop judge: once an honest prefix is frozen, the following
     * stone/ore window is measured. On confirmation the window resets —
     * the verdict fires once per episode, not per block.
     */
    private static void judgeDrop(ServerPlayerEntity player, PlayerData d) {
        if (d.xrayPrefixFrozen) {
            if (d.xrayWindowStone >= HONEST_PREFIX_STONE) {
                // The window is already larger than the honest prefix itself:
                // sustained mixed mining, not a drop. Refreeze and move on.
                d.xrayPrefixFrozen = false;
                return;
            }
            if (d.xrayWindowStone >= DROP_WINDOW_STONE || d.xrayWindowOres >= DROP_MIN_ORES * 3) {
                if (isPurityDrop(d.xrayWindowOres, d.xrayWindowStone)) {
                    feed(player, d, KIND_DROP, 5);
                    d.xrayWindowStone = 0;
                    d.xrayWindowOres = 0;
                    d.xrayPrefixFrozen = false;
                } else if (d.xrayWindowStone >= DROP_WINDOW_STONE) {
                    // Window closed without a verdict: honest continuation.
                    d.xrayPrefixFrozen = false;
                }
            }
        } else if (d.xrayStoneLike >= HONEST_PREFIX_STONE) {
            d.xrayPrefixFrozen = true;
            d.xrayWindowStone = 0;
            d.xrayWindowOres = 0;
        }
    }

    private static void feed(ServerPlayerEntity player, PlayerData d, String kind, int probes) {
        for (int i = 0; i < probes; i++) {
            AttackChainDetector.probe(new ChainPlayerAdapter(player), "xray",
                    kind + " (руд " + d.xrayValuable + ", камень " + d.xrayStoneLike
                            + ", скрытых " + d.xrayHidden + ")");
        }
    }

    private static int weightOf(OreFamily fam) {
        // Emerald is biome-gated and appears in clusters a caver can luck
        // into exposed; diamond hidden behind full stone is the strongest
        // single signal in the game.
        return fam == OreFamily.DIAMOND ? 1 : 1;
    }

    /** Adjacent-air probe around a position, server-authoritative. */
    public static boolean hasAdjacentAir(net.minecraft.world.WorldAccess world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (world.getBlockState(pos.offset(dir)).isAir()) return true;
        }
        return false;
    }

    /** Called on join: fresh session counters per connection. */
    public static void attach(ServerPlayerEntity player, PlayerData d) {
        d.xrayStoneLike = 0;
        d.xrayValuable = 0;
        d.xrayHidden = 0;
        d.xrayHiddenOreFamily = null;
        d.xrayPrefixFrozen = false;
        d.xrayWindowStone = 0;
        d.xrayWindowOres = 0;
    }

    /** Session summary for the player card UI. */
    public static String summary(PlayerData d) {
        if (d == null) return "нет данных";
        return "руд=" + d.xrayValuable + ", камня=" + d.xrayStoneLike
                + ", скрытых=" + d.xrayHidden
                + (isHighRatio(d.xrayValuable, d.xrayStoneLike) ? " [ratio]" : "");
    }
}
