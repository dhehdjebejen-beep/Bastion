package dev.bastionclaims.core;

import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.model.Claim;
import dev.bastionclaims.util.Fmt;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

/**
 * Shared claim-creation logic (validation + limits + overlap) used by both the
 * {@code /claim create} command and the GUI "create" buttons, so the two never
 * drift apart. Returns a ready-to-send, colourised message.
 */
public final class ClaimService {

    private static final int MAX_NAME_LEN = 24;

    public record Result(boolean ok, String message, Claim claim) {}

    private static ClaimConfig cfg() {
        return BastionClaims.config();
    }

    /**
     * Validates the player's current wand selection and creates a claim from it.
     * When {@code fullHeight} is true the claim spans the whole world column and
     * is costed by footprint area; otherwise it is a 3D cuboid costed by volume.
     */
    public static Result tryCreate(ServerPlayerEntity p, String rawName, boolean fullHeight) {
        String owner = p.getGameProfile().name();
        // Strip colour markers before storing: the name is rendered through
        // Fmt.color everywhere it appears, so "&c&l[SERVER]" would show up as a
        // formatted system line to everyone entering the claim.
        String name = Fmt.sanitize(rawName);
        if (name.isEmpty()) return fail("create.noName");
        if (name.length() > MAX_NAME_LEN) name = name.substring(0, MAX_NAME_LEN).trim();
        if (name.isEmpty()) return fail("create.noName");

        Selection sel = Selection.of(p.getUuid());
        if (!sel.complete()) return fail("sel.none");

        int minX = Math.min(sel.x1, sel.x2), maxX = Math.max(sel.x1, sel.x2);
        int minY = Math.min(sel.y1, sel.y2), maxY = Math.max(sel.y1, sel.y2);
        int minZ = Math.min(sel.z1, sel.z2), maxZ = Math.max(sel.z1, sel.z2);
        String dim = sel.dim;

        MinecraftServer server = p.getEntityWorld().getServer();
        ServerWorld world = resolve(server, dim);
        if (fullHeight && world != null) {
            minY = world.getBottomY();
            maxY = world.getBottomY() + world.getHeight() - 1;
        }

        if (ClaimManager.nameTaken(owner, name)) return fail("create.nameTaken");

        int sideX = maxX - minX + 1, sideZ = maxZ - minZ + 1;
        if (Math.min(sideX, sideZ) < cfg().minSideLength) {
            return fail("create.tooSmall", cfg().minSideLength);
        }

        long cost = fullHeight ? (long) sideX * sideZ : (long) sideX * (maxY - minY + 1) * sideZ;

        boolean unlimited = ProtectionService.isOp(p) && cfg().opBypassLimits;
        if (!unlimited) {
            int hours = ProtectionService.playHours(p);
            int maxClaims = cfg().maxClaimsFor(hours);
            if (ClaimManager.countOf(p.getUuid()) >= maxClaims) {
                return failRaw(cfg().message("create.limitClaims", maxClaims, hours)
                        + "\n" + cfg().message("limit.progress"));
            }
            long maxBlocks = cfg().maxBlocksFor(hours);
            if (cost > maxBlocks) return fail("create.limitBlocks", cost, maxBlocks);
        }

        Claim overlap = ClaimManager.firstOverlap(dim, minX, minY, minZ, maxX, maxY, maxZ, null);
        if (overlap != null) return fail("create.overlap", overlap.name, overlap.owner);

        Claim c = ClaimManager.create(owner, name, dim, fullHeight, minX, minY, minZ, maxX, maxY, maxZ);
        Selection.clear(p.getUuid());
        String msg = cfg().message(fullHeight ? "create.success2d" : "create.success3d", c.name, cost);
        return new Result(true, msg, c);
    }

    /**
     * Adds the player's current wand selection to {@code c} as another zone, so
     * one claim can be several cuboids joined together instead of eating a claim
     * slot for every wing of a base.
     */
    public static Result tryAddZone(ServerPlayerEntity p, Claim c) {
        Selection sel = Selection.of(p.getUuid());
        if (!sel.complete()) return fail("sel.none");
        if (!sel.dim.equals(c.dim)) return failRaw(cfg().message("sel.diffWorld"));

        if (c.zoneCount() >= cfg().maxZonesPerClaim) {
            return failRaw(cfg().message("zone.limit", cfg().maxZonesPerClaim));
        }

        int minX = Math.min(sel.x1, sel.x2), maxX = Math.max(sel.x1, sel.x2);
        int minY = Math.min(sel.y1, sel.y2), maxY = Math.max(sel.y1, sel.y2);
        int minZ = Math.min(sel.z1, sel.z2), maxZ = Math.max(sel.z1, sel.z2);

        // A full-height claim stays full height in every zone, or its cost model
        // (area rather than volume) would stop making sense.
        ServerWorld world = resolve(p.getEntityWorld().getServer(), c.dim);
        if (c.fullHeight && world != null) {
            minY = world.getBottomY();
            maxY = world.getBottomY() + world.getHeight() - 1;
        }

        int sideX = maxX - minX + 1, sideZ = maxZ - minZ + 1;
        if (Math.min(sideX, sideZ) < cfg().minSideLength) {
            return fail("create.tooSmall", cfg().minSideLength);
        }

        Claim.Box zone = Claim.Box.of(minX, minY, minZ, maxX, maxY, maxZ);
        int gap = c.distanceToNearestZone(zone);
        if (gap > cfg().zoneMaxDistance) {
            return failRaw(cfg().message("zone.tooFar", gap, cfg().zoneMaxDistance));
        }

        Claim overlap = ClaimManager.firstOverlap(c.dim, minX, minY, minZ, maxX, maxY, maxZ, null);
        if (overlap != null) {
            return overlap.id.equals(c.id)
                    ? failRaw(cfg().message("zone.overlapSelf"))
                    : fail("create.overlap", overlap.name, overlap.owner);
        }

        boolean unlimited = ProtectionService.isOp(p) && cfg().opBypassLimits;
        if (!unlimited) {
            int hours = ProtectionService.playHours(p);
            long maxBlocks = cfg().maxBlocksFor(hours);
            long after = c.cost() + (c.fullHeight ? zone.area() : zone.volume());
            if (after > maxBlocks) return fail("create.limitBlocks", after, maxBlocks);
        }

        ClaimManager.addZone(c, zone);
        Selection.clear(p.getUuid());
        return new Result(true, cfg().message("zone.added", c.name, c.zoneCount(), c.cost()), c);
    }

    /**
     * What to tell a player the moment their selection is complete. Naming the
     * exact command to type is the whole point: the mod had a full-height mode
     * from early on, but the only place it was written down was the help screen,
     * so in practice everyone made 3D claims without ever knowing why.
     */
    public static String selectionHint() {
        boolean twoD = cfg().fullHeightByDefault;
        String primary = "&aСоздать: &f/claim create <название>";
        String primaryNote = twoD ? " &8(во всю высоту, от бедрока до неба)" : " &8(куб ровно по двум точкам)";
        String other = twoD
                ? "&8Нужен куб ровно по точкам: &7/claim create3d <название>"
                : "&8Нужно во всю высоту: &7/claim create2d <название>";
        return primary + primaryNote + "\n" + other;
    }

    /**
     * Why {@code target} may not take ownership of {@code c}, or {@code null}
     * when they may. The receiver's own tier is what counts — otherwise handing
     * claims around would be a way to launder past the limits.
     */
    public static String transferProblem(ServerPlayerEntity target, Claim c) {
        boolean unlimited = ProtectionService.isOp(target) && cfg().opBypassLimits;
        if (unlimited) return null;
        int hours = ProtectionService.playHours(target);
        int maxClaims = cfg().maxClaimsFor(hours);
        if (ClaimManager.countOf(target.getUuid()) >= maxClaims) {
            return "&cУ игрока &f" + target.getGameProfile().name() + "&c уже максимум приватов ("
                    + maxClaims + " при " + hours + "ч игры).";
        }
        long maxBlocks = cfg().maxBlocksFor(hours);
        if (c.cost() > maxBlocks) {
            return "&cЭтот приват (&f" + c.cost() + "&c блоков) больше лимита игрока &f"
                    + target.getGameProfile().name() + "&c (&f" + maxBlocks + "&c при " + hours + "ч игры).";
        }
        return null;
    }

    private static Result fail(String key, Object... args) {
        return new Result(false, cfg().message(key, args), null);
    }

    /** For a message that is already a full string (not a message key). */
    private static Result failRaw(String literalMessage) {
        return new Result(false, literalMessage, null);
    }

    private static ServerWorld resolve(MinecraftServer server, String dim) {
        try {
            ServerWorld w = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dim)));
            if (w != null) return w;
        } catch (Exception ignored) {
        }
        return server.getOverworld();
    }

    private ClaimService() {}
}
