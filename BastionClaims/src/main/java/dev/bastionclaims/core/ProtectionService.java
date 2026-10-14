package dev.bastionclaims.core;

import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.model.Claim;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central "may this happen?" logic for claim protection. Stateless except for
 * the admin bypass set and a small per-player message throttle.
 */
public final class ProtectionService {

    /**
     * Per-player override of the operator bypass, session only. TRUE = forced
     * on, FALSE = forced off, absent = whatever the config grants this player.
     * It is an override rather than a plain "on" set so an operator can switch
     * protection ON for themselves and test a claim exactly as a guest would.
     * Before, {@code opBypassProtection} in the config silently won: every
     * operator walked through every claim, {@code /claim why} answered
     * РАЗРЕШЕНО whatever the flags said, and the only way to see the real
     * picture was a second, non-op account.
     */
    private static final ConcurrentHashMap<UUID, Boolean> OVERRIDE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_NOTIFY = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ helpers

    public static String dimOf(ServerPlayerEntity player) {
        return player.getEntityWorld().getRegistryKey().getValue().toString();
    }

    public static int playHours(ServerPlayerEntity player) {
        int ticks = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME));
        return ticks / 20 / 3600;
    }

    public static boolean isOp(ServerPlayerEntity player) {
        return net.minecraft.server.command.CommandManager.ADMINS_CHECK.allows(player.getPermissions());
    }

    /** Whether this player currently ignores other people's claims. */
    public static boolean bypassesProtection(ServerPlayerEntity player) {
        Boolean forced = OVERRIDE.get(player.getUuid());
        if (forced != null) return forced;
        return configBypass(player);
    }

    /** What the config alone grants this player, ignoring the session toggle. */
    public static boolean configBypass(ServerPlayerEntity player) {
        ClaimConfig cfg = BastionClaims.config();
        return cfg != null && cfg.opBypassProtection && isOp(player);
    }

    /**
     * Only the deliberate {@code /claim bypass} toggle — not the blanket
     * "operators ignore protection" config switch. Used where an operator must
     * confirm intent, e.g. running WorldEdit through someone else's claim.
     */
    public static boolean hasExplicitBypass(ServerPlayerEntity player) {
        return Boolean.TRUE.equals(OVERRIDE.get(player.getUuid()));
    }

    /** Flips the effective state for this session and returns the new one. */
    public static boolean toggleBypass(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        boolean want = !bypassesProtection(player);
        if (want == configBypass(player)) OVERRIDE.remove(id);
        else OVERRIDE.put(id, want);
        return want;
    }

    /** One line on why the bypass is on for this player, or {@code null} when it is off. */
    public static String bypassReason(ServerPlayerEntity player) {
        if (!bypassesProtection(player)) return null;
        if (hasExplicitBypass(player)) return "включён командой /claim bypass — она же выключает";
        return "ты оператор, а в config.json стоит opBypassProtection=true; /claim bypass выключит обход для тебя";
    }

    public static void forget(UUID player) {
        OVERRIDE.remove(player);
        LAST_NOTIFY.remove(player);
    }

    // ------------------------------------------------------------------ decisions

    /** The claim that would block {@code player} building at this block, or null. */
    public static Claim blockingBuild(ServerPlayerEntity player, String dim, int x, int y, int z) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.blockProtection) return null;
        Claim c = ClaimManager.claimAt(dim, x, y, z);
        if (c == null || bypassesProtection(player)) return null;
        // Sealed by the state: the owner is a stranger here until the tax is paid.
        if (c.arrested) return c;
        if (c.can(player.getUuid(), Claim.Perm.BUILD)) return null;
        return c;
    }

    /** Kind of interaction, used to apply the matching guest flag. */
    public enum Interaction { CONTAINER, DOOR, ENTITY, OTHER }

    /** The role permission an interaction needs. Using any other block (a bed, a lectern, bone meal) counts as building. */
    public static Claim.Perm permFor(Interaction cat) {
        return switch (cat) {
            case CONTAINER -> Claim.Perm.CONTAINERS;
            case DOOR -> Claim.Perm.DOORS;
            case ENTITY -> Claim.Perm.ENTITIES;
            case OTHER -> Claim.Perm.BUILD;
        };
    }

    /** The claim that would block {@code player} interacting at this block, or null. */
    public static Claim blockingInteract(ServerPlayerEntity player, String dim, int x, int y, int z) {
        return blockingInteract(player, dim, x, y, z, Interaction.OTHER);
    }

    /** As {@link #blockingInteract}, honouring the per-claim guest flag for the category. */
    public static Claim blockingInteract(ServerPlayerEntity player, String dim, int x, int y, int z, Interaction cat) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.interactProtection) return null;
        Claim c = ClaimManager.claimAt(dim, x, y, z);
        if (c == null || bypassesProtection(player)) return null;
        if (c.arrested) return c;
        // Owner, full member, or a role that grants this category.
        if (c.can(player.getUuid(), permFor(cat))) return null;
        // Otherwise (a stranger, or a member whose role does not cover it) the
        // guest flags still open what they open to everybody.
        if (cat == Interaction.CONTAINER && c.guestContainers) return null;
        if (cat == Interaction.DOOR && c.guestDoors) return null;
        if (cat == Interaction.ENTITY && c.guestEntities) return null;
        return c;
    }

    /**
     * Whether damage to {@code victim} from a hostile mob (melee, projectile or
     * mob-triggered explosion) must be cancelled because the victim stands in a
     * claim that keeps mob damage off.
     */
    public static boolean shouldCancelMobDamage(ServerPlayerEntity victim, DamageSource source) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.mobDamageProtection) return false;
        Entity attacker = source.getAttacker();
        boolean mob = attacker instanceof LivingEntity && !(attacker instanceof PlayerEntity);
        if (!mob) return false;
        Claim c = ClaimManager.claimAt(dimOf(victim), victim.getBlockX(), victim.getBlockY(), victim.getBlockZ());
        return c != null && !c.allowMobDamage;
    }

    /**
     * Whether damage dealt by another player must be cancelled because the
     * victim stands in a claim with PvP off.
     *
     * <p>The attack event only ever sees a melee swing. An arrow, a trident, a
     * splash potion or a firework all arrive as damage with the shooter as the
     * <em>attacker</em> and never touch that event — which meant PvP protection
     * could be walked around with a bow. Checking here covers every delivery
     * method at once.
     */
    public static ServerPlayerEntity playerDamageBlockedBy(ServerPlayerEntity victim, DamageSource source) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.pvpProtection) return null;
        if (!(source.getAttacker() instanceof ServerPlayerEntity attacker)) return null;
        if (attacker == victim) return null;                       // own arrow falling back
        if (bypassesProtection(attacker)) return null;
        Claim c = ClaimManager.claimAt(dimOf(victim), victim.getBlockX(), victim.getBlockY(), victim.getBlockZ());
        if (c == null || c.allowPvp) return null;
        return attacker;
    }

    /**
     * Whether damage to a non-player entity inside a claim must be cancelled.
     *
     * <p>The attack event only ever sees a melee swing, and the explosion hook
     * only ever filters blocks — so armour stands, item frames, paintings,
     * animals and chest minecarts inside a claim could be destroyed from outside
     * with a bow, a splash potion or a stick of TNT, and their contents picked
     * up off the floor. This is the one place every delivery method converges.
     *
     * <p>Hostile mobs are never protected: a claim must not become a safe pen
     * for the creeper that wandered into it.
     */
    public static boolean entityDamageBlocked(Entity victim, DamageSource source) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.entityProtection) return false;
        if (victim == null || victim instanceof PlayerEntity) return false;   // PvP path handles players
        if (victim instanceof net.minecraft.entity.mob.HostileEntity) return false;

        Claim c = ClaimManager.claimAt(victim.getEntityWorld().getRegistryKey().getValue().toString(),
                victim.getBlockX(), victim.getBlockY(), victim.getBlockZ());
        if (c == null) return false;

        // Explosions follow the claim's explosion flags, exactly like the blocks
        // around the victim — whoever lit the fuse. Before, a player behind the
        // blast (the igniter of TNT, the shooter of a fireball) was judged by
        // trust instead, so with the bypass on — or as a member — a blast that
        // spared every block still flattened the armour stands next to them.
        if (source != null && source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_EXPLOSION)) {
            return explosionProtected(cfg.protection, c, source.getSource());
        }
        // An anvil or a stalactite dropped in from outside the claim.
        if (source != null && source.getSource() instanceof net.minecraft.entity.FallingBlockEntity falling) {
            BlockPos from = falling.getFallingBlockPos();
            return cfg.protection.blockProtection && (from == null || !c.contains(from.getX(), from.getY(), from.getZ()));
        }
        // A player behind it (directly, or as the owner of the arrow/potion):
        // trusted players may do as they like, everyone else may not.
        Entity attacker = source == null ? null : source.getAttacker();
        if (attacker instanceof ServerPlayerEntity p) {
            return !c.can(p.getUuid(), Claim.Perm.ENTITIES) && !bypassesProtection(p);
        }
        // Anything else (fall damage, a cactus, another mob) is left to vanilla.
        return false;
    }

    /**
     * Whether an explosion must leave {@code victim} alone entirely — no damage
     * and no knockback. Called by {@code ExplosionImplMixin} before vanilla even
     * looks at the entity, which is the only place that covers every entity type
     * at once: armour stands override {@code damage()} without calling up to
     * {@code LivingEntity}, so Fabric's damage event never sees them, and the
     * blast would still have thrown the protected entity out of the claim.
     */
    public static boolean explosionSparesEntity(ServerWorld world, Entity explosionSource, Entity victim) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.entityProtection) return false;
        if (victim == null || victim instanceof PlayerEntity) return false;
        if (victim instanceof net.minecraft.entity.mob.HostileEntity) return false;
        Claim c = ClaimManager.claimAt(world.getRegistryKey().getValue().toString(),
                victim.getBlockX(), victim.getBlockY(), victim.getBlockZ());
        return c != null && explosionProtected(cfg.protection, c, explosionSource);
    }

    /**
     * The one explosion rule for blocks and entities: a mob's blast honours the
     * mob-grief flags, anything else (TNT, fireballs, beds, wind charges) the
     * explosion flags.
     */
    static boolean explosionProtected(ClaimConfig.Protection p, Claim c, Entity explosionSource) {
        return explosionProtected(p, c, isMob(explosionSource));
    }

    /** Pure decision — unit-testable. */
    static boolean explosionProtected(ClaimConfig.Protection p, Claim c, boolean mobCaused) {
        if (p == null || c == null) return false;
        return mobCaused
                ? p.mobGriefProtection && !c.allowMobGriefing
                : p.explosionProtection && !c.allowExplosions;
    }

    private static boolean isMob(Entity e) {
        return e instanceof LivingEntity && !(e instanceof PlayerEntity);
    }

    /** True when the attacker may hit the victim at the given location. */
    public static boolean canPvp(ServerPlayerEntity attacker, String dim, int x, int y, int z) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.pvpProtection) return true;
        if (bypassesProtection(attacker)) return true;
        Claim c = ClaimManager.claimAt(dim, x, y, z);
        if (c == null) return true;
        return c.allowPvp;
    }

    /**
     * Whether an explosion caused by {@code source} must spare the block at
     * {@code pos}. Mob-caused explosions honour the mob-grief master + per-claim
     * flag; all others honour the explosion master switch.
     */
    public static boolean shouldProtectExplosion(ServerWorld world, Entity source, BlockPos pos) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null) return false;
        String dim = world.getRegistryKey().getValue().toString();
        Claim c = ClaimManager.claimAt(dim, pos.getX(), pos.getY(), pos.getZ());
        return explosionProtected(cfg.protection, c, source);
    }

    // ------------------------------------------------------------------ trees

    /**
     * Whether a sapling at {@code pos} could drop logs or leaves into a claim it
     * does not belong to. A sapling already standing inside a claim is fine —
     * the owner is growing their own tree.
     */
    public static boolean treeWouldReachForeignClaim(ServerWorld world, BlockPos pos) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.treeProtection) return false;
        String dim = world.getRegistryKey().getValue().toString();
        Claim home = ClaimManager.claimAt(dim, pos.getX(), pos.getY(), pos.getZ());

        int r = Math.max(1, cfg.protection.treeGuardRadius);
        int h = Math.max(1, cfg.protection.treeGuardHeight);
        int x0 = pos.getX() - r, y0 = pos.getY(), z0 = pos.getZ() - r;
        int x1 = pos.getX() + r, y1 = pos.getY() + h, z1 = pos.getZ() + r;
        if (home == null) return ClaimManager.firstOverlap(dim, x0, y0, z0, x1, y1, z1, null) != null;
        // The owner's own sapling may lean over its own border — but not into
        // the neighbour's claim next door, which the old check never looked at.
        return ClaimManager.firstForeignOverlap(dim, x0, y0, z0, x1, y1, z1, home.owner) != null;
    }

    // ------------------------------------------------------------------ fire

    /**
     * Whether fire at this position must not tick or spread, because it is
     * burning inside a claim whose owner has not enabled fire spread. Used by
     * {@code FireBlockMixin} to cancel the scheduled tick; a fire on open ground
     * or inside the owner's own claim ticks as normal.
     */
    public static boolean fireSpreadBlocked(String dim, int x, int y, int z) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null) return false;
        Claim c = ClaimManager.claimAt(dim, x, y, z);
        return fireSpreadBlocked(cfg.protection, c);
    }

    /** Pure decision — unit-testable. */
    static boolean fireSpreadBlocked(ClaimConfig.Protection p, Claim at) {
        if (p == null || !p.fireProtection) return false;
        return at != null && !at.allowFireSpread;
    }

    // ------------------------------------------------------------------ fluids

    /**
     * Whether a liquid must be stopped from flowing into the destination cell.
     * Fluid entering a protected claim is cancelled unless its source cell is
     * inside the <em>same</em> claim (so the owner's own liquids still spread).
     */
    public static boolean fluidFlowBlocked(String dim, int dx, int dy, int dz, int sx, int sy, int sz) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null) return false;
        Claim dest = ClaimManager.claimAt(dim, dx, dy, dz);
        return fluidFlowBlocked(cfg.protection, dest, sx, sy, sz);
    }
    /** Pure decision (no Minecraft / no global state) — unit-testable. */
    static boolean fluidFlowBlocked(ClaimConfig.Protection p, Claim dest, int sx, int sy, int sz) {
        if (p == null || !p.fluidProtection) return false;
        if (dest == null || dest.allowFluidFlow) return false;
        return !dest.contains(sx, sy, sz); // external source flowing in → block
    }

    // ------------------------------------------------------------------ hoppers

    /**
     * Whether a hopper (block or minecart) at {@code (hx,hy,hz)} must be denied
     * access to the container at {@code (cx,cy,cz)} because that container is in
     * a protected claim the hopper does not belong to.
     */
    public static boolean hopperBlocked(String dim, int cx, int cy, int cz, int hx, int hy, int hz) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null) return false;
        Claim container = ClaimManager.claimAt(dim, cx, cy, cz);
        return hopperBlocked(cfg.protection, container, hx, hy, hz);
    }

    /**
     * As {@link #hopperBlocked} for a hopper that belongs to nobody, wherever it
     * stands (a hopper minecart off the rails): any protected claim container is
     * out of its reach.
     */
    public static boolean hopperBlockedForeign(String dim, int cx, int cy, int cz) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null) return false;
        if (!cfg.protection.hopperProtection) return false;
        Claim container = ClaimManager.claimAt(dim, cx, cy, cz);
        return container != null && !container.allowHoppers;
    }

    /** Pure decision — unit-testable. */
    static boolean hopperBlocked(ClaimConfig.Protection p, Claim container, int hx, int hy, int hz) {
        if (p == null || !p.hopperProtection) return false;
        if (container == null || container.allowHoppers) return false;
        return !container.contains(hx, hy, hz); // hopper outside the claim → block
    }

    // ------------------------------------------------------------------ pistons

    /**
     * Whether a piston based at {@code (px,py,pz)} must be denied moving/breaking
     * the block at {@code (cellX,cellY,cellZ)} because that cell lies in a
     * protected claim the piston is not part of.
     */
    public static boolean pistonCellBlocked(String dim, int cellX, int cellY, int cellZ,
                                            int px, int py, int pz) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null) return false;
        Claim cell = ClaimManager.claimAt(dim, cellX, cellY, cellZ);
        return pistonCellBlocked(cfg.protection, cell, px, py, pz);
    }

    /** Pure decision — unit-testable. */
    static boolean pistonCellBlocked(ClaimConfig.Protection p, Claim cell, int px, int py, int pz) {
        if (p == null || !p.pistonProtection) return false;
        if (cell == null || cell.allowPistons) return false;
        return !cell.contains(px, py, pz); // piston outside the affected claim → block
    }

    // ------------------------------------------------------------------ dispensers

    /**
     * Whether a dispenser at {@code (dx,dy,dz)} must not act on the cell at
     * {@code (cellX,cellY,cellZ)} because that cell lies in a claim the
     * dispenser is not part of. Follows the block-protection master switch:
     * whatever a dispenser does there is building or griefing.
     */
    public static boolean dispenserBlocked(String dim, int cellX, int cellY, int cellZ, int dx, int dy, int dz) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null) return false;
        Claim cell = ClaimManager.claimAt(dim, cellX, cellY, cellZ);
        if (!dispenserBlocked(cfg.protection, cell, dx, dy, dz)) return false;
        // The owner's machine in their own neighbouring claim is theirs to run.
        Claim home = ClaimManager.claimAt(dim, dx, dy, dz);
        return home == null || !home.owner.equalsIgnoreCase(cell.owner);
    }

    /** Pure decision — unit-testable. */
    static boolean dispenserBlocked(ClaimConfig.Protection p, Claim cell, int dx, int dy, int dz) {
        if (p == null || !p.blockProtection) return false;
        return cell != null && !cell.contains(dx, dy, dz);
    }

    // ------------------------------------------------------------------ world changes without a player hand

    private static Claim claimAt(net.minecraft.world.World world, BlockPos pos) {
        return ClaimManager.claimAt(world.getRegistryKey().getValue().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Whether a mob may not change the block at {@code pos}: an enderman lifting
     * or dropping a block, a wither or a ravager smashing through, a zombie
     * breaking a door, the dragon eating the island. Governed by the same
     * mob-grief switches as a creeper's blast.
     */
    public static boolean mobGriefBlockedAt(net.minecraft.world.World world, BlockPos pos) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.mobGriefProtection) return false;
        Claim c = claimAt(world, pos);
        return c != null && !c.allowMobGriefing;
    }

    /**
     * Whether {@code actor} may not alter the block at {@code pos} without a
     * click — trampling farmland, freezing water with Frost Walker. A player is
     * held to the build rule (members and the bypass may), anything else to the
     * mob-grief switches.
     */
    public static boolean actorBlockedAt(net.minecraft.world.World world, BlockPos pos, Entity actor) {
        if (actor instanceof ServerPlayerEntity p) {
            return blockingBuild(p, world.getRegistryKey().getValue().toString(), pos.getX(), pos.getY(), pos.getZ()) != null;
        }
        return mobGriefBlockedAt(world, pos);
    }

    /**
     * Whether a projectile may not act on the block it struck: a burning arrow
     * priming TNT or lighting a campfire or candles, an arrow ringing a bell,
     * powering a target block, breaking dripstone or a chorus flower, a fire
     * charge setting the roof alight. Judged by who loosed it — a member's own
     * shot is theirs to make; a stranger's, a dispenser's (no owner) or a
     * skeleton's is not.
     */
    public static boolean projectileBlockedAt(net.minecraft.world.World world, BlockPos pos, Entity owner) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null) return false;
        Claim c = claimAt(world, pos);
        if (c == null) return false;
        if (owner instanceof ServerPlayerEntity p) {
            return cfg.protection.blockProtection && (c.arrested || (!c.can(p.getUuid(), Claim.Perm.BUILD) && !bypassesProtection(p)));
        }
        if (isMob(owner)) return cfg.protection.mobGriefProtection && !c.allowMobGriefing;
        return cfg.protection.blockProtection;
    }

    /**
     * Whether a falling block (sand, gravel, concrete powder, an anvil, a
     * stalactite) may not land at {@code landing}: it started outside the claim
     * it would come to rest in — dropped from above a 3D claim or catapulted
     * over the border. It then breaks into its item, as vanilla does when there
     * is no room.
     */
    public static boolean fallingBlockBlocked(net.minecraft.world.World world, BlockPos landing, BlockPos origin) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.blockProtection) return false;
        Claim c = claimAt(world, landing);
        return c != null && (origin == null || !c.contains(origin.getX(), origin.getY(), origin.getZ()));
    }

    /**
     * Whether a lightning bolt called down by Channeling may not touch this
     * spot — set fire there or strike the creature standing on it (a villager
     * turning into a witch, a pig into a piglin). Natural lightning (no
     * channeler) is weather, not griefing, and is left alone.
     */
    public static boolean channeledLightningBlockedAt(net.minecraft.world.World world, BlockPos pos, ServerPlayerEntity channeler) {
        if (channeler == null) return false;
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null) return false;
        Claim c = claimAt(world, pos);
        return c != null && (c.arrested || (!c.can(channeler.getUuid(), Claim.Perm.BUILD) && !bypassesProtection(channeler)));
    }

    /**
     * Whether a fishing rod may not pull this entity: an animal, a villager, an
     * armour stand or an item lying inside a claim, hooked by someone without
     * the {@code entities} permission there. Without this a stranger standing
     * outside reeled the inhabitants of a base out over the wall.
     */
    public static boolean fishingPullBlocked(Entity hooked, Entity owner) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.entityProtection || hooked == null) return false;
        if (hooked instanceof PlayerEntity || hooked instanceof net.minecraft.entity.mob.HostileEntity) return false;
        Claim c = claimAt(hooked.getEntityWorld(), hooked.getBlockPos());
        if (c == null) return false;
        if (!(owner instanceof ServerPlayerEntity p)) return true;
        return c.arrested || (!c.can(p.getUuid(), Claim.Perm.ENTITIES) && !bypassesProtection(p));
    }

    /**
     * Whether sculk spreading from the catalyst at {@code catalyst} may not
     * convert the block at {@code target}: the block lies in a claim the
     * catalyst is not part of. A catalyst outside the border, fed by mobs
     * killed next to it, used to grow sculk and sensors into the base.
     */
    public static boolean sculkSpreadBlocked(net.minecraft.world.World world, BlockPos target, BlockPos catalyst) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.protection.blockProtection) return false;
        Claim c = claimAt(world, target);
        return c != null && (catalyst == null || !c.contains(catalyst.getX(), catalyst.getY(), catalyst.getZ()));
    }

    // ------------------------------------------------------------------ messaging

    /** Sends a message at most once per ~1.5s per player (anti-spam on repeated denials). */
    public static void notifyThrottled(ServerPlayerEntity player, net.minecraft.text.Text text) {
        long now = System.currentTimeMillis();
        Long last = LAST_NOTIFY.get(player.getUuid());
        if (last != null && now - last < 1500) return;
        LAST_NOTIFY.put(player.getUuid(), now);
        player.sendMessage(text, true); // action bar — unobtrusive
    }

    private ProtectionService() {}
}
