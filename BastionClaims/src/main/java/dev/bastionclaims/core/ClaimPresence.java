package dev.bastionclaims.core;

import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.model.Claim;
import dev.bastionclaims.util.Fmt;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two things a player needs to know about the ground under their feet: whose it
 * is, and where it ends.
 *
 * <p>Crossing a border prints one line above the hotbar — only on the crossing,
 * never repeatedly. Borders are drawn with particles <b>for the owner only</b>:
 * showing everyone the exact outline of every base would be a raiding map.
 */
public final class ClaimPresence {

    /** Claim id the player was standing in last check ("" for open land). */
    private static final Map<UUID, String> LAST_CLAIM = new ConcurrentHashMap<>();
    /** Owners who asked for a permanent outline. */
    private static final Set<UUID> BORDERS_ON = ConcurrentHashMap.newKeySet();
    /** Owners shown the outline briefly after creating or extending a claim. */
    private static final Map<UUID, Long> BORDERS_UNTIL = new ConcurrentHashMap<>();
    /** Last position outside any sealed claim, where an ejected player is put back. */
    private record Spot(String dim, double x, double y, double z) {}
    private static final Map<UUID, Spot> LAST_OUTSIDE = new ConcurrentHashMap<>();
    /** Throttle for the "sealed" line, so standing on the border does not spam. */
    private static final Map<UUID, Long> LAST_ARREST_MSG = new ConcurrentHashMap<>();

    private static final int PRESENCE_INTERVAL = 10;   // ticks
    private static final int PARTICLE_INTERVAL = 10;   // ticks
    private static final int DRAW_RADIUS = 48;         // blocks from the player
    private static final int MAX_POINTS = 220;         // per player, per pass
    private static final int STEP = 2;                 // blocks between particles

    // ------------------------------------------------------------------ toggles

    public static boolean toggleBorders(ServerPlayerEntity p) {
        UUID id = p.getUuid();
        if (BORDERS_ON.remove(id)) return false;
        BORDERS_ON.add(id);
        return true;
    }

    public static boolean bordersOn(ServerPlayerEntity p) {
        return BORDERS_ON.contains(p.getUuid());
    }

    /** Flashes the outline for a few seconds — used right after a claim changes. */
    public static void flash(ServerPlayerEntity p, long millis) {
        BORDERS_UNTIL.put(p.getUuid(), System.currentTimeMillis() + millis);
    }

    public static void forget(UUID id) {
        LAST_CLAIM.remove(id);
        BORDERS_ON.remove(id);
        BORDERS_UNTIL.remove(id);
        LAST_OUTSIDE.remove(id);
        LAST_ARREST_MSG.remove(id);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server, long tick) {
        if (tick % PRESENCE_INTERVAL == 0) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) presence(p);
        }
        if (tick % PARTICLE_INTERVAL == 0) {
            long now = System.currentTimeMillis();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                Long until = BORDERS_UNTIL.get(p.getUuid());
                boolean temporary = until != null && until > now;
                if (until != null && !temporary) BORDERS_UNTIL.remove(p.getUuid());
                if (temporary || BORDERS_ON.contains(p.getUuid())) drawOwnBorders(p);
            }
        }
    }

    // ------------------------------------------------------------------ enter / leave

    private static void presence(ServerPlayerEntity p) {
        String dim = ProtectionService.dimOf(p);
        Claim now = ClaimManager.claimAt(dim, p.getBlockX(), p.getBlockY(), p.getBlockZ());

        // Sealed claims eject everyone who is not bypassing protection — the
        // owner most of all, since the seal is aimed at them. The player goes
        // back to where they last stood on open ground; failing that, up to
        // the surface outside the claim's edge.
        if (now != null && now.arrested && !ProtectionService.bypassesProtection(p)) {
            eject(p, now);
            return;
        }
        LAST_OUTSIDE.put(p.getUuid(), new Spot(dim, p.getX(), p.getY(), p.getZ()));

        String nowId = now == null ? "" : now.id;
        String beforeId = LAST_CLAIM.put(p.getUuid(), nowId);
        if (beforeId != null && beforeId.equals(nowId)) return;   // nothing changed
        if (beforeId == null && now == null) return;              // first check on open land

        if (now != null) {
            String key = now.isOwner(p.getUuid()) ? "presence.enterOwn"
                    : now.isMember(p.getUuid()) ? "presence.enterMember"
                    : "presence.enterOther";
            p.sendMessage(Fmt.text(cfg().message(key, now.name, now.owner)), true);
        } else {
            Claim left = ClaimManager.byId(beforeId);
            p.sendMessage(Fmt.text(cfg().message("presence.leave",
                    left == null ? "?" : left.name)), true);
        }
    }

    private static void eject(ServerPlayerEntity p, Claim c) {
        if (!(p.getEntityWorld() instanceof ServerWorld world)) return;
        String dim = world.getRegistryKey().getValue().toString();
        Spot back = LAST_OUTSIDE.get(p.getUuid());
        if (back != null && back.dim().equals(dim)
                && ClaimManager.claimAt(dim, (int) Math.floor(back.x()), (int) Math.floor(back.y()),
                        (int) Math.floor(back.z())) == null) {
            p.teleport(world, back.x(), back.y(), back.z(), java.util.Set.of(), p.getYaw(), p.getPitch(), false);
        } else {
            // No memory of open ground (joined inside): put them just past the
            // nearest edge of the primary box, on the surface.
            int x = p.getBlockX() - c.minX < c.maxX - p.getBlockX() ? c.minX - 2 : c.maxX + 2;
            int z = p.getBlockZ();
            int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
            p.teleport(world, x + 0.5, y, z + 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
        }
        long now = System.currentTimeMillis();
        Long last = LAST_ARREST_MSG.get(p.getUuid());
        if (last == null || now - last > 3000) {
            LAST_ARREST_MSG.put(p.getUuid(), now);
            p.sendMessage(Fmt.text(cfg().message("presence.arrested", c.name,
                    c.arrestReason == null || c.arrestReason.isBlank() ? "по решению государства" : c.arrestReason)), false);
        }
    }

    // ------------------------------------------------------------------ borders

    private static void drawOwnBorders(ServerPlayerEntity p) {
        if (!(p.getEntityWorld() instanceof ServerWorld world)) return;
        String dim = world.getRegistryKey().getValue().toString();
        List<Claim> mine = ClaimManager.claimsOf(p.getUuid());
        if (mine.isEmpty()) return;

        int px = p.getBlockX(), py = p.getBlockY(), pz = p.getBlockZ();
        int drawn = 0;

        for (Claim c : mine) {
            if (!c.sameDim(dim)) continue;
            for (Claim.Box b : c.zones()) {
                if (drawn >= MAX_POINTS) return;
                // Cheap reject before walking the perimeter at all.
                if (px < b.minX - DRAW_RADIUS || px > b.maxX + DRAW_RADIUS
                        || pz < b.minZ - DRAW_RADIUS || pz > b.maxZ + DRAW_RADIUS) continue;

                // Drawn at the player's own height so the line is where they
                // are looking, clamped into the zone for 3D claims.
                double y = Math.min(Math.max(py, b.minY), b.maxY) + 0.15;

                for (int x = b.minX; x <= b.maxX && drawn < MAX_POINTS; x += STEP) {
                    drawn += point(world, p, x, y, b.minZ, px, pz);
                    drawn += point(world, p, x, y, b.maxZ + 1.0, px, pz);
                }
                for (int z = b.minZ; z <= b.maxZ && drawn < MAX_POINTS; z += STEP) {
                    drawn += point(world, p, b.minX, y, z, px, pz);
                    drawn += point(world, p, b.maxX + 1.0, y, z, px, pz);
                }
            }
        }
    }

    /** @return 1 when a particle was actually sent, 0 when the point was too far. */
    private static int point(ServerWorld world, ServerPlayerEntity viewer,
                             double x, double y, double z, int px, int pz) {
        double dx = x - px, dz = z - pz;
        if (dx * dx + dz * dz > (double) DRAW_RADIUS * DRAW_RADIUS) return 0;
        world.spawnParticles(viewer, ParticleTypes.HAPPY_VILLAGER, true, false,
                x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        return 1;
    }

    private static dev.bastionclaims.config.ClaimConfig cfg() {
        return BastionClaims.config();
    }

    private ClaimPresence() {}
}
