package dev.bastionac.argus.features;

import dev.bastionac.telemetry.Prim;
import dev.bastionac.telemetry.SessionData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.bastionac.telemetry.TelemetryFormat.*;

/** Boat physics from the server's one-second STATE snapshots.
 *
 * <p>R_MOVE describes the passenger, not the vehicle. STATE is authoritative,
 * but its block is only one sample below the passenger, not the hull footprint.
 * Consequently unknown surfaces and supported/air transitions are not evidence.
 * No check flags or player identity enter this calculation.
 */
public final class VehicleFeatures {
    public enum SurfaceKind { AIR, LIQUID, ICE, LAND, UNKNOWN }
    public record Surface(SurfaceKind kind, double slipperiness) {}
    public interface Context {
        /** STATE stores the registry raw id plus one. Unknown/modded types must not masquerade as boats. */
        String vehicle(int storedType);
        Surface surface(int rawState);
    }
    public record Episode(String key, long from, long to, double seconds, double maxSpeed, double maxRise) {}
    public record Diagnostics(Map<String, Double> secondsByType, Map<String, Double> evaluatedSeconds,
                              Map<String, Double> suspiciousSeconds, List<Episode> episodes,
                              int mountedSnapshots, double unknownSurfaceSeconds) {}

    private static final String LAND = "veh.land_speed", RISE = "veh.rise", HOVER = "veh.hover";
    private static final int EXCLUDE = ST_GLIDING | ST_FLYING | ST_SWIMMING | ST_WATER | ST_LAVA
            | ST_TELEPORT_PENDING | ST_STAFF_FROZEN | ST_AUTH_FROZEN;

    private VehicleFeatures() {}

    public static void analyze(SessionData s, FeatureSet fs, Context context) {
        measure(s, fs, context);
    }

    public static Diagnostics inspect(SessionData s, Context context) {
        return measure(s, null, context);
    }

    public static boolean isBoat(String type) {
        return type != null && type.startsWith("minecraft:")
                && (type.endsWith("_boat") || type.endsWith("_raft"));
    }

    private static Diagnostics measure(SessionData s, FeatureSet fs, Context context) {
        Map<String, Double> volume = new LinkedHashMap<>(), evaluated = new LinkedHashMap<>(), bad = new LinkedHashMap<>();
        List<Episode> episodes = new ArrayList<>();
        Map<String, Integer> lastEpisode = new LinkedHashMap<>();
        int snapshots = 0;
        double unknown = 0, drySeconds = 0, airSeconds = 0, previousSpeed = 0;
        double minKnownY = Double.NaN, maxAirY = Double.NaN;
        long mountedAt = 0, lastWet = Long.MIN_VALUE / 2;
        SessionData.State previous = null;
        for (SessionData.State b : s.states) {
            if (b.has(ST_VEHICLE)) snapshots++;
            SessionData.State a = previous;
            previous = b;
            double dt = a == null ? 0 : (b.t - a.t) / 1000.0;
            boolean consecutive = a != null && a.has(ST_VEHICLE) && b.has(ST_VEHICLE)
                    && a.vehicleType == b.vehicleType && a.world == b.world && dt >= 0.25 && dt <= 2.5
                    && !between(s.vehicleT, a.t, b.t);
            if (!consecutive) {
                drySeconds = airSeconds = previousSpeed = 0;
                minKnownY = maxAirY = Double.NaN;
                mountedAt = b.t;
                continue;
            }
            String type = context.vehicle(b.vehicleType);
            volume.merge(type == null ? "unknown:" + b.vehicleType : type, dt, Double::sum);
            Surface sa = context.surface(a.blockAtFeet), sb = context.surface(b.blockAtFeet);
            if (sa.kind == SurfaceKind.UNKNOWN || sb.kind == SurfaceKind.UNKNOWN) unknown += dt;
            if (!isBoat(type)) continue;
            boolean wet = sa.kind == SurfaceKind.LIQUID || sb.kind == SurfaceKind.LIQUID
                    || sa.kind == SurfaceKind.ICE || sb.kind == SurfaceKind.ICE;
            if (wet) lastWet = b.t;
            double dx = b.x - a.x, dz = b.z - a.z, dy = b.y - a.y;
            double speed = Math.hypot(dx, dz) / dt;
            // Dimension 3 has mod-defined movement. STATE cannot identify the mod's rules.
            boolean excluded = b.world == 3 || a.gameMode == 3 || b.gameMode == 3
                    || ((a.flags | b.flags) & EXCLUDE) != 0 || a.mspt > 55 || b.mspt > 55
                    || a.lastTickMs > 150 || b.lastTickMs > 150
                    || between(s.tpSentT, a.t - 2000, b.t) || between(s.corrT, a.t - 2000, b.t)
                    || between(s.velT, a.t - 5000, b.t) || between(s.lifeT, a.t, b.t)
                    || Math.abs(dy) > 16 || speed > 80;
            if (excluded) {
                drySeconds = airSeconds = 0;
                minKnownY = maxAirY = Double.NaN;
                previousSpeed = speed;
                continue;
            }
            boolean land = sa.kind == SurfaceKind.LAND && sb.kind == SurfaceKind.LAND && Math.abs(dy) < 0.25;
            if (land) {
                drySeconds += dt;
                // 1.21.11 updateVelocity copies nearbySlipperiness before halving
                // the field for a controlling player, so THIS tick uses the full
                // ground friction. getLocation refreshes it on the next tick.
                // Paddling adds 0.04/tick: grass is 0.04/(1-0.6)=0.10 blocks/tick.
                // Carry the observed entry speed through the deceleration sum, plus
                // the maximum seat-offset change: the rear seat is 0.6 blocks
                // from the hull centre and can rotate to its opposite side.
                double friction = Math.max(0.05, Math.min(0.9, Math.max(sa.slipperiness, sb.slipperiness)));
                double ticks = Math.max(1, b.serverTick - a.serverTick);
                double terminal = 0.04 / (1 - friction);
                double entry = Math.max(previousSpeed / 20, terminal);
                double inertia = Math.max(0, entry - terminal) * friction * (1 - Math.pow(friction, ticks)) / (1 - friction);
                double limitDistance = terminal * ticks + inertia + 1.2 + 1e-6;
                // First three seconds after a mount/surface change are deliberately unjudged.
                if (drySeconds >= 3 && b.t - mountedAt >= 3000) {
                    observe(LAND, Math.hypot(dx, dz) > limitDistance, a, b, dt, speed, dy / dt,
                            fs, evaluated, bad, episodes, lastEpisode);
                }
                minKnownY = Double.isNaN(minKnownY) ? Math.min(a.y, b.y) : Math.min(minKnownY, Math.min(a.y, b.y));
            } else drySeconds = 0;

            boolean air = sa.kind == SurfaceKind.AIR && sb.kind == SurfaceKind.AIR;
            if (air) {
                airSeconds += dt;
                maxAirY = Double.isNaN(maxAirY) ? Math.max(a.y, b.y) : Math.max(maxAirY, Math.max(a.y, b.y));
                // A one-block edge, wave, bubble column or a water-to-land transition
                // cannot establish a sustained airborne ascent from these snapshots.
                if (airSeconds >= 2 && b.t - lastWet > 5000 && b.t - mountedAt >= 3000) {
                    observe(RISE, dy / dt > 0.6, a, b, dt, speed, dy / dt,
                            fs, evaluated, bad, episodes, lastEpisode);
                }
                // Air at the sampled point alone is NOT proof of no hull support:
                // slabs, stairs and shore edges also produce it. Require an observed
                // ascent at least two blocks above a dry supported height first.
                boolean altitudeKnown = !Double.isNaN(minKnownY) && maxAirY - minKnownY > 2.0 && b.y - minKnownY > 1.5;
                if (altitudeKnown && airSeconds >= 3 && b.t - lastWet > 5000) {
                    observe(HOVER, Math.abs(dy) / dt < 0.08, a, b, dt, speed, dy / dt,
                            fs, evaluated, bad, episodes, lastEpisode);
                }
            } else {
                airSeconds = 0;
                maxAirY = Double.NaN;
                if (wet || sb.kind == SurfaceKind.UNKNOWN) minKnownY = Double.NaN;
            }
            previousSpeed = speed;
        }
        // One sparse displacement cannot distinguish hull motion from a seat or
        // mount transition. Require a continuous anomaly for two STATE intervals.
        // This is persistence filtering, not a claim that the record names a driver.
        episodes.removeIf(e->e.seconds()<1.9);
        bad.clear();
        for(Episode e:episodes)bad.merge(e.key(),e.seconds(),Double::sum);
        if(fs!=null) {
            for(var e:evaluated.entrySet()) {
                fs.acc(e.getKey()).num+=bad.getOrDefault(e.getKey(),0.0);
            }
            for(Episode e:episodes)fs.moment(e.key(),e.to());
        }
        return new Diagnostics(volume, evaluated, bad, episodes, snapshots, unknown);
    }

    private static void observe(String key, boolean hit, SessionData.State a, SessionData.State b,
                                double seconds, double speed, double rise, FeatureSet fs,
                                Map<String, Double> evaluated, Map<String, Double> bad,
                                List<Episode> episodes, Map<String, Integer> lastEpisode) {
        evaluated.merge(key, seconds, Double::sum);
        if (fs != null) fs.rate(key, 0, seconds);
        if (!hit) return;
        bad.merge(key, seconds, Double::sum);
        Integer index = lastEpisode.get(key);
        Episode old = index == null ? null : episodes.get(index);
        if (old != null && old.to == a.t) {
            episodes.set(index, new Episode(key, old.from, b.t, old.seconds + seconds,
                    Math.max(old.maxSpeed, speed), Math.max(old.maxRise, rise)));
        } else {
            lastEpisode.put(key, episodes.size());
            episodes.add(new Episode(key, a.t, b.t, seconds, speed, rise));
        }
    }

    private static boolean between(Prim.Longs times, long from, long to) {
        int low = 0, high = times.n;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (times.a[mid] <= from) low = mid + 1; else high = mid;
        }
        return low < times.n && times.a[low] <= to;
    }
}
