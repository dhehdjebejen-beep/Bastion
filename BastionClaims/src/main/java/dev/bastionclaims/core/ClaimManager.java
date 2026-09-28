package dev.bastionclaims.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.model.Claim;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * In-memory registry of all claims, persisted to {@code config/bastionclaims/
 * claims.json}. Fast "who owns this block?" lookups are served from a per-world
 * chunk index ({@code dim -> chunkKey -> claims}); every mutation rewrites the
 * JSON file. All access happens on the server thread.
 */
public final class ClaimManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final List<Claim> CLAIMS = new ArrayList<>();
    // dim -> (packed chunk x/z -> claims touching that chunk column)
    private static final Map<String, Map<Long, List<Claim>>> INDEX = new ConcurrentHashMap<>();

    private static volatile Path file;

    public static synchronized void init(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        file = configDir.resolve("claims.json");
        load();
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Set when claims.json exists but could not be parsed. While it is true we
     * refuse to write: an unreadable file must never be silently overwritten
     * with an empty list, which would destroy every claim on the server.
     */
    private static volatile boolean readFailed;

    public static boolean isReadFailed() {
        return readFailed;
    }

    /**
     * Drops the in-memory registry and, crucially, the path it writes to.
     *
     * <p>Tests point this manager at a throwaway directory; leaving the static
     * pointing there afterwards means a later save can land in a folder the test
     * framework is busy deleting.
     */
    public static synchronized void detach() {
        CLAIMS.clear();
        INDEX.clear();
        readFailed = false;
        file = null;
    }

    /**
     * Loads the registry from the primary file, falling back only to the last
     * known-good backup. A protection mod must never continue with an empty
     * registry after a parse error: that would silently turn every claim into
     * unclaimed land.
     */
    private static void load() throws IOException {
        CLAIMS.clear();
        INDEX.clear();
        readFailed = false;
        migratedToUuids = 0;
        if (file == null || !Files.exists(file)) return;

        try {
            installClaims(readClaims(file));
            BastionClaims.LOGGER.info("Loaded {} claim(s)", CLAIMS.size());
            persistUuidMigration();
            keepGoodCopy();
            return;
        } catch (Exception primaryFailure) {
            CLAIMS.clear();
            INDEX.clear();
            Path broken = preserveBrokenPrimary();
            Path backup = file.resolveSibling("claims.json.bak");

            if (Files.isRegularFile(backup)) {
                try {
                    installClaims(readClaims(backup));
                    persistUuidMigration();
                    BastionClaims.LOGGER.warn("claims.json is unreadable; recovered {} claim(s) from {}. "
                                    + "The damaged primary file was preserved as {}.",
                            CLAIMS.size(), backup.getFileName(), broken.getFileName());
                    return;
                } catch (Exception backupFailure) {
                    CLAIMS.clear();
                    INDEX.clear();
                    readFailed = true;
                    IOException fatal = new IOException("Both claims.json and claims.json.bak are unreadable; "
                            + "refusing to start BastionClaims without claim protection.", primaryFailure);
                    fatal.addSuppressed(backupFailure);
                    throw fatal;
                }
            }

            readFailed = true;
            throw new IOException("claims.json is unreadable and no valid claims.json.bak exists; "
                    + "refusing to start BastionClaims without claim protection. Damaged copy: "
                    + broken.getFileName(), primaryFailure);
        }
    }

    /**
     * Rewrites claims.json once after a load that backfilled legacy name-only
     * records with UUID ownership, so the next startup is a pure UUID load and
     * a crash between the two cannot lose the migration.
     */
    private static void persistUuidMigration() {
        if (migratedToUuids <= 0) return;
        BastionClaims.LOGGER.info("Migrated {} legacy claims to UUID ownership", migratedToUuids);
        save();
    }

    private static List<Claim> readClaims(Path source) throws IOException {
        String json = Files.readString(source, StandardCharsets.UTF_8);
        Type type = new TypeToken<List<Claim>>() {}.getType();
        List<Claim> loaded = GSON.fromJson(json, type);
        if (loaded == null) {
            throw new IOException(source.getFileName() + " must contain a JSON array, not null");
        }
        return loaded;
    }

    /**
     * Set when at least one installed claim needed the legacy name → UUID
     * backfill, so {@code load()} can rewrite the file once after the whole
     * list is validated instead of writing it per claim.
     */
    private static int migratedToUuids;

    /** Installs only structurally valid claims; silently dropping one is fail-open. */
    private static void installClaims(List<Claim> loaded) throws IOException {
        for (Claim c : loaded) {
            if (c == null || c.id == null || c.id.isBlank() || c.owner == null || c.owner.isBlank()
                    || c.dim == null || c.dim.isBlank()) {
                throw new IOException("claims.json contains a claim without id, owner or dimension");
            }
            if (c.minX > c.maxX || c.minY > c.maxY || c.minZ > c.maxZ) {
                throw new IOException("claims.json contains a primary zone with inverted bounds");
            }
            if (c.members == null) c.members = new ArrayList<>();
            // Claims written before multi-zone support have no list at all.
            if (c.extraZones == null) c.extraZones = new ArrayList<>();
            for (Claim.Box zone : c.extraZones) {
                if (zone == null) throw new IOException("claims.json contains a null extra zone");
                if (zone.minX > zone.maxX || zone.minY > zone.maxY || zone.minZ > zone.maxZ) {
                    throw new IOException("claims.json contains an extra zone with inverted bounds");
                }
            }
            // Fill owner/member UUID mirrors for records written before UUID
            // ownership existed. migrateToUuids() is idempotent; the before/
            // after comparison counts only claims whose JSON will actually
            // change, so an already-migrated file is not rewritten.
            boolean ownerUuidMissing = c.ownerUuid == null || c.ownerUuid.isEmpty();
            int memberUuidsBefore = c.memberUuids == null ? 0 : c.memberUuids.size();
            c.migrateToUuids();
            if (ownerUuidMissing || (c.memberUuids != null && c.memberUuids.size() > memberUuidsBefore)) {
                migratedToUuids++;
            }
            CLAIMS.add(c);
            indexAdd(c);
        }
    }

    private static Path preserveBrokenPrimary() {
        Path broken = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.copy(file, broken, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException copyFailed) {
            BastionClaims.LOGGER.error("Could not preserve the broken claims.json", copyFailed);
        }
        return broken;
    }

    /** Snapshot of the last file that parsed cleanly, for recovery on next startup. */
    private static void keepGoodCopy() {
        Path target = file;
        if (target != null) keepGoodCopy(target);
    }

    private static void keepGoodCopy(Path target) {
        try {
            Files.copy(target, target.resolveSibling("claims.json.bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            BastionClaims.LOGGER.warn("Could not refresh claims.json.bak: {}", e.toString());
        }
    }

    /** Single writer thread: keeps writes ordered and off the server tick. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BastionClaims-IO");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /**
     * Serialises on the calling (server) thread and writes on the I/O thread.
     *
     * <p>Serialisation stays on the tick on purpose: {@code Claim} objects are
     * mutated in place by the flag setters, so handing the live list to another
     * thread would let Gson read a claim halfway through a change. Turning a few
     * hundred claims into JSON is a bounded millisecond; the disk write is the
     * unpredictable part, and that is what moves off.
     *
     * <p>The write itself still goes through a temp file and an atomic move, so
     * a crash mid-write leaves the previous file intact rather than a truncated
     * one — and every mutation is still persisted immediately, with no window
     * where a just-created claim exists only in memory.
     */
    private static void save() {
        if (file == null) return;
        if (readFailed) {
            BastionClaims.LOGGER.error("Refusing to write claims.json: it did not parse at startup. "
                    + "Restore claims.json.bak (or the .broken-* copy) and restart.");
            return;
        }
        String json = GSON.toJson(CLAIMS);
        // The destination is fixed when the snapshot is taken: a queued write
        // must land in the file its data came from, even if the registry is
        // re-initialised (or detached) before the I/O thread gets to it.
        Path target = file;
        IO.execute(() -> writeAtomically(json, target));
    }

    /** Blocking write — used on shutdown, where the data must reach the disk now. */
    public static synchronized void saveNow() {
        if (file == null || readFailed) return;
        String json = GSON.toJson(CLAIMS);
        Path target = file;
        try {
            // Queue the final snapshot behind earlier saves. Writing it directly
            // could race an older queued write and restore stale claim data.
            IO.submit(() -> writeAtomically(json, target)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // A stuck disk must never hold shutdown hostage, but the operator
            // needs a visible indication that the final state may be unsaved.
            BastionClaims.LOGGER.error("Timed out or failed while flushing claims.json at shutdown", e);
        }
    }

    private static void writeAtomically(String json, Path target) {
        if (target == null) return;
        Path tmp = null;
        try {
            tmp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            keepGoodCopy(target);
        } catch (IOException e) {
            BastionClaims.LOGGER.error("Failed to write claims.json", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // The next startup can safely remove an orphaned unique temp file.
                }
            }
        }
    }

    // ------------------------------------------------------------------ chunk index

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    // Every zone of a claim is indexed; zones sharing a chunk must not add the
    // same claim to that bucket twice or removal would leave a stale entry.
    private static void indexAdd(Claim c) {
        Map<Long, List<Claim>> world = INDEX.computeIfAbsent(c.dim, k -> new ConcurrentHashMap<>());
        for (Claim.Box b : c.zones()) {
            for (int cx = b.minX >> 4; cx <= (b.maxX >> 4); cx++) {
                for (int cz = b.minZ >> 4; cz <= (b.maxZ >> 4); cz++) {
                    List<Claim> bucket = world.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>());
                    if (!bucket.contains(c)) bucket.add(c);
                }
            }
        }
    }

    private static void indexRemove(Claim c) {
        Map<Long, List<Claim>> world = INDEX.get(c.dim);
        if (world == null) return;
        for (Claim.Box b : c.zones()) {
            for (int cx = b.minX >> 4; cx <= (b.maxX >> 4); cx++) {
                for (int cz = b.minZ >> 4; cz <= (b.maxZ >> 4); cz++) {
                    long key = chunkKey(cx, cz);
                    List<Claim> bucket = world.get(key);
                    if (bucket != null) {
                        bucket.remove(c);
                        if (bucket.isEmpty()) world.remove(key);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ lookups

    /** The claim covering this block, or {@code null} if the block is unclaimed. */
    public static Claim claimAt(String dim, int x, int y, int z) {
        Map<Long, List<Claim>> world = INDEX.get(dim);
        if (world == null) return null;
        List<Claim> bucket = world.get(chunkKey(x >> 4, z >> 4));
        if (bucket == null) return null;
        for (Claim c : bucket) {
            if (c.contains(x, y, z)) return c;
        }
        return null;
    }

    /**
     * First existing claim overlapping the given cuboid, or {@code null}. Used to
     * reject overlapping new claims. {@code ignoreId} is skipped (for resizes).
     */
    public static Claim firstOverlap(String dim, int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ, String ignoreId) {
        Map<Long, List<Claim>> world = INDEX.get(dim);
        if (world == null) return null;
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                List<Claim> bucket = world.get(chunkKey(cx, cz));
                if (bucket == null) continue;
                for (Claim c : bucket) {
                    if (ignoreId != null && ignoreId.equals(c.id)) continue;
                    if (c.intersects(minX, minY, minZ, maxX, maxY, maxZ)) return c;
                }
            }
        }
        return null;
    }

    /** First overlapping claim that {@code owner} does not own, or {@code null}. */
    public static Claim firstForeignOverlap(String dim, int minX, int minY, int minZ,
                                            int maxX, int maxY, int maxZ, String owner) {
        return firstForeignOverlap(dim, minX, minY, minZ, maxX, maxY, maxZ, owner, null);
    }

    /** As {@link #firstForeignOverlap}, matching the operator by UUID. */
    public static Claim firstForeignOverlap(String dim, int minX, int minY, int minZ,
                                            int maxX, int maxY, int maxZ, UUID owner) {
        return firstForeignOverlap(dim, minX, minY, minZ, maxX, maxY, maxZ, null, owner);
    }

    private static Claim firstForeignOverlap(String dim, int minX, int minY, int minZ,
                                             int maxX, int maxY, int maxZ, String ownerName,
                                             UUID ownerUuid) {
        Map<Long, List<Claim>> world = INDEX.get(dim);
        if (world == null) return null;
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                List<Claim> bucket = world.get(chunkKey(cx, cz));
                if (bucket == null) continue;
                for (Claim c : bucket) {
                    if (ownerUuid != null ? c.isOwner(ownerUuid) : c.isOwner(ownerName)) continue;
                    if (c.intersects(minX, minY, minZ, maxX, maxY, maxZ)) return c;
                }
            }
        }
        return null;
    }

    public static List<Claim> claimsOf(String owner) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : CLAIMS) {
            if (c.isOwner(owner)) out.add(c);
        }
        return out;
    }

    public static int countOf(String owner) {
        int n = 0;
        for (Claim c : CLAIMS) if (c.isOwner(owner)) n++;
        return n;
    }

    /**
     * Claims owned by UUID — the authoritative form wherever a player object
     * is at hand. {@link Claim#isOwner(UUID)} also falls back to the stored
     * name for pre-migration records, so this sees every claim.
     */
    public static List<Claim> claimsOf(UUID owner) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : CLAIMS) {
            if (c.isOwner(owner)) out.add(c);
        }
        return out;
    }

    public static int countOf(UUID owner) {
        int n = 0;
        for (Claim c : CLAIMS) if (c.isOwner(owner)) n++;
        return n;
    }

    public static Claim byId(String id) {
        if (id == null) return null;
        for (Claim c : CLAIMS) {
            if (id.equals(c.id)) return c;
        }
        return null;
    }

    public static Claim byOwnerAndName(String owner, String name) {
        for (Claim c : CLAIMS) {
            if (c.isOwner(owner) && c.name != null && c.name.equalsIgnoreCase(name)) return c;
        }
        return null;
    }

    public static boolean nameTaken(String owner, String name) {
        return byOwnerAndName(owner, name) != null;
    }

    public static List<Claim> all() {
        return new ArrayList<>(CLAIMS);
    }

    // ------------------------------------------------------------------ mutations

    /** Creates and registers a claim (no validation — callers check limits/overlap). */
    public static synchronized Claim create(String owner, String name, String dim,
                                            int x1, int y1, int z1, int x2, int y2, int z2) {
        return create(owner, name, dim, false, x1, y1, z1, x2, y2, z2);
    }

    /** As {@link #create}, marking the claim as a full-height 2D claim when requested. */
    public static synchronized Claim create(String owner, String name, String dim, boolean fullHeight,
                                            int x1, int y1, int z1, int x2, int y2, int z2) {
        Claim c = Claim.of(UUID.randomUUID().toString(), owner, name, dim, x1, y1, z1, x2, y2, z2);
        c.fullHeight = fullHeight;
        // The display name stays for messages; ownership is checked by UUID
        // from here on. On an offline server the player UUID is exactly this
        // offline-UUID derivation, so the mirror cannot drift.
        c.setOwnerUuid(Claim.offlineUuid(owner));
        CLAIMS.add(c);
        indexAdd(c);
        save();
        return c;
    }

    /** Adds a zone to an existing claim. Re-indexes so lookups see it at once. */
    public static synchronized void addZone(Claim c, Claim.Box zone) {
        if (c == null || zone == null) return;
        indexRemove(c);
        if (c.extraZones == null) c.extraZones = new ArrayList<>();
        c.extraZones.add(zone);
        indexAdd(c);
        save();
    }

    /**
     * Removes the zone at a 1-based index as shown to the player. Index 1 is the
     * primary box and cannot be removed — deleting that means deleting the claim.
     */
    public static synchronized boolean removeZone(Claim c, int oneBasedIndex) {
        if (c == null || c.extraZones == null) return false;
        int i = oneBasedIndex - 2; // 1 = primary, 2 = first extra
        if (i < 0 || i >= c.extraZones.size()) return false;
        indexRemove(c);
        c.extraZones.remove(i);
        indexAdd(c);
        save();
        return true;
    }

    public static synchronized boolean remove(Claim c) {
        if (c == null) return false;
        boolean removed = CLAIMS.remove(c);
        if (removed) {
            indexRemove(c);
            save();
        }
        return removed;
    }

    /**
     * Hands the claim to another player. Names are unique per owner, so a clash
     * with something the new owner already has gets a numeric suffix instead of
     * failing the transfer. The previous owner keeps no access — trusted users
     * are left as they were.
     *
     * @return the name the claim ended up with
     */
    public static synchronized String setOwner(Claim c, String newOwner, int maxNameLen) {
        return setOwner(c, newOwner, null, maxNameLen);
    }

    /**
     * As {@link #setOwner(Claim, String, int)}, with the new owner's UUID
     * supplied by a caller that has the player online — the transfer then
     * keeps the exact identity even if the display name is odd. Falls back to
     * the offline-UUID derivation of the name otherwise.
     *
     * @return the name the claim ended up with
     */
    public static synchronized String setOwner(Claim c, String newOwner, UUID newOwnerUuid, int maxNameLen) {
        if (c == null) return null;
        c.name = uniqueNameFor(newOwner, c.name, maxNameLen);
        c.owner = newOwner;
        c.setOwnerUuid(newOwnerUuid != null ? newOwnerUuid : Claim.offlineUuid(newOwner));
        // The owner is not their own guest — clear both mirrors, name and UUID.
        c.removeMember(newOwner);
        c.removeMemberUuid(Claim.offlineUuid(newOwner));
        if (newOwnerUuid != null) c.removeMemberUuid(newOwnerUuid);
        save();
        return c.name;
    }

    /** {@code base}, or "base 2", "base 3"… — the first name free for that owner. */
    public static String uniqueNameFor(String owner, String base, int maxNameLen) {
        String name = base == null || base.isBlank() ? "приват" : base.trim();
        if (!nameTaken(owner, name)) return name;
        for (int n = 2; n < 1000; n++) {
            String suffix = " " + n;
            String head = name.length() + suffix.length() > maxNameLen
                    ? name.substring(0, Math.max(1, maxNameLen - suffix.length()))
                    : name;
            String candidate = head + suffix;
            if (!nameTaken(owner, candidate)) return candidate;
        }
        return name + " " + System.currentTimeMillis() % 1000;
    }

    public static synchronized boolean rename(Claim c, String newName) {
        if (c == null) return false;
        c.name = newName;
        save();
        return true;
    }

    public static synchronized boolean addMember(Claim c, String member) {
        return addMember(c, member, null);
    }

    /**
     * Adds a trusted user by name and, when the player is online, by their
     * exact UUID. The name list stays for display; the UUID list is what the
     * protection checks go through.
     *
     * @return true when either mirror changed and was persisted
     */
    public static synchronized boolean addMember(Claim c, String member, UUID memberUuid) {
        if (c == null) return false;
        boolean addedName = c.addMember(member);
        boolean addedUuid = memberUuid != null
                ? c.addMemberUuid(memberUuid)
                : c.addMemberUuid(Claim.offlineUuid(member));
        boolean changed = addedName || addedUuid;
        if (changed) save();
        return changed;
    }

    public static synchronized boolean removeMember(Claim c, String member) {
        return removeMember(c, member, null);
    }

    /**
     * Removes a trusted user from both mirrors. When the exact UUID is known
     * (player online) it is removed directly; the offline-UUID of the typed
     * name is removed as well, so legacy entries added by name only still go.
     *
     * @return true when either mirror changed and was persisted
     */
    public static synchronized boolean removeMember(Claim c, String member, UUID memberUuid) {
        if (c == null) return false;
        boolean removedName = c.removeMember(member);
        boolean removedUuid = c.removeMemberUuid(Claim.offlineUuid(member))
                || (memberUuid != null && c.removeMemberUuid(memberUuid));
        boolean changed = removedName || removedUuid;
        if (changed) save();
        return changed;
    }

    public static synchronized void setPvp(Claim c, boolean allow) {
        if (c == null) return;
        c.allowPvp = allow;
        save();
    }

    public static synchronized void setMobGriefing(Claim c, boolean allow) {
        if (c == null) return;
        c.allowMobGriefing = allow;
        save();
    }

    public static synchronized void setMobDamage(Claim c, boolean allow) {
        if (c == null) return;
        c.allowMobDamage = allow;
        save();
    }

    public static synchronized void setExplosions(Claim c, boolean allow) {
        if (c == null) return;
        c.allowExplosions = allow;
        save();
    }

    public static synchronized void setFluidFlow(Claim c, boolean allow) {
        if (c == null) return;
        c.allowFluidFlow = allow;
        save();
    }

    public static synchronized void setPistons(Claim c, boolean allow) {
        if (c == null) return;
        c.allowPistons = allow;
        save();
    }

    public static synchronized void setHoppers(Claim c, boolean allow) {
        if (c == null) return;
        c.allowHoppers = allow;
        save();
    }

    public static synchronized void setFireSpread(Claim c, boolean allow) {
        if (c == null) return;
        c.allowFireSpread = allow;
        save();
    }

    public static synchronized void setGuestContainers(Claim c, boolean allow) {
        if (c == null) return;
        c.guestContainers = allow;
        save();
    }

    public static synchronized void setGuestEntities(Claim c, boolean allow) {
        if (c == null) return;
        c.guestEntities = allow;
        save();
    }

    /** Seals or unseals a claim. Owner and members lose access while sealed. */
    public static synchronized void setArrested(Claim c, boolean arrested, String reason) {
        if (c == null) return;
        c.arrested = arrested;
        c.arrestReason = arrested && reason != null ? reason : "";
        save();
    }

    public static synchronized void setGuestDoors(Claim c, boolean allow) {
        if (c == null) return;
        c.guestDoors = allow;
        save();
    }

    private ClaimManager() {}
}
