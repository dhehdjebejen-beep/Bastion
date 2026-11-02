package dev.bastionac.core;

import dev.bastionac.BastionAC;
import dev.bastionac.config.ACConfig;
import dev.bastionac.mixin.ChunkLoadingManagerAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Anti-ESP (3.6): another player, or an item lying on the ground, is sent to
 * a client only while that client could see it.
 *
 * <p>Vanilla tracking sends every player and every dropped item within range,
 * through any number of walls; ESP draws them. Here each pair is judged every
 * few ticks with rays from the viewer's eyes, blocked only by opaque full
 * cubes (glass, leaves, fences, slabs and water let the sight through, as
 * they do for the eye):
 * <ul>
 *   <li><b>players</b> — inside a wide view cone and with a clear line to one
 *       of five points of the hitbox, or within {@code espAlwaysVisibleRadius};</li>
 *   <li><b>items</b> — a clear line to the item or just above it (no cone: an
 *       item behind you is out of sight anyway, and a turn would only make
 *       them pop), or within {@code espItemAlwaysVisibleRadius}.</li>
 * </ul>
 * Something that comes into sight is sent at once; something that leaves it
 * is removed only after {@code espHideDelayTicks}, so a corner or a head turn
 * does not flicker.
 *
 * <p>The decision goes into vanilla's own tracking: the hook on
 * {@code EntityTracker.updateTrackedStatus} refuses a pair the shield hides,
 * and this class asks vanilla to re-judge a pair when its answer changes.
 * Nothing else changes — the tab list, chat and sounds stay as they are.
 * Spectators see everything.
 */
public final class EspShield {

    /** viewer → (target entity id → last tick the target was in sight). */
    private static final Map<UUID, Map<Integer, Long>> SEEN = new HashMap<>();
    /** viewer → (target entity id → whether the shield currently lets it through). */
    private static final Map<UUID, Map<Integer, Boolean>> SHOWN = new HashMap<>();

    private EspShield() {}

    static ACConfig.General general() {
        ACConfig cfg = BastionAC.config();
        return cfg == null ? null : cfg.general;
    }

    static boolean playersOn() {
        ACConfig.General g = general();
        return g != null && g.espShield && BastionAC.enabled();
    }

    static boolean itemsOn() {
        ACConfig.General g = general();
        return g != null && g.espShieldItems && BastionAC.enabled();
    }

    /** Viewers the shield never hides anything from: spectators see through walls in vanilla too. */
    static boolean exempt(ServerPlayerEntity viewer) {
        return viewer.isSpectator() || minigameWorld(viewer);
    }

    /**
     * Minigame arenas show everyone, as vanilla does. On a big Hunger Games map
     * watching an opponent across a valley is half the game, and the shield
     * hid them; Прятки have their own rules about who sees whom.
     */
    static boolean minigameWorld(ServerPlayerEntity viewer) {
        String ns = viewer.getEntityWorld().getRegistryKey().getValue().getNamespace();
        return ns.equals("hideseek") || ns.equals("hungergames");
    }

    /**
     * A pair another mod shows through walls on purpose — a minigame that makes
     * the last hiders glow for the seekers. Hiding them would cancel the glow:
     * a target that is not sent cannot be drawn. Asked every judgement; must be
     * cheap and must not throw (a throw counts as "no").
     */
    private static volatile BiPredicate<ServerPlayerEntity, Entity> reveal = (viewer, target) -> false;

    public static void setReveal(BiPredicate<ServerPlayerEntity, Entity> predicate) {
        reveal = predicate == null ? (viewer, target) -> false : predicate;
    }

    static boolean revealed(ServerPlayerEntity viewer, Entity target) {
        try {
            return reveal.test(viewer, target);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** For the tracker hook: is this an entity the shield judges? */
    public static boolean judges(Entity entity) {
        return entity instanceof ServerPlayerEntity ? playersOn() : entity instanceof ItemEntity && itemsOn();
    }

    /**
     * Whether vanilla may track this target for this viewer. Called from the
     * tracker hook; answers from the last judgement, judging now if the pair
     * is new.
     */
    public static boolean allowed(ServerPlayerEntity viewer, Entity target) {
        if (viewer == target || exempt(viewer) || !judges(target)) return true;
        Map<Integer, Boolean> shown = SHOWN.computeIfAbsent(viewer.getUuid(), k -> new HashMap<>());
        Boolean s = shown.get(target.getId());
        if (s == null) {
            boolean vis = revealed(viewer, target) || inSight(viewer, target, general());
            if (vis) SEEN.computeIfAbsent(viewer.getUuid(), k -> new HashMap<>()).put(target.getId(), BastionAC.serverTick());
            shown.put(target.getId(), vis);
            return vis;
        }
        return s;
    }

    /** The periodic judgement: players every {@code espCheckIntervalTicks}, items every {@code espItemCheckIntervalTicks}. */
    public static void tick(MinecraftServer server, long tick) {
        ACConfig.General g = general();
        if (g == null) return;
        if (!playersOn() && !itemsOn()) {
            if (!SHOWN.isEmpty()) releaseAll(server);
            return;
        }
        boolean players = playersOn() && tick % Math.max(1, g.espCheckIntervalTicks) == 0;
        boolean items = itemsOn() && tick % Math.max(1, g.espItemCheckIntervalTicks) == 0;
        if (!players && !items) return;
        for (ServerWorld world : server.getWorlds()) {
            List<ServerPlayerEntity> all = world.getPlayers();
            for (ServerPlayerEntity viewer : all) {
                Map<Integer, Long> seen = SEEN.computeIfAbsent(viewer.getUuid(), k -> new HashMap<>());
                Map<Integer, Boolean> shown = SHOWN.computeIfAbsent(viewer.getUuid(), k -> new HashMap<>());
                boolean free = exempt(viewer);
                if (players) {
                    for (ServerPlayerEntity target : all) {
                        if (target != viewer) judge(world, viewer, target, free, seen, shown, tick, g);
                    }
                }
                if (items) {
                    double range = Math.min(96, world.getServer().getPlayerManager().getViewDistance() * 16);
                    Set<Integer> present = new HashSet<>();
                    for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class,
                            viewer.getBoundingBox().expand(range, range, range), e -> true)) {
                        present.add(item.getId());
                        judge(world, viewer, item, free, seen, shown, tick, g);
                    }
                    // Items that despawned, were picked up or left range: forget them.
                    shown.keySet().removeIf(id -> !present.contains(id) && !(world.getEntityById(id) instanceof ServerPlayerEntity));
                    seen.keySet().removeIf(id -> !shown.containsKey(id));
                }
            }
        }
    }

    private static void judge(ServerWorld world, ServerPlayerEntity viewer, Entity target, boolean free,
                              Map<Integer, Long> seen, Map<Integer, Boolean> shown, long tick, ACConfig.General g) {
        boolean vis = free || revealed(viewer, target) || inSight(viewer, target, g);
        if (vis) seen.put(target.getId(), tick);
        long last = seen.getOrDefault(target.getId(), Long.MIN_VALUE / 2);
        boolean show = vis || tick - last < g.espHideDelayTicks;
        Boolean before = shown.put(target.getId(), show);
        if (before == null || before != show) recheck(world, viewer, target);
    }

    /** A player left: forget every pair with them. */
    public static void forget(ServerPlayerEntity player) {
        SEEN.remove(player.getUuid());
        SHOWN.remove(player.getUuid());
        for (Map<Integer, Long> m : SEEN.values()) m.remove(player.getId());
        for (Map<Integer, Boolean> m : SHOWN.values()) m.remove(player.getId());
    }

    /** The shield was switched off: every hidden pair is handed back to vanilla. */
    private static void releaseAll(MinecraftServer server) {
        Map<UUID, Map<Integer, Boolean>> was = new HashMap<>(SHOWN);
        SEEN.clear();
        SHOWN.clear();
        for (ServerWorld world : server.getWorlds()) {
            for (ServerPlayerEntity viewer : world.getPlayers()) {
                Map<Integer, Boolean> m = was.get(viewer.getUuid());
                if (m == null) continue;
                for (Map.Entry<Integer, Boolean> e : m.entrySet()) {
                    if (e.getValue()) continue;
                    Entity target = world.getEntityById(e.getKey());
                    if (target != null) recheck(world, viewer, target);
                }
            }
        }
    }

    /** Asks vanilla to decide again whether this viewer tracks this target. */
    private static void recheck(ServerWorld world, ServerPlayerEntity viewer, Entity target) {
        Object tracker = ((ChunkLoadingManagerAccessor) world.getChunkManager().chunkLoadingManager)
                .bastionac$entityTrackers().get(target.getId());
        if (tracker instanceof Recheck r) r.bastionac$recheck(viewer);
    }

    /** Implemented on vanilla's EntityTracker by the tracker mixin. */
    public interface Recheck {
        void bastionac$recheck(ServerPlayerEntity viewer);
    }

    // ------------------------------------------------------------------ sight

    static boolean inSight(ServerPlayerEntity viewer, Entity target, ACConfig.General g) {
        if (viewer.getEntityWorld() != target.getEntityWorld()) return true;
        Vec3d eye = viewer.getEyePos();
        Box box = target.getBoundingBox();
        Vec3d center = box.getCenter();
        ServerWorld world = (ServerWorld) viewer.getEntityWorld();
        if (target instanceof ItemEntity) {
            double near = g.espItemAlwaysVisibleRadius;
            if (eye.squaredDistanceTo(center) <= near * near) return true;
            // The item bobs and its name floats above it: the point half a
            // block up counts too.
            return clear(world, eye, center) || clear(world, eye, center.add(0, 0.5, 0));
        }
        double near = g.espAlwaysVisibleRadius;
        if (eye.squaredDistanceTo(center) <= near * near) return true;
        if (!inCone(viewer.getRotationVec(1.0f), center.subtract(eye), g.espViewConeDegrees)) return false;
        double half = (box.maxX - box.minX) / 2 + 0.3;   // a little wider than the hitbox: reveal early
        Vec3d to = center.subtract(eye);
        Vec3d side = new Vec3d(-to.z, 0, to.x);
        side = side.lengthSquared() < 1.0e-6 ? new Vec3d(half, 0, 0) : side.normalize().multiply(half);
        Vec3d[] points = {
                target.getEyePos(),
                center,
                new Vec3d(center.x, box.minY + 0.1, center.z),
                center.add(side),
                center.subtract(side),
        };
        for (Vec3d p : points) {
            if (clear(world, eye, p)) return true;
        }
        return false;
    }

    /** Whether the direction lies inside a cone of this full angle around the look vector. */
    static boolean inCone(Vec3d look, Vec3d direction, double fullAngleDegrees) {
        if (fullAngleDegrees >= 360) return true;
        double len = direction.length();
        if (len < 1.0e-6) return true;
        double cos = look.dotProduct(direction) / (look.length() * len);
        return cos >= Math.cos(Math.toRadians(fullAngleDegrees / 2.0));
    }

    /**
     * A grid walk from a to b (Amanatides–Woo): false at the first opaque full
     * cube on the way. The cells of both ends do not count; a chunk that is
     * not loaded lets the sight through (never hide on a guess).
     */
    static boolean clear(ServerWorld world, Vec3d a, Vec3d b) {
        int x = MathHelper.floor(a.x), y = MathHelper.floor(a.y), z = MathHelper.floor(a.z);
        int ex = MathHelper.floor(b.x), ey = MathHelper.floor(b.y), ez = MathHelper.floor(b.z);
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : (sx > 0 ? (x + 1 - a.x) : (a.x - x)) * tdx;
        double tmy = dy == 0 ? Double.MAX_VALUE : (sy > 0 ? (y + 1 - a.y) : (a.y - y)) * tdy;
        double tmz = dz == 0 ? Double.MAX_VALUE : (sz > 0 ? (z + 1 - a.z) : (a.z - z)) * tdz;
        WorldChunk chunk = null;
        for (int steps = 0; steps < 1024; steps++) {
            if (tmx < tmy && tmx < tmz) { x += sx; tmx += tdx; }
            else if (tmy < tmz) { y += sy; tmy += tdy; }
            else { z += sz; tmz += tdz; }
            if (tmx > 1 && tmy > 1 && tmz > 1) return true;
            if (x == ex && y == ey && z == ez) return true;
            if (y < world.getBottomY() || y > world.getTopYInclusive()) continue;
            int cx = x >> 4, cz = z >> 4;
            if (chunk == null || chunk.getPos().x != cx || chunk.getPos().z != cz) {
                chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
            }
            BlockState s = chunk.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
            if (s.isOpaqueFullCube()) return false;
        }
        return true;
    }
}
