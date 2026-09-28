package dev.bastionac.core;

import dev.bastionac.BastionAC;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-game staff alerts. Each alert is one clickable line: hovering shows the
 * details, coordinates and ping; clicking teleports the admin to the suspect.
 */
public final class Alerts {

    /** Explicit per-admin overrides of the config default (true=on). */
    private static final Map<UUID, Boolean> OVERRIDES = new ConcurrentHashMap<>();

    public static boolean isAdmin(ServerPlayerEntity player) {
        return CommandManager.ADMINS_CHECK.allows(player.getPermissions());
    }

    public static boolean isEnabledFor(ServerPlayerEntity admin) {
        Boolean override = OVERRIDES.get(admin.getUuid());
        if (override != null) return override;
        return BastionAC.config().general.alertsToOps;
    }

    /** @return the new state after toggling. */
    public static boolean toggle(ServerPlayerEntity admin) {
        boolean next = !isEnabledFor(admin);
        OVERRIDES.put(admin.getUuid(), next);
        return next;
    }

    public static void forget(UUID uuid) {
        OVERRIDES.remove(uuid);
    }

    public static void send(MinecraftServer server, ServerPlayerEntity suspect,
                            CheckType type, double vl, String details) {
        String name = suspect.getGameProfile().name();
        String alertPrefix = BastionAC.config().messages.alertPrefix;
        BlockPos pos = suspect.getBlockPos();
        // A violation can be confirmed on the same tick the connection is torn
        // down (kick, ban, timeout) — never let the alert itself throw.
        int ping = suspect.networkHandler == null ? 0 : suspect.networkHandler.getLatency();

        BastionAC.LOGGER.warn("[ALERT] {} failed {} (VL {}): {}",
                name, type.key, String.format("%.1f", vl), details);

        MutableText line = TextFmt.literal(alertPrefix + " &e" + name
                + " &7» &f" + type.key + " &8VL &f" + String.format("%.1f", vl) + " &8[&bTP&8]");

        MutableText hover = TextFmt.literal(
                "&7Проверка: &f" + type.key + "\n"
                + "&7Детали: &f" + details + "\n"
                + "&7VL: &f" + String.format("%.1f", vl) + "\n"
                + "&7Коорд: &f" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "\n"
                + "&7Пинг: &f" + ping + " мс\n"
                + "&8Клик — телепорт к игроку");

        MutableText clickable = (MutableText) line.styled(s -> s
                .withClickEvent(new ClickEvent.RunCommand("/tp " + name))
                .withHoverEvent(new HoverEvent.ShowText(hover)));

        for (ServerPlayerEntity op : server.getPlayerManager().getPlayerList()) {
            if (isAdmin(op) && isEnabledFor(op)) {
                op.sendMessage(clickable, false);
            }
        }
    }

    /**
     * Chain alert: an attack-chain report (EDR-style correlation), sent when
     * the chain detector arms or burns. The hover carries the full chain; the
     * click opens the panel's chain view rather than teleporting — a chain is
     * a profile, not a place.
     */
    public static void sendChain(String name, UUID uuid, String grade, String chainText) {
        MinecraftServer srv = BastionAC.server();
        if (srv == null) return;
        String alertPrefix = BastionAC.config().messages.alertPrefix;
        MutableText line = TextFmt.literal(alertPrefix + " &8[&#" + grade + "&8] &e"
                + name + " &7» цепочка атак");
        MutableText hover = TextFmt.literal(
                "&7Игрок: &f" + name + "\n"
                + "&7UUID: &8" + uuid + "\n"
                + "&7Оценка: &f" + grade + "\n\n"
                + "&7Цепочка (по стадиям):\n&f" + chainText + "\n\n"
                + "&8Контекст сохранён в дампе (logs/bastionac/dumps)");
        MutableText clickable = (MutableText) line.styled(s -> s
                .withHoverEvent(new HoverEvent.ShowText(hover)));
        for (ServerPlayerEntity op : srv.getPlayerManager().getPlayerList()) {
            if (isAdmin(op) && isEnabledFor(op)) {
                op.sendMessage(clickable, false);
            }
        }
    }

    /**
     * Network-registry signal: a join from the subnet of a recent ban, a
     * same-subnet account link, or another correlation-level fact that is a
     * signal for humans, never an automatic action. Hover shows the detail;
     * click opens the network view of the panel.
     */
    public static void sendNetSignal(String name, UUID uuid, String relatedName, String what, String detail) {
        MinecraftServer srv = BastionAC.server();
        if (srv == null) return;
        String alertPrefix = BastionAC.config().messages.alertPrefix;
        MutableText line = TextFmt.literal(alertPrefix + " &8[&dNET&8] &e" + name
                + " &7» " + what + " &8(&f" + relatedName + "&8)");
        MutableText hover = TextFmt.literal(
                "&7Игрок: &f" + name + "\n"
                + "&7Связь: &f" + relatedName + "\n"
                + "&7Детали: &f" + detail + "\n\n"
                + "&8Сигнал для персонала — сам по себе не наказание");
        MutableText clickable = (MutableText) line.styled(s ->
                s.withHoverEvent(new HoverEvent.ShowText(hover)));
        for (ServerPlayerEntity op : srv.getPlayerManager().getPlayerList()) {
            if (isAdmin(op) && isEnabledFor(op)) {
                op.sendMessage(clickable, false);
            }
        }
    }

    private Alerts() {}
}
