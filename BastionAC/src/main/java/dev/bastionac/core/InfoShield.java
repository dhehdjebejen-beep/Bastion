package dev.bastionac.core;

import com.mojang.datafixers.util.Pair;
import dev.bastionac.BastionAC;
import dev.bastionac.config.ACConfig;
import dev.bastionac.mixin.TrackedAbsorptionAccessor;
import dev.bastionac.mixin.TrackedHealthAccessor;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * What the server tells a client about others, trimmed to what the vanilla
 * client actually shows (3.3.1). A cheat that reads a packet sends nothing
 * suspicious back — it cannot be detected, only starved:
 *
 * <ul>
 *   <li><b>Other players' health.</b> The vanilla client never draws another
 *       player's health, yet the server sends it with every change: health
 *       ESP reads it. Others get "alive at full health" (or 0, so the death
 *       animation still plays) and no absorption. A health objective on the
 *       scoreboard is the server's own choice and is left alone.</li>
 *   <li><b>Other players' equipment.</b> Enchantments, durability, names and
 *       contents of what they wear and hold are invisible in vanilla. Others
 *       get the item with its looks only: model, trim, dye, banner, head,
 *       loaded crossbow, potion colour, and the enchantment glint.</li>
 *   <li><b>Where far sounds come from.</b> A Wither spawning, the dragon dying
 *       or an End portal opening is sent to every player on the server, a horn
 *       to everyone within hundreds of blocks — with the exact coordinates, so
 *       the client can tell the direction. A cheat prints them. Far sources are
 *       moved towards the listener along the same line: the direction stays,
 *       the coordinates go. (Thunder is not one of them any more: the client
 *       plays it itself, for a bolt it can see.)</li>
 * </ul>
 *
 * The player's own data is never touched. Runs on every outgoing packet, so
 * anything it does not recognise passes through untouched and any failure
 * returns the original packet.
 */
public final class InfoShield {

    /** A global event farther than this is moved to this distance from the listener. */
    static final double GLOBAL_SOUND_KEEP = 32;
    /** A positioned sound farther than this (only loud ones reach so far) is moved to it. */
    static final double LOUD_SOUND_KEEP = 64;

    private InfoShield() {}

    /** The packet as this recipient may see it. */
    public static Packet<?> rewrite(ServerPlayerEntity to, Packet<?> packet) {
        ACConfig cfg = BastionAC.config();
        if (cfg == null || to == null) return packet;
        ACConfig.General g = cfg.general;
        try {
            return rewrite(to, packet, g);
        } catch (RuntimeException e) {
            return packet;   // never break the connection over a cosmetic rewrite
        }
    }

    private static Packet<?> rewrite(ServerPlayerEntity to, Packet<?> packet, ACConfig.General g) {
        if (packet instanceof BundleS2CPacket bundle) {
            List<Packet<? super ClientPlayPacketListener>> out = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientPlayPacketListener> p : bundle.getPackets()) {
                Packet<?> r = rewrite(to, p, g);
                changed |= r != p;
                @SuppressWarnings("unchecked")
                Packet<? super ClientPlayPacketListener> cast = (Packet<? super ClientPlayPacketListener>) r;
                out.add(cast);
            }
            return changed ? new BundleS2CPacket(out) : packet;
        }
        if (packet instanceof EntityTrackerUpdateS2CPacket meta && g.hideOtherPlayersHealth) {
            return health(to, meta);
        }
        if (packet instanceof EntityEquipmentUpdateS2CPacket eq && g.hideOtherPlayersEquipmentDetails) {
            return equipment(to, eq);
        }
        if (packet instanceof WorldEventS2CPacket event && g.hideSoundOrigins) {
            return globalEvent(to, event);
        }
        if (packet instanceof PlaySoundS2CPacket sound && g.hideSoundOrigins) {
            return loudSound(to, sound);
        }
        return packet;
    }

    // ------------------------------------------------------------------ health

    private static Packet<?> health(ServerPlayerEntity to, EntityTrackerUpdateS2CPacket meta) {
        if (meta.id() == to.getId()) return meta;
        PlayerEntity other = otherPlayer(to, meta.id());
        if (other == null) return meta;
        int healthId = TrackedHealthAccessor.bastionac$health().id();
        int absorptionId = TrackedAbsorptionAccessor.bastionac$absorption().id();
        List<DataTracker.SerializedEntry<?>> out = null;
        List<DataTracker.SerializedEntry<?>> in = meta.trackedValues();
        for (int i = 0; i < in.size(); i++) {
            DataTracker.SerializedEntry<?> e = in.get(i);
            DataTracker.SerializedEntry<?> r = e;
            if (e.id() == healthId && e.value() instanceof Float hp) {
                float shown = hp > 0 ? Math.max(1f, other.getMaxHealth()) : hp;
                if (shown != hp) r = DataTracker.SerializedEntry.of(TrackedHealthAccessor.bastionac$health(), shown);
            } else if (e.id() == absorptionId && e.value() instanceof Float abs && abs != 0f) {
                r = DataTracker.SerializedEntry.of(TrackedAbsorptionAccessor.bastionac$absorption(), 0f);
            }
            if (r != e) {
                if (out == null) out = new ArrayList<>(in);
                out.set(i, r);
            }
        }
        return out == null ? meta : new EntityTrackerUpdateS2CPacket(meta.id(), out);
    }

    // ------------------------------------------------------------------ equipment

    private static Packet<?> equipment(ServerPlayerEntity to, EntityEquipmentUpdateS2CPacket eq) {
        if (eq.getEntityId() == to.getId()) return eq;
        if (otherPlayer(to, eq.getEntityId()) == null) return eq;
        List<Pair<EquipmentSlot, ItemStack>> out = new ArrayList<>(eq.getEquipmentList().size());
        for (Pair<EquipmentSlot, ItemStack> p : eq.getEquipmentList()) out.add(Pair.of(p.getFirst(), looksOnly(p.getSecond())));
        return new EntityEquipmentUpdateS2CPacket(eq.getEntityId(), out);
    }

    /** What the vanilla client draws of an item someone else wears or holds — nothing more. */
    static ItemStack looksOnly(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return stack;
        ItemStack clean = new ItemStack(stack.getItem(), stack.getCount());
        copy(stack, clean, DataComponentTypes.ITEM_MODEL);
        copy(stack, clean, DataComponentTypes.CUSTOM_MODEL_DATA);
        copy(stack, clean, DataComponentTypes.EQUIPPABLE);
        copy(stack, clean, DataComponentTypes.TRIM);
        copy(stack, clean, DataComponentTypes.DYED_COLOR);
        copy(stack, clean, DataComponentTypes.PROFILE);
        copy(stack, clean, DataComponentTypes.BANNER_PATTERNS);
        copy(stack, clean, DataComponentTypes.BASE_COLOR);
        copy(stack, clean, DataComponentTypes.POTION_CONTENTS);
        copy(stack, clean, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
        if (stack.contains(DataComponentTypes.CHARGED_PROJECTILES)) {
            // A loaded crossbow is held differently; what it is loaded with is nobody's business.
            var loaded = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (loaded != null && !loaded.isEmpty()) {
                clean.set(DataComponentTypes.CHARGED_PROJECTILES,
                        net.minecraft.component.type.ChargedProjectilesComponent.of(new ItemStack(net.minecraft.item.Items.ARROW)));
            }
        }
        // The glint stays visible, the enchantments behind it do not.
        if (stack.hasGlint() && !clean.hasGlint()) clean.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return clean;
    }

    private static <T> void copy(ItemStack from, ItemStack to, ComponentType<T> type) {
        if (!from.contains(type)) return;
        T v = from.get(type);
        if (v != null) to.set(type, v);
    }

    /** Another player this recipient can see, by entity id; null for anything else. */
    private static PlayerEntity otherPlayer(ServerPlayerEntity to, int id) {
        if (id == to.getId()) return null;
        var server = to.getEntityWorld().getServer();
        if (server == null || !server.isOnThread()) return null;   // the world is the server thread's
        Entity e = to.getEntityWorld().getEntityById(id);
        return e instanceof PlayerEntity p ? p : null;
    }

    // ------------------------------------------------------------------ sounds

    private static Packet<?> globalEvent(ServerPlayerEntity to, WorldEventS2CPacket event) {
        if (!event.isGlobal()) return event;
        BlockPos src = event.getPos();
        Vec3d at = towards(to.getEyePos(), Vec3d.ofCenter(src), GLOBAL_SOUND_KEEP);
        if (at == null) return event;
        return new WorldEventS2CPacket(event.getEventId(), BlockPos.ofFloored(at), event.getData(), true);
    }

    private static Packet<?> loudSound(ServerPlayerEntity to, PlaySoundS2CPacket sound) {
        Vec3d src = new Vec3d(sound.getX(), sound.getY(), sound.getZ());
        Vec3d at = towards(to.getEyePos(), src, LOUD_SOUND_KEEP);
        if (at == null) return sound;
        float volume = keptLoudness(sound.getVolume(), to.getEyePos().distanceTo(src), LOUD_SOUND_KEEP);
        return new PlaySoundS2CPacket(sound.getSound(), sound.getCategory(), at.x, at.y, at.z,
                volume, sound.getPitch(), sound.getSeed());
    }

    /**
     * The volume that makes a sound moved from {@code dist} to {@code keep}
     * blocks sound as loud as before (3.3.2). The client fades a sound
     * linearly to zero at 16 × max(volume, 1) blocks, so a horn moved from 200
     * to 64 blocks was three times louder than it should be. Scaling the volume
     * by keep / dist keeps the fade at the listener exactly the same.
     */
    static float keptLoudness(float volume, double dist, double keep) {
        if (!(dist > keep) || volume <= 1f) return volume;
        return (float) Math.max(1.0, volume * keep / dist);
    }

    /**
     * The point {@code keep} blocks from the listener on the line to the
     * source, or null when the source is no farther than that anyway.
     */
    static Vec3d towards(Vec3d listener, Vec3d source, double keep) {
        Vec3d d = source.subtract(listener);
        double len = d.length();
        if (!(len > keep)) return null;
        return listener.add(d.multiply(keep / len));
    }
}
