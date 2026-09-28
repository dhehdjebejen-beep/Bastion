package dev.bastionclaims.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A single land claim: an axis-aligned 3D cuboid in one dimension, owned by a
 * player, optionally shared with trusted members. Pure data + geometry, with no
 * Minecraft dependency, so the whole class is unit-testable on its own.
 *
 * <p>The claimed "cost" is the cuboid <em>volume</em>
 * ({@code (maxX-minX+1) * (maxY-minY+1) * (maxZ-minZ+1)}), which is what the
 * per-tier {@code maxBlocksPerClaim} limit is measured against.
 */
public final class Claim {

    /** One cuboid of a claim. A claim owns at least one; extras are added later. */
    public static final class Box {
        public int minX, minY, minZ, maxX, maxY, maxZ;

        public Box() {}

        public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
            Box b = new Box();
            b.minX = Math.min(x1, x2); b.maxX = Math.max(x1, x2);
            b.minY = Math.min(y1, y2); b.maxY = Math.max(y1, y2);
            b.minZ = Math.min(z1, z2); b.maxZ = Math.max(z1, z2);
            return b;
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        public int sizeX() { return maxX - minX + 1; }
        public int sizeY() { return maxY - minY + 1; }
        public int sizeZ() { return maxZ - minZ + 1; }

        public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

        public long area() { return (long) sizeX() * sizeZ(); }

        /**
         * How many blocks lie between the two boxes: 0 when they touch or
         * overlap. Bounds are inclusive, so a face-sharing neighbour is
         * {@code o.minX - maxX == 1} — hence the extra −1.
         */
        public int distanceTo(Box o) {
            int dx = Math.max(0, Math.max(minX - o.maxX - 1, o.minX - maxX - 1));
            int dy = Math.max(0, Math.max(minY - o.maxY - 1, o.minY - maxY - 1));
            int dz = Math.max(0, Math.max(minZ - o.maxZ - 1, o.minZ - maxZ - 1));
            return Math.max(dx, Math.max(dy, dz));
        }
    }

    public String id;
    public String owner;                 // display name (original case)
    /** Owner UUID (dashed string). Written on every owner change; legacy files without it are migrated on load via offline-UUID derivation. */
    public String ownerUuid;
    /** Trusted member UUIDs (dashed strings), mirrored from {@link #members} for identity-by-UUID checks. */
    public List<String> memberUuids = new ArrayList<>();
    public String name;                  // label chosen by the owner
    public String dim = "minecraft:overworld";
    /** The first zone. Kept as loose fields so old claims.json files still load. */
    public int minX, minY, minZ;
    public int maxX, maxY, maxZ;
    /**
     * Zones added after creation. A claim is the union of its primary box and
     * these — that is how one claim can be an L, a ring, or two towers joined,
     * instead of forcing the owner to spend a second claim slot on each piece.
     */
    public List<Box> extraZones = new ArrayList<>();
    public List<String> members = new ArrayList<>();   // trusted names (lower-case)

    /** True for a 2D "full-height" claim (spans the whole world column). Its cost
     *  is measured as footprint area (X*Z), not volume — otherwise it could never
     *  fit the per-tier limit. */
    public boolean fullHeight = false;

    // Per-claim flags. Default is "protected": anything that could grief or
    // endanger the claim is OFF until the owner explicitly opts in.
    public boolean allowPvp = false;
    public boolean allowMobGriefing = false;
    /** Allow hostile mobs (and their explosions) to damage players inside. */
    public boolean allowMobDamage = false;
    /** Allow non-mob explosions (TNT, end crystals, beds…) to break blocks. */
    public boolean allowExplosions = false;
    /** Allow liquids to flow into the claim from blocks outside its border. */
    public boolean allowFluidFlow = false;
    /** Allow pistons standing outside the claim to move/break blocks inside it. */
    public boolean allowPistons = false;
    /** Allow hoppers/hopper-minecarts outside the claim to pull from or push into
     *  containers inside it (automation crossing the border). */
    public boolean allowHoppers = false;
    /** Allow fire to tick and spread inside the claim. Default OFF: burning
     *  scaffolding just outside a border must not eat a build. */
    public boolean allowFireSpread = false;
    /** Let non-members open containers (chests, barrels, furnaces…). */
    public boolean guestContainers = false;
    /** Let non-members use doors, trapdoors, gates, buttons, levers, plates. */
    public boolean guestDoors = false;
    /** Let non-members right-click entities: villagers, horses, boats, frames. */
    public boolean guestEntities = false;

    /**
     * Under arrest: nobody, the owner included, may enter, build or open
     * anything inside until the flag is lifted. Set by the state (MaxCore's
     * land tax through {@link dev.bastionclaims.api.ClaimApi}) — the claim is
     * neither deleted nor transferred, it is simply sealed, so an unpaid tax
     * costs access rather than the base itself. Protection against outsiders
     * stays exactly as it was.
     */
    public boolean arrested = false;
    /** Why, for the message on the border. */
    public String arrestReason = "";

    public Claim() {}

    /** Builds a claim from two arbitrary corners, normalising min/max. */
    public static Claim of(String id, String owner, String name, String dim,
                           int x1, int y1, int z1, int x2, int y2, int z2) {
        Claim c = new Claim();
        c.id = id;
        c.owner = owner;
        c.name = name;
        c.dim = dim;
        c.minX = Math.min(x1, x2);
        c.minY = Math.min(y1, y2);
        c.minZ = Math.min(z1, z2);
        c.maxX = Math.max(x1, x2);
        c.maxY = Math.max(y1, y2);
        c.maxZ = Math.max(z1, z2);
        return c;
    }

    /** The primary box plus every extra zone, in order. */
    public List<Box> zones() {
        List<Box> out = new ArrayList<>(1 + (extraZones == null ? 0 : extraZones.size()));
        out.add(primary());
        if (extraZones != null) out.addAll(extraZones);
        return out;
    }

    /** The first zone, as a box. */
    public Box primary() {
        return Box.of(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int zoneCount() {
        return 1 + (extraZones == null ? 0 : extraZones.size());
    }

    /** Number of blocks enclosed, summed over every zone. */
    public long volume() {
        long total = 0;
        for (Box b : zones()) total += b.volume();
        return total;
    }

    /** Horizontal footprint (X*Z), summed over every zone. */
    public long area() {
        long total = 0;
        for (Box b : zones()) total += b.area();
        return total;
    }

    /**
     * The value checked against the per-tier {@code maxBlocksPerClaim} limit:
     * footprint area for full-height 2D claims, full volume for 3D claims.
     */
    public long cost() {
        return fullHeight ? area() : volume();
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }

    public boolean contains(int x, int y, int z) {
        if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) return true;
        if (extraZones == null) return false;
        for (Box b : extraZones) {
            if (b.contains(x, y, z)) return true;
        }
        return false;
    }

    public boolean contains(String dimension, int x, int y, int z) {
        return sameDim(dimension) && contains(x, y, z);
    }

    public boolean sameDim(String dimension) {
        return dim != null && dim.equals(dimension);
    }

    /** True when this claim and {@code other} share any block (same dimension). */
    public boolean intersects(Claim other) {
        if (other == null || !sameDim(other.dim)) return false;
        for (Box a : zones()) {
            for (Box b : other.zones()) {
                if (boxesIntersect(a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ,
                        b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when any zone of this claim overlaps the given cuboid. */
    public boolean intersects(int ax, int ay, int az, int bx, int by, int bz) {
        int lminX = Math.min(ax, bx), lminY = Math.min(ay, by), lminZ = Math.min(az, bz);
        int lmaxX = Math.max(ax, bx), lmaxY = Math.max(ay, by), lmaxZ = Math.max(az, bz);
        for (Box a : zones()) {
            if (boxesIntersect(a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ,
                    lminX, lminY, lminZ, lmaxX, lmaxY, lmaxZ)) {
                return true;
            }
        }
        return false;
    }

    /** Shortest gap from the given box to the nearest zone of this claim. */
    public int distanceToNearestZone(Box other) {
        int best = Integer.MAX_VALUE;
        for (Box b : zones()) best = Math.min(best, b.distanceTo(other));
        return best;
    }

    public boolean isOwner(String playerName) {
        return owner != null && owner.equalsIgnoreCase(playerName);
    }

    public boolean isMember(String playerName) {
        if (playerName == null) return false;
        return members.contains(playerName.toLowerCase(Locale.ROOT));
    }

    /** Owner by UUID — the authoritative identity check on an offline server,
     *  where display names can be spoofed but UUIDs cannot (offline UUID is a
     *  deterministic hash of the name, so a name-clone still gets a distinct
     *  check only when the stored ownerUuid is present). */
    public boolean isOwner(java.util.UUID uuid) {
        if (uuid == null) return false;
        if (ownerUuid != null && !ownerUuid.isEmpty()) return uuid.toString().equals(ownerUuid);
        // Legacy record without UUID: derive the offline UUID from the stored
        // name and compare, so pre-migration files keep working unchanged.
        return owner != null && uuid.equals(offlineUuid(owner));
    }

    /** Trusted member by UUID (falls back to the legacy name list). */
    public boolean isMember(java.util.UUID uuid) {
        if (uuid == null) return false;
        if (memberUuids != null) {
            for (String s : memberUuids) {
                if (uuid.toString().equals(s)) return true;
            }
        }
        // Legacy fallback: a member may exist only by name in old files.
        return false;
    }

    /** Owner or trusted member by UUID. */
    public boolean isTrusted(java.util.UUID uuid) {
        return isOwner(uuid) || isMember(uuid);
    }

    /** Owner or trusted member. */
    public boolean isTrusted(String playerName) {
        return isOwner(playerName) || isMember(playerName);
    }

    public void setOwnerUuid(java.util.UUID uuid) {
        this.ownerUuid = uuid == null ? null : uuid.toString();
    }

    public java.util.UUID ownerUuid() {
        if (ownerUuid == null || ownerUuid.isEmpty()) return null;
        try {
            return java.util.UUID.fromString(ownerUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean addMemberUuid(java.util.UUID uuid) {
        if (uuid == null || isMember(uuid)) return false;
        if (memberUuids == null) memberUuids = new ArrayList<>();
        memberUuids.add(uuid.toString());
        return true;
    }

    public boolean removeMemberUuid(java.util.UUID uuid) {
        if (uuid == null || memberUuids == null) return false;
        return memberUuids.remove(uuid.toString());
    }

    /** Fills the UUID mirror fields for legacy records using the vanilla
     *  offline-UUID formula (MD5 of "OfflinePlayer:" + name). Idempotent. */
    public void migrateToUuids() {
        if ((ownerUuid == null || ownerUuid.isEmpty()) && owner != null && !owner.isEmpty()) {
            ownerUuid = offlineUuid(owner).toString();
        }
        if (memberUuids == null) memberUuids = new ArrayList<>();
        if (members != null) {
            for (String name : members) {
                java.util.UUID u = offlineUuid(name);
                boolean found = false;
                for (String s : memberUuids) {
                    if (s.equals(u.toString())) { found = true; break; }
                }
                if (!found) memberUuids.add(u.toString());
            }
        }
    }

    /** Vanilla offline-mode UUID: UUID.nameUUIDFromBytes("OfflinePlayer:"+name). */
    public static java.util.UUID offlineUuid(String playerName) {
        return java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public boolean addMember(String playerName) {
        String key = playerName.toLowerCase(Locale.ROOT);
        if (members.contains(key)) return false;
        members.add(key);
        return true;
    }

    public boolean removeMember(String playerName) {
        return members.remove(playerName.toLowerCase(Locale.ROOT));
    }

    /** Static 3D AABB overlap test (inclusive integer bounds). */
    public static boolean boxesIntersect(int aMinX, int aMinY, int aMinZ, int aMaxX, int aMaxY, int aMaxZ,
                                         int bMinX, int bMinY, int bMinZ, int bMaxX, int bMaxY, int bMaxZ) {
        return aMinX <= bMaxX && aMaxX >= bMinX
                && aMinY <= bMaxY && aMaxY >= bMinY
                && aMinZ <= bMaxZ && aMaxZ >= bMinZ;
    }
}
