package dev.bastionclaims.api;

import dev.bastionclaims.core.ClaimManager;
import dev.bastionclaims.core.ProtectionService;
import dev.bastionclaims.model.Claim;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Small stable entry point for other mods (e.g. MaxCore's sidebar). Kept
 * dependency-free on purpose so it can be reached by reflection without a
 * compile-time link:
 * {@code Class.forName("dev.bastionclaims.api.ClaimApi").getMethod("statusLine", ServerPlayerEntity.class)}.
 */
public final class ClaimApi {

    /** The claim covering the player's current position, or {@code null}. */
    public static Claim claimAt(ServerPlayerEntity player) {
        return ClaimManager.claimAt(ProtectionService.dimOf(player),
                player.getBlockX(), player.getBlockY(), player.getBlockZ());
    }

    public static boolean isInClaim(ServerPlayerEntity player) {
        return claimAt(player) != null;
    }

    /**
     * A short, colour-coded (&-codes) status for a sidebar line. Never null:
     * "нет" (grey) on unclaimed land; the claim name in <b>green</b> when the
     * player owns it or is trusted; the claim name in <b>red</b> when it is
     * someone else's claim.
     */
    public static String statusLine(ServerPlayerEntity player) {
        Claim c = claimAt(player);
        if (c == null) return "&7нет";
        if (c.arrested) return "&4⛔ " + c.name;
        if (c.isOwner(player.getUuid()) || c.isMember(player.getUuid())) return "&a" + c.name;
        return "&c" + c.name;
    }

    // ------------------------------------------------------------------ land register

    /**
     * Every claim on the server as a flat row, for a mod that taxes land:
     * {@code {id, ownerUuid, ownerName, claimName, cost(Long), dim, arrested(Boolean)}}.
     * Plain {@code Object[]} rows rather than {@link Claim} so a reflective
     * caller never needs this mod's classes on its compile path. {@code cost}
     * is the same figure the tier limits are measured in — footprint for a
     * full-height claim, volume for a 3D one — so a tax on it lines up with
     * what the player already knows their claim "weighs".
     */
    public static java.util.List<Object[]> taxRoll() {
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (Claim c : ClaimManager.all()) {
            java.util.UUID owner = c.ownerUuid();
            if (owner == null) owner = Claim.offlineUuid(c.owner == null ? "" : c.owner);
            rows.add(new Object[]{c.id, owner.toString(), c.owner, c.name, c.cost(), c.dim, c.arrested});
        }
        return rows;
    }

    /** Seals or unseals a claim by id. Returns false when the id is unknown. */
    public static boolean setArrested(String claimId, boolean arrested, String reason) {
        Claim c = ClaimManager.byId(claimId);
        if (c == null) return false;
        if (c.arrested == arrested) {
            if (arrested && reason != null && !reason.equals(c.arrestReason)) {
                ClaimManager.setArrested(c, true, reason);
            }
            return true;
        }
        ClaimManager.setArrested(c, arrested, reason);
        return true;
    }

    public static boolean isArrested(String claimId) {
        Claim c = ClaimManager.byId(claimId);
        return c != null && c.arrested;
    }

    /** Id of the claim covering a position, or {@code null}. */
    public static String claimIdAt(String dim, int x, int y, int z) {
        Claim c = ClaimManager.claimAt(dim, x, y, z);
        return c == null ? null : c.id;
    }

    /**
     * Name of the claim covering a raw position, or {@code null} when the block
     * is unclaimed. Position-based (not player-based) so другие моды can check a
     * region they care about — e.g. MaxCore verifying that the labour camp is
     * actually protected.
     */
    public static String claimNameAt(String dim, int x, int y, int z) {
        Claim c = ClaimManager.claimAt(dim, x, y, z);
        return c == null ? null : c.name;
    }

    /** Whether non-members may open containers at that position (false if unclaimed). */
    public static boolean guestContainersAt(String dim, int x, int y, int z) {
        Claim c = ClaimManager.claimAt(dim, x, y, z);
        return c != null && c.guestContainers;
    }

    /** How many claims the player owns. */
    public static int countOf(ServerPlayerEntity player) {
        return ClaimManager.claimsOf(player.getUuid()).size();
    }

    /** Opens the claims menu — lets the МАХ app link straight into this mod. */
    public static void openMenu(ServerPlayerEntity player) {
        dev.bastionclaims.gui.ClaimMenu.open(player);
    }

    private ClaimApi() {}
}
