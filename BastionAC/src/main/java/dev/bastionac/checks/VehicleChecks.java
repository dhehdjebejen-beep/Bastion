package dev.bastionac.checks;

import dev.bastionac.BastionAC;
import dev.bastionac.core.Action;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.MathUtil;
import dev.bastionac.core.PlayerData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Vehicle movement — the one packet path the engine never looked at.
 *
 * <p>While a player rides something, their own {@code PlayerMove} packets stop
 * carrying the real position: the vehicle is the moving object, and the client
 * is authoritative for it through {@code VehicleMoveC2SPacket}. Vanilla only
 * refuses a vehicle move of more than a hundred blocks, so everything under
 * that — BoatFly, VehicleSpeed, EntityControl, flying a minecart off the
 * rails — arrived completely unexamined.
 *
 * <p>Two rules, both measured against the vehicle's own physics rather than a
 * flat constant:
 * <ul>
 *   <li><b>Speed.</b> Horizontal distance per packet against a ceiling derived
 *       from the vehicle type, with ice, rails and the server's own knockback
 *       budget accounted for. A boat on blue ice is genuinely fast, so the ice
 *       allowance is generous; a boat in open air is not.</li>
 *   <li><b>Ascent.</b> Nothing rideable climbs under its own power. Rising for
 *       several consecutive packets with no support beneath the hull, no
 *       liquid, no climbable and no server-granted velocity is BoatFly — a
 *       vanilla boat has no thrust and simply falls.</li>
 * </ul>
 *
 * <p>Both are buffered and both bail out on anything ambiguous (dismount,
 * vehicle swap, teleport, server lag, a fresh mount), so ordinary riding —
 * including a horse jumping and a minecart cresting a slope — never flags.
 */
public final class VehicleChecks {

    /** Packets of ascent before the climb counts as powered flight. */
    private static final int ASCENT_PACKETS = 6;
    /** Ticks after mounting during which physics is still settling. */
    private static final long MOUNT_GRACE_TICKS = 20;

    public static Action onVehicleMove(ServerPlayerEntity player, PlayerData d, VehicleMoveC2SPacket packet) {
        long tick = BastionAC.serverTick();
        if (player.isCreative() || player.isSpectator()) return Action.NONE;
        if (d.isFullyExempt(tick) || BastionAC.isServerLagging(tick) || BastionAC.isAuthFrozen(player)) {
            d.vehicleInit = false;
            return Action.NONE;
        }

        Entity vehicle = player.getRootVehicle();
        if (vehicle == null || vehicle == player) {
            d.vehicleInit = false;
            return Action.NONE;
        }
        // Only the pilot's packets describe the vehicle; a passenger's are ignored
        // by vanilla anyway, and judging them would flag the wrong player.
        if (vehicle.getControllingPassenger() != player) {
            d.vehicleInit = false;
            return Action.NONE;
        }

        if (d.lastVehicleId != vehicle.getId()) {
            d.lastVehicleId = vehicle.getId();
            d.vehicleInit = false;
            d.vehicleSinceTick = tick;
            d.vehicleAirTicks = 0;
            d.vehicleBuffer = 0;
            d.vehicleAirBuffer = 0;
        }

        Vec3d pos = packet.position();
        if (!d.vehicleInit) {
            anchor(d, pos);
            return Action.NONE;
        }

        double dx = pos.x - d.lastVehX;
        double dy = pos.y - d.lastVehY;
        double dz = pos.z - d.lastVehZ;
        double hDist = MathUtil.horizontal(dx, dz);

        // A server-side reposition (teleport, portal, dismount-fling) moves the
        // vehicle itself, so the authoritative position sits far from our last
        // sample. Re-anchor instead of flagging — a client-side jump cannot move
        // the server's copy of the vehicle.
        double sdx = vehicle.getX() - d.lastVehX;
        double sdz = vehicle.getZ() - d.lastVehZ;
        if (MathUtil.horizontal(sdx, sdz) > 8.0 || Math.abs(vehicle.getY() - d.lastVehY) > 8.0) {
            anchor(d, pos);
            return Action.NONE;
        }

        boolean settling = tick - d.vehicleSinceTick < MOUNT_GRACE_TICKS;
        boolean velocityActive = tick < d.velocityGraceUntil;
        World world = player.getEntityWorld();
        Env env = scan(world, vehicle);

        Action action = Action.NONE;

        // ---------------- SPEED ----------------
        if (!settling) {
            double limit = baseSpeed(vehicle);
            if (env.ice) limit *= 5.0;       // blue ice boats genuinely fly along
            else if (env.rail) limit *= 2.2; // powered rails push a minecart hard
            if (env.liquid) limit *= 1.2;
            if (velocityActive) limit += Math.max(0, d.velocityBudget);
            limit *= Math.max(0.9, BastionAC.config().general.speedToleranceMultiplier);

            if (hDist > limit) {
                d.vehicleBuffer += 1;
                if (d.vehicleBuffer > 4) {
                    d.vehicleBuffer = 2;
                    if (BastionAC.flag(player, d, CheckType.VEHICLE, String.format(
                            "%s %.2f бл/пакет (лимит %.2f)", nameOf(vehicle), hDist, limit))) {
                        action = Action.max(action, Action.SETBACK);
                    }
                }
            } else {
                d.vehicleBuffer = Math.max(0, d.vehicleBuffer - 0.5);
            }
        }

        // ---------------- ASCENT (BoatFly) ----------------
        boolean supported = env.support || env.liquid || env.climbable || env.rail;
        if (supported || settling || velocityActive || vehicle.hasPassenger(e -> false)) {
            d.vehicleAirTicks = 0;
        } else {
            d.vehicleAirTicks++;
        }
        // A rideable entity that can genuinely fly under its own power is exempt:
        // the happy ghast is a mount, not an exploit.
        boolean canFlyItself = canFly(vehicle);
        if (!canFlyItself && !supported && !settling && !velocityActive && dy > 0.02) {
            if (d.vehicleAirTicks > ASCENT_PACKETS) {
                d.vehicleAirBuffer += 1;
                if (d.vehicleAirBuffer > 2) {
                    d.vehicleAirBuffer = 1;
                    if (BastionAC.flag(player, d, CheckType.VEHICLE, String.format(
                            "%s поднимается без опоры: dy=%.3f, %d пакетов в воздухе",
                            nameOf(vehicle), dy, d.vehicleAirTicks))) {
                        action = Action.max(action, Action.SETBACK);
                    }
                }
            }
        } else if (dy <= 0.02) {
            d.vehicleAirBuffer = Math.max(0, d.vehicleAirBuffer - 0.5);
        }

        anchor(d, pos);
        if (action == Action.SETBACK && BastionAC.config().general.setbackEnabled) {
            // Put the vehicle back where the server still believes it is. The
            // server copy is authoritative and was never moved by the cheat.
            vehicle.requestTeleport(d.lastVehX, d.lastVehY, d.lastVehZ);
            anchor(d, new Vec3d(vehicle.getX(), vehicle.getY(), vehicle.getZ()));
        }
        return action;
    }

    private static void anchor(PlayerData d, Vec3d pos) {
        d.vehicleInit = true;
        d.lastVehX = pos.x;
        d.lastVehY = pos.y;
        d.lastVehZ = pos.z;
    }

    /** Per-tick horizontal ceiling for a vehicle on flat ground, before terrain bonuses. */
    private static double baseSpeed(Entity vehicle) {
        if (vehicle instanceof BoatEntity) return 0.65;
        if (vehicle instanceof AbstractMinecartEntity) return 0.65;
        if (vehicle instanceof net.minecraft.entity.passive.AbstractHorseEntity) return 0.80;
        if (vehicle instanceof net.minecraft.entity.passive.PigEntity
                || vehicle instanceof net.minecraft.entity.passive.StriderEntity) return 0.45;
        return 1.0; // unknown rideable: be generous, this check is not its judge
    }

    /** Entities that legitimately move under their own power in the air. */
    private static boolean canFly(Entity vehicle) {
        String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(vehicle.getType()).getPath();
        return id.contains("ghast") || id.contains("dragon") || id.contains("allay")
                || id.contains("bee") || id.contains("phantom") || id.contains("parrot");
    }

    private static String nameOf(Entity vehicle) {
        return net.minecraft.registry.Registries.ENTITY_TYPE.getId(vehicle.getType()).getPath();
    }

    private static final class Env {
        boolean support;
        boolean liquid;
        boolean climbable;
        boolean ice;
        boolean rail;
    }

    /** Probes the cells the hull actually rests in — collision shapes, not "is it air". */
    private static Env scan(World world, Entity vehicle) {
        Env env = new Env();
        Box box = vehicle.getBoundingBox();
        try {
            // Anything solid in the thin slab just beneath the hull is support.
            Box below = new Box(box.minX, box.minY - 0.35, box.minZ, box.maxX, box.minY + 0.05, box.maxZ);
            if (!world.isSpaceEmpty(vehicle, below)) env.support = true;

            for (BlockPos pos : BlockPos.iterate(
                    net.minecraft.util.math.MathHelper.floor(box.minX), net.minecraft.util.math.MathHelper.floor(box.minY - 0.5),
                    net.minecraft.util.math.MathHelper.floor(box.minZ), net.minecraft.util.math.MathHelper.floor(box.maxX),
                    net.minecraft.util.math.MathHelper.floor(box.maxY), net.minecraft.util.math.MathHelper.floor(box.maxZ))) {
                var state = world.getBlockState(pos);
                if (state.isAir()) continue;
                if (!state.getFluidState().isEmpty()) env.liquid = true;
                if (state.isIn(BlockTags.CLIMBABLE)) env.climbable = true;
                if (state.isIn(BlockTags.ICE)) env.ice = true;
                if (state.isIn(BlockTags.RAILS)) { env.rail = true; env.support = true; }
            }
        } catch (RuntimeException e) {
            // Unloaded chunk edge — treat as supported so nothing is convicted on missing data.
            env.support = true;
        }
        return env;
    }

    private VehicleChecks() {}
}
