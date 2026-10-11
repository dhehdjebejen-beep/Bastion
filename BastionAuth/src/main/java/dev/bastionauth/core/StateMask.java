package dev.bastionauth.core;

import dev.bastionauth.BastionAuth;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.network.packet.s2c.play.RemoveEntityStatusEffectS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides the state of an account from whoever is sitting on its login screen.
 *
 * <h2>The leak</h2>
 * {@link InventoryMask} blanked the inventory, but the rest of the account still
 * reached the client before the password: the armour bar (the armour attribute,
 * not the items), the effect icons, the yellow hearts of absorption, the XP
 * level, the game mode — an operator in creative showed up as one — and the
 * flight ability. Someone who only typed a name learned what the account was
 * wearing, drinking and allowed to do.
 *
 * <h2>The fix</h2>
 * Until the player is authenticated, the packets that describe the player to
 * itself are dropped on the way out ({@link #drops}), and a plain picture is
 * sent instead ({@link #apply}): no effects, no armour, twenty hearts, no XP,
 * survival, no flight, no coordinates in F3. The same picture pins the player:
 * movement speed, jump strength and gravity are zero, so an honest client
 * cannot walk, jump or fall while it waits. On login the real state is sent
 * back ({@link #reveal}). Like the inventory mask this is only a view — the
 * server-side player is never changed.
 */
public final class StateMask {

    /** Our own packets pass the filter; vanilla's do not. */
    private static final ThreadLocal<Boolean> OURS = ThreadLocal.withInitial(() -> false);

    /** Attributes the masked picture overrides, with the value it shows. */
    private static final List<RegistryEntry<EntityAttribute>> FAKED = List.of(
            EntityAttributes.ARMOR, EntityAttributes.ARMOR_TOUGHNESS, EntityAttributes.MOVEMENT_SPEED,
            EntityAttributes.JUMP_STRENGTH, EntityAttributes.GRAVITY, EntityAttributes.MAX_HEALTH,
            EntityAttributes.MAX_ABSORPTION);

    private StateMask() {}

    /** Whether this player's picture is masked right now. */
    public static boolean masked(ServerPlayerEntity player) {
        AuthManager m = BastionAuth.manager();
        if (m == null || player == null) return false;
        if (!m.config().protection.hideAccountState) return false;
        // No state yet means the connection is still inside onPlayerConnect,
        // before the join event: every connection starts masked, a resumed
        // session is revealed a moment later.
        return !m.isAuthenticated(player.getUuid());
    }

    /**
     * The outbound filter: true for a vanilla packet that would describe the
     * masked player to itself.
     */
    public static boolean drops(ServerPlayerEntity player, Packet<?> packet) {
        if (OURS.get()) return false;
        if (!(packet instanceof HealthUpdateS2CPacket || packet instanceof ExperienceBarUpdateS2CPacket
                || packet instanceof EntityStatusEffectS2CPacket || packet instanceof EntityAttributesS2CPacket
                || packet instanceof EntityTrackerUpdateS2CPacket || packet instanceof GameStateChangeS2CPacket
                || packet instanceof PlayerAbilitiesS2CPacket)) {
            return false;
        }
        if (!masked(player)) return false;
        int self = player.getId();
        if (packet instanceof EntityStatusEffectS2CPacket p) return p.getEntityId() == self;
        if (packet instanceof EntityAttributesS2CPacket p) return p.getEntityId() == self;
        if (packet instanceof EntityTrackerUpdateS2CPacket p) return p.id() == self;
        if (packet instanceof GameStateChangeS2CPacket p) return p.getReason() == GameStateChangeS2CPacket.GAME_MODE_CHANGED;
        return true;
    }

    private static void send(ServerPlayerEntity player, Packet<?> packet) {
        OURS.set(true);
        try {
            player.networkHandler.sendPacket(packet);
        } finally {
            OURS.set(false);
        }
    }

    private static EntityAttributeInstance fake(RegistryEntry<EntityAttribute> attribute, double value) {
        EntityAttributeInstance instance = new EntityAttributeInstance(attribute, i -> {});
        instance.setBaseValue(value);
        return instance;
    }

    /** Sends the plain picture. Safe to call again; it only repeats itself. */
    public static void apply(ServerPlayerEntity player) {
        if (player == null || player.networkHandler == null) return;
        try {
            int id = player.getId();
            for (StatusEffectInstance effect : player.getStatusEffects()) {
                send(player, new RemoveEntityStatusEffectS2CPacket(id, effect.getEffectType()));
            }
            List<EntityAttributeInstance> attrs = new ArrayList<>();
            attrs.add(fake(EntityAttributes.ARMOR, 0));
            attrs.add(fake(EntityAttributes.ARMOR_TOUGHNESS, 0));
            attrs.add(fake(EntityAttributes.MOVEMENT_SPEED, 0));
            attrs.add(fake(EntityAttributes.JUMP_STRENGTH, 0));
            attrs.add(fake(EntityAttributes.GRAVITY, 0));
            attrs.add(fake(EntityAttributes.MAX_HEALTH, 20));
            attrs.add(fake(EntityAttributes.MAX_ABSORPTION, 0));
            send(player, new EntityAttributesS2CPacket(id, attrs));
            send(player, new HealthUpdateS2CPacket(20.0F, 20, 5.0F));
            send(player, new EntityTrackerUpdateS2CPacket(id, List.of(DataTracker.SerializedEntry.of(
                    dev.bastionauth.mixin.PlayerEntityAccessor.bastionauth$absorption(), 0.0F))));
            send(player, new ExperienceBarUpdateS2CPacket(0.0F, 0, 0));
            // A spectator (SpectatorGate) hovers in place: flying, at zero speed.
            boolean spectator = SpectatorGate.active(player);
            send(player, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.GAME_MODE_CHANGED,
                    (spectator ? GameMode.SPECTATOR : GameMode.SURVIVAL).getIndex()));
            PlayerAbilities plain = new PlayerAbilities();
            if (spectator) {
                plain.allowFlying = true;
                plain.flying = true;
                plain.invulnerable = true;
            }
            plain.setFlySpeed(0.0F);
            send(player, new PlayerAbilitiesS2CPacket(plain));
            // 22: reduced debug info — F3 shows no coordinates.
            send(player, new EntityStatusS2CPacket(player, (byte) 22));
            // Something to stand on, so the zero speed actually holds.
            Limbo.platform(player);
        } catch (Throwable t) {
            BastionAuth.LOGGER.warn("Could not mask the state of {}: {}", player.getGameProfile().name(), t.toString());
        }
    }

    /** Puts the real state back on screen, once the account is proven. */
    public static void reveal(ServerPlayerEntity player) {
        if (player == null || player.networkHandler == null) return;
        try {
            int id = player.getId();
            send(player, new EntityAttributesS2CPacket(id, player.getAttributes().getAttributesToSend()));
            List<EntityAttributeInstance> faked = new ArrayList<>();
            for (RegistryEntry<EntityAttribute> a : FAKED) {
                EntityAttributeInstance real = player.getAttributeInstance(a);
                if (real != null) faked.add(real);
            }
            if (!faked.isEmpty()) send(player, new EntityAttributesS2CPacket(id, faked));
            for (StatusEffectInstance effect : player.getStatusEffects()) {
                send(player, new EntityStatusEffectS2CPacket(id, effect, false));
            }
            List<DataTracker.SerializedEntry<?>> tracked = player.getDataTracker().getChangedEntries();
            if (tracked != null && !tracked.isEmpty()) send(player, new EntityTrackerUpdateS2CPacket(id, tracked));
            send(player, new EntityTrackerUpdateS2CPacket(id, List.of(DataTracker.SerializedEntry.of(
                    dev.bastionauth.mixin.PlayerEntityAccessor.bastionauth$absorption(), player.getAbsorptionAmount()))));
            send(player, new ExperienceBarUpdateS2CPacket(player.experienceProgress, player.totalExperience,
                    player.experienceLevel));
            send(player, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.GAME_MODE_CHANGED,
                    player.interactionManager.getGameMode().getIndex()));
            send(player, new PlayerAbilitiesS2CPacket(player.getAbilities()));
            boolean reduced = player.getEntityWorld().getGameRules()
                    .getValue(net.minecraft.world.rule.GameRules.REDUCED_DEBUG_INFO);
            send(player, new EntityStatusS2CPacket(player, (byte) (reduced ? 22 : 23)));
            // Health and food: the server re-sends them on its next tick.
            player.markHealthDirty();
        } catch (Throwable t) {
            BastionAuth.LOGGER.warn("Could not reveal the state of {}: {}", player.getGameProfile().name(), t.toString());
        }
    }
}
