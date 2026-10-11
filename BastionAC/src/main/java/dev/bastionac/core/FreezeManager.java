package dev.bastionac.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.bastionac.BastionAC;
import dev.bastionac.util.SafeFiles;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freezing a player in place, two ways over one mechanism.
 *
 * <ul>
 *   <li><b>Setback freeze</b> — a few ticks after every movement setback, so a
 *       cheat client cannot fight the teleport and the player sees one clean
 *       "snap back" instead of a rubber-band tug of war.</li>
 *   <li><b>Staff freeze</b> — {@code /bac freeze <ник> [причина]}: no movement,
 *       no hits, no block or item use, no commands but private messages, no
 *       damage taken or dealt, a title on screen; survives a relog, and leaving
 *       while frozen is reported to staff.</li>
 * </ul>
 *
 * The client is asked to stand still (movement speed and jump strength
 * multiplied by zero — a vanilla client simply cannot move); the server
 * enforces it anyway, so a client that ignores the attributes is put back.
 */
public final class FreezeManager {

    private static final Identifier MOD_ID = Identifier.of("bastionac", "freeze");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Staff freezes, keyed by uuid; persisted. */
    private static final Map<UUID, Entry> FROZEN = new ConcurrentHashMap<>();
    /** Players whose attribute modifiers are currently applied. */
    private static final Set<UUID> MODS_ON = ConcurrentHashMap.newKeySet();
    /**
     * Staff-frozen players as they were before: could they fly, did they float.
     * Given back on unfreeze; not persisted — a relog re-freezes from scratch.
     */
    private static final Map<UUID, boolean[]> BEFORE = new ConcurrentHashMap<>();
    private static volatile Path file;

    public static final class Entry {
        public String name;
        public String reason;
        public String by;
        public long since;
    }

    private FreezeManager() {}

    // ------------------------------------------------------------------ persistence

    public static void load(Path dir) {
        file = dir.resolve("frozen.json");
        FROZEN.clear();
        try {
            if (Files.exists(file)) {
                Map<String, Entry> m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<LinkedHashMap<String, Entry>>() {}.getType());
                if (m != null) m.forEach((k, v) -> FROZEN.put(UUID.fromString(k), v));
            }
        } catch (Exception e) {
            BastionAC.LOGGER.error("frozen.json could not be read; no staff freezes restored", e);
        }
    }

    private static void save() {
        Path f = file;
        if (f == null) return;
        Map<String, Entry> m = new LinkedHashMap<>();
        FROZEN.forEach((k, v) -> m.put(k.toString(), v));
        try {
            SafeFiles.write(f, GSON.toJson(m) + System.lineSeparator());
        } catch (Exception e) {
            BastionAC.LOGGER.error("frozen.json could not be written", e);
        }
    }

    // ------------------------------------------------------------------ queries

    public static boolean isStaffFrozen(UUID uuid) {
        return FROZEN.containsKey(uuid);
    }

    /** Staff-frozen: no hits, block/item use or commands either. */
    public static boolean blocksActions(ServerPlayerEntity player) {
        return FROZEN.containsKey(player.getUuid());
    }

    public static boolean isFrozen(ServerPlayerEntity player, PlayerData d, long tick) {
        return FROZEN.containsKey(player.getUuid()) || (d != null && tick < d.setbackFreezeUntil);
    }

    public static Map<UUID, Entry> list() {
        return Map.copyOf(FROZEN);
    }

    // ------------------------------------------------------------------ actions

    public static boolean freeze(ServerPlayerEntity target, String by, String reason) {
        Entry e = new Entry();
        e.name = target.getGameProfile().name();
        e.by = by;
        e.reason = reason == null || reason.isBlank() ? "проверка модератором" : reason;
        e.since = System.currentTimeMillis();
        boolean fresh = FROZEN.put(target.getUuid(), e) == null;
        save();
        hold(target, BastionAC.data(target));
        target.sendMessage(Text.literal("§b❄ Вы заморожены модератором. §7Причина: §f" + e.reason
                + "\n§7Не выходите из игры — выход будет замечен. Писать можно в личные сообщения."), false);
        title(target);
        BastionAC.LOGGER.warn("[FREEZE] {} frozen by {}: {}", e.name, by, e.reason);
        return fresh;
    }

    public static boolean unfreeze(MinecraftServer server, UUID uuid, String by) {
        Entry e = FROZEN.remove(uuid);
        if (e == null) return false;
        save();
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
        if (p != null) {
            release(p);
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§a✔ Заморозка снята")));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§7Можно двигаться")));
            p.sendMessage(Text.literal("§a[BAC] Заморозка снята. Спасибо за терпение."), false);
        }
        BastionAC.LOGGER.warn("[FREEZE] {} unfrozen by {}", e.name, by);
        return true;
    }

    /** The few ticks after a movement setback (server thread). */
    public static void afterSetback(ServerPlayerEntity player, PlayerData d, long tick, int ticks) {
        if (ticks <= 0) return;
        // Counted from when the client can actually have received the
        // teleport, so a high ping does not eat the whole window.
        long until = tick + ticks + player.networkHandler.getLatency() / 50;
        d.setbackFreezeUntil = Math.max(d.setbackFreezeUntil, until);
        d.freezeX = d.anchorX;
        d.freezeY = d.anchorY;
        d.freezeZ = d.anchorZ;
        mods(player, true);
    }

    /**
     * Pins the player where they stand now — completely: out of any vehicle,
     * out of the air (no flight, no elytra, no gravity — a player frozen in
     * mid-air hangs there instead of falling out of the pin), no speed, no jump.
     */
    private static void hold(ServerPlayerEntity player, PlayerData d) {
        if (player.hasVehicle()) player.stopRiding();
        if (player.isGliding()) player.stopGliding();
        BEFORE.putIfAbsent(player.getUuid(), new boolean[]{player.getAbilities().allowFlying, player.hasNoGravity()});
        player.getAbilities().flying = false;
        player.getAbilities().allowFlying = false;
        player.sendAbilitiesUpdate();
        player.setNoGravity(true);
        if (d != null) {
            d.freezeX = player.getX();
            d.freezeY = player.getY();
            d.freezeZ = player.getZ();
        }
        player.setVelocity(Vec3d.ZERO);
        player.knockedBack = true;
        mods(player, true);
    }

    private static void release(ServerPlayerEntity player) {
        mods(player, false);
        boolean[] was = BEFORE.remove(player.getUuid());
        if (was != null) {
            player.setNoGravity(was[1]);
            // The game mode decides flight for creative and spectator; for
            // survival, whatever /fly had granted before comes back.
            player.interactionManager.getGameMode().setAbilities(player.getAbilities());
            if (was[0]) player.getAbilities().allowFlying = true;
            player.sendAbilitiesUpdate();
        }
    }

    /** Every tick for a staff-frozen player: undo anything that moved them. */
    private static void enforce(ServerPlayerEntity p) {
        PlayerData d = BastionAC.data(p);
        if (p.hasVehicle()) p.stopRiding();
        if (p.isGliding()) p.stopGliding();
        if (p.getAbilities().flying || p.getAbilities().allowFlying) {
            p.getAbilities().flying = false;
            p.getAbilities().allowFlying = false;
            p.sendAbilitiesUpdate();
        }
        if (!p.hasNoGravity()) p.setNoGravity(true);
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
        if (d == null) return;
        // Water, a piston, an explosion, a hit: the server moved them — put them back.
        double dx = p.getX() - d.freezeX, dy = p.getY() - d.freezeY, dz = p.getZ() - d.freezeZ;
        if (dx * dx + dy * dy + dz * dz > 0.05 * 0.05) {
            p.networkHandler.requestTeleport(d.freezeX, d.freezeY, d.freezeZ, p.getYaw(), p.getPitch());
        }
    }

    private static void mods(ServerPlayerEntity player, boolean on) {
        EntityAttributeInstance speed = player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        EntityAttributeInstance jump = player.getAttributeInstance(EntityAttributes.JUMP_STRENGTH);
        if (on) {
            if (!MODS_ON.add(player.getUuid())) return;
            EntityAttributeModifier zero = new EntityAttributeModifier(MOD_ID, -1.0,
                    EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            if (speed != null && !speed.hasModifier(MOD_ID)) speed.addTemporaryModifier(zero);
            if (jump != null && !jump.hasModifier(MOD_ID)) jump.addTemporaryModifier(zero);
        } else {
            MODS_ON.remove(player.getUuid());
            if (speed != null) speed.removeModifier(MOD_ID);
            if (jump != null) jump.removeModifier(MOD_ID);
        }
    }

    private static void title(ServerPlayerEntity p) {
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 50, 10));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§b❄ Вы заморожены")));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§7Ожидайте модератора, не выходите из игры")));
    }

    // ------------------------------------------------------------------ hooks

    /** Every server tick. */
    public static void tick(MinecraftServer server, long tick) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            UUID id = p.getUuid();
            boolean staff = FROZEN.containsKey(id);
            if (staff) {
                if (!MODS_ON.contains(id)) hold(p, BastionAC.data(p));
                enforce(p);
                if (tick % 40 == 0) title(p);
                continue;
            }
            if (MODS_ON.contains(id)) {
                PlayerData d = BastionAC.data(p);
                if (d == null || tick >= d.setbackFreezeUntil) release(p);
            }
        }
    }

    /** A frozen player joined again: freeze them where they arrive and tell staff. */
    public static void onJoin(ServerPlayerEntity player) {
        Entry e = FROZEN.get(player.getUuid());
        if (e == null) return;
        MODS_ON.remove(player.getUuid());
        hold(player, BastionAC.data(player));
        player.sendMessage(Text.literal("§b❄ Вы всё ещё заморожены. §7Причина: §f" + e.reason), false);
        title(player);
        Alerts.sendNetSignal(e.name, player.getUuid(), e.by, "зашёл замороженным", e.reason);
    }

    /** Leaving while frozen is what staff want to know about. */
    public static void onDisconnect(ServerPlayerEntity player) {
        MODS_ON.remove(player.getUuid());
        // Leave the saved player file as it would be unfrozen; the next join re-freezes.
        boolean[] was = BEFORE.remove(player.getUuid());
        if (was != null) player.setNoGravity(was[1]);
        Entry e = FROZEN.get(player.getUuid());
        if (e == null) return;
        BastionAC.LOGGER.warn("[FREEZE] {} left while frozen (by {}, {})", e.name, e.by, e.reason);
        Alerts.sendNetSignal(e.name, player.getUuid(), e.by, "§cвышел во время заморозки", e.reason);
    }

    /** Commands a staff-frozen player may still use: talking to staff. */
    public static boolean commandAllowed(String command) {
        String root = command.trim();
        if (root.startsWith("/")) root = root.substring(1);
        int sp = root.indexOf(' ');
        if (sp >= 0) root = root.substring(0, sp);
        root = root.toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        return switch (root) {
            case "msg", "tell", "w", "r", "m", "reply", "репорт", "report", "жалоба", "login", "l", "register",
                 "лс", "написать", "ответ", "pm", "mmax" -> true;
            default -> false;
        };
    }
}
