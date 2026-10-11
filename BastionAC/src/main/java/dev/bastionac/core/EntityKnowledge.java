package dev.bastionac.core;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which entities each client has actually been shown.
 *
 * <p>A vanilla client can only hit what it has: an entity reaches it as a
 * spawn packet, and its id comes from that packet. CrystalAura's "predict"
 * (Meteor, Thunderhack, Boze and the like) attacks the end crystal it has
 * just placed before the server has even told it the crystal exists — it
 * guesses the next id. Every id the client is sent is recorded here (spawns,
 * bundles included); an attack on one it was never sent is a guess, and a
 * guess is not something a mouse can make.
 *
 * <p>Ids are not compared with the server's counter: other mods (Herobrine,
 * Polymer) give their packet-only entities ids from their own ranges, and
 * those reach the client through the same spawn packets, so they are known
 * here like any other.
 */
public final class EntityKnowledge {

    /** A destroyed entity stays attackable this long — the destroy packet may still be on its way. */
    static final long DESTROY_GRACE_MS = 10_000;

    private static final class Known {
        final IntOpenHashSet ids = new IntOpenHashSet();
        final Int2LongOpenHashMap destroyedAt = new Int2LongOpenHashMap();
        final long since = System.currentTimeMillis();
    }

    private static final Map<UUID, Known> KNOWN = new ConcurrentHashMap<>();

    private EntityKnowledge() {}

    /**
     * The record starts with the first spawn packet, not at the join event:
     * the entities around a player's login are sent while the connection is
     * still being set up, before any join hook runs.
     */
    private static Known of(UUID player) {
        return KNOWN.computeIfAbsent(player, u -> new Known());
    }

    public static void forget(UUID player) {
        KNOWN.remove(player);
    }

    /** Any packet on its way to the player (from the send hook). */
    public static void onSent(UUID player, Packet<?> packet) {
        if (packet instanceof EntitySpawnS2CPacket spawn) {
            Known k = of(player);
            synchronized (k) {
                k.ids.add(spawn.getEntityId());
                k.destroyedAt.remove(spawn.getEntityId());
            }
        } else if (packet instanceof EntitiesDestroyS2CPacket destroy) {
            Known k = of(player);
            long now = System.currentTimeMillis();
            synchronized (k) {
                for (int id : destroy.getEntityIds()) k.destroyedAt.put(id, now);
            }
        } else if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> p : bundle.getPackets()) onSent(player, p);
        }
    }

    /**
     * Whether the client could know this entity. Unknown players (not
     * tracked yet — the first seconds of a session) answer true.
     */
    public static boolean knows(UUID player, int entityId) {
        Known k = KNOWN.get(player);
        if (k == null || System.currentTimeMillis() - k.since < 3_000) return true;
        synchronized (k) {
            return k.ids.contains(entityId);
        }
    }

    /** Once a second: entities destroyed long enough ago are forgotten. */
    public static void sweep() {
        long cutoff = System.currentTimeMillis() - DESTROY_GRACE_MS;
        for (Known k : KNOWN.values()) {
            synchronized (k) {
                var it = k.destroyedAt.int2LongEntrySet().fastIterator();
                while (it.hasNext()) {
                    var e = it.next();
                    if (e.getLongValue() < cutoff) {
                        k.ids.remove(e.getIntKey());
                        it.remove();
                    }
                }
            }
        }
    }
}
