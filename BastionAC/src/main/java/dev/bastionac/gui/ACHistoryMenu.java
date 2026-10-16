package dev.bastionac.gui;

import dev.bastionac.core.BanManager;
import dev.bastionac.core.HistoryManager;
import dev.bastionac.core.HistoryManager.Event;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/** Alert and punishment history feeds (global and per-player). */
final class ACHistoryMenu {

    private static final int PER_PAGE = 45;

    // ------------------------------------------------------------------ global

    static void openAlerts(ServerPlayerEntity admin, int page) {
        feed(admin, "&8[&cBAC&8] &7История алертов", HistoryManager.recentAlerts(2000), page, true,
                () -> ACPanel.open(admin), pg -> openAlerts(admin, pg));
    }

    static void openPunishments(ServerPlayerEntity admin, int page) {
        feed(admin, "&8[&cBAC&8] &7История наказаний", HistoryManager.recentPunishments(1000), page, true,
                () -> ACPanel.open(admin), pg -> openPunishments(admin, pg));
    }

    // ------------------------------------------------------------------ per-player

    static void openPlayerAlerts(ServerPlayerEntity admin, UUID uuid, String name, int page) {
        feed(admin, "&8[&cBAC&8] &7Алерты &f" + name, HistoryManager.alertsFor(uuid, 500), page, false,
                () -> ACPlayerMenu.openCard(admin, uuid), pg -> openPlayerAlerts(admin, uuid, name, pg));
    }

    static void openPlayerPunishments(ServerPlayerEntity admin, UUID uuid, String name, int page) {
        feed(admin, "&8[&cBAC&8] &7Наказания &f" + name, HistoryManager.punishmentsFor(uuid, 500), page, false,
                () -> ACPlayerMenu.openCard(admin, uuid), pg -> openPlayerPunishments(admin, uuid, name, pg));
    }

    // ------------------------------------------------------------------ renderer

    private static void feed(ServerPlayerEntity admin, String title, List<Event> all, int page,
                             boolean showName, Runnable back, IntConsumer reopen) {
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        ChestGui gui = new ChestGui(TextFmt.literal(title + " &8(" + (page + 1) + "/" + pages + ")"), 6);

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < all.size(); i++) {
            Event e = all.get(start + i);
            if (parseKind(e.kind) == HistoryManager.Kind.ALERT) {
                gui.set(i, icon(e, showName), (p, t) -> onAlertClick(admin, e, t, () -> reopen.accept(pageNow(all, e))));
            } else {
                gui.button(i, icon(e, showName), () -> onClick(admin, e));
            }
        }
        if (all.isEmpty()) gui.button(22, GuiItems.item(Items.BOOK, "&7Пока пусто"), () -> {});

        int prev = page - 1, next = page + 1;
        if (page > 0) gui.button(45, GuiItems.item(Items.ARROW, "&7← Назад (стр.)"), () -> reopen.accept(prev));
        gui.button(49, GuiItems.back("в панель"), back);
        if (next < pages) gui.button(53, GuiItems.item(Items.ARROW, "&7Вперёд (стр.) →"), () -> reopen.accept(next));

        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static void onClick(ServerPlayerEntity admin, Event e) {
        // Punishment that is still an active ban → offer unban.
        HistoryManager.Kind kind = parseKind(e.kind);
        if ((kind == HistoryManager.Kind.TEMPBAN || kind == HistoryManager.Kind.MANUAL_BAN
                || kind == HistoryManager.Kind.ARGUS_BAN)) {
            try {
                UUID uuid = UUID.fromString(e.uuid);
                if (BanManager.isBanned(uuid, e.name)) {
                    Confirm.open(admin, "&aРазбанить " + e.name + "?", "Снять бан с " + e.name + "?", "Разбанить",
                            () -> {
                                BanManager.unban(uuid, e.name, admin.getGameProfile().name());
                                admin.sendMessage(TextFmt.literal("&a[BAC] " + e.name + " разбанен"), false);
                                openPunishments(admin, 0);
                            },
                            () -> openPunishments(admin, 0));
                    return;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        // Alert / other → jump to the player's card if they are online.
        try {
            UUID uuid = UUID.fromString(e.uuid);
            if (admin.getEntityWorld().getServer().getPlayerManager().getPlayer(uuid) != null) {
                ACPlayerMenu.openCard(admin, uuid);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    /**
     * An alert in the history (3.3.2): left — Argus investigates the session
     * that was running at that moment, online or not; right — teleport to
     * where it happened; shift-left — the player's cases; shift-right — the
     * player's card (online only).
     */
    private static void onAlertClick(ServerPlayerEntity admin, Event e, ChestGui.ClickType t, Runnable back) {
        UUID uuid;
        try {
            uuid = UUID.fromString(e.uuid);
        } catch (IllegalArgumentException ex) {
            return;
        }
        switch (t) {
            case LEFT -> ArgusMenu.investigate(admin, uuid, e.name, e.time);
            case RIGHT -> teleportTo(admin, e);
            case SHIFT_LEFT -> ArgusMenu.openPlayer(admin, uuid, e.name, back);
            case SHIFT_RIGHT -> {
                if (admin.getEntityWorld().getServer().getPlayerManager().getPlayer(uuid) != null) {
                    ACPlayerMenu.openCard(admin, uuid);
                } else {
                    admin.sendMessage(TextFmt.literal("&7[BAC] " + e.name + " не в сети — ЛКМ: расследование Аргуса"), false);
                }
            }
        }
    }

    /** The page the event is on now (the feed may have grown since it was drawn). */
    private static int pageNow(List<Event> all, Event e) {
        int i = all.indexOf(e);
        return i < 0 ? 0 : i / PER_PAGE;
    }

    /** Teleports the admin to where the alert happened (its world and the server's position then). */
    private static void teleportTo(ServerPlayerEntity admin, Event e) {
        double[] p = alertPos(e);
        String worldId = e.evidence == null ? null : e.evidence.get("world");
        var srv = admin.getEntityWorld().getServer();
        net.minecraft.server.world.ServerWorld world = null;
        if (worldId != null) {
            try {
                world = srv.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD,
                        net.minecraft.util.Identifier.of(worldId)));
            } catch (RuntimeException ignored) {
            }
        }
        if (p == null || world == null) {
            admin.sendMessage(TextFmt.literal("&c[BAC] У этого алерта нет сохранённой позиции"), false);
            return;
        }
        admin.teleport(world, p[0], p[1], p[2], java.util.Set.of(), admin.getYaw(), admin.getPitch(), false);
        admin.closeHandledScreen();
        admin.sendMessage(TextFmt.literal(String.format(java.util.Locale.ROOT,
                "&7[BAC] Место алерта %s (%s): &f%.1f %.1f %.1f", e.check, e.name, p[0], p[1], p[2])), false);
    }

    /** The server's position of the player at the alert, from its evidence ("x,y,z"); null if none. */
    static double[] alertPos(Event e) {
        if (e.evidence == null) return null;
        String s = e.evidence.get("serverPos");
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length != 3) return null;
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static void appendEvidence(List<String> lore, Event e) {
        if (e.evidence == null || e.evidence.isEmpty()) return;
        String tick = e.evidence.getOrDefault("tick", "?");
        String tickMs = e.evidence.getOrDefault("tickMs", "?");
        String ping = e.evidence.getOrDefault("pingMs", "?");
        String world = e.evidence.getOrDefault("world", "?");
        double[] p = alertPos(e);
        // Where it happened, readable at a glance (it used to be the last grey line).
        lore.add(p == null ? "&7Где: &8позиция неизвестна" : String.format(java.util.Locale.ROOT,
                "&7Где: &f%.1f %.1f %.1f &8(%s)", p[0], p[1], p[2], world.replace("minecraft:", "")));
        lore.add("&8tick " + tick + " · " + tickMs + " ms · ping " + ping + " ms");
        String shadow = e.evidence.get("shadow");
        if (shadow != null && !shadow.isBlank()) lore.add("&8" + shadow);
    }

    private static net.minecraft.item.ItemStack icon(Event e, boolean showName) {
        HistoryManager.Kind kind = parseKind(e.kind);
        String when = GuiItems.ago(System.currentTimeMillis() - e.time) + " назад &8(" + GuiItems.stamp(e.time) + ")";
        List<String> lore = new ArrayList<>();
        if (showName) lore.add("&7Игрок: &f" + e.name);
        switch (kind) {
            case ALERT -> {
                lore.add("&7Проверка: &f" + e.check + " &8· &7VL &f" + String.format(java.util.Locale.ROOT, "%.1f", e.vl));
                if (e.details != null && !e.details.isEmpty()) lore.add("&8" + e.details);
                appendEvidence(lore, e);
                lore.add("&8" + when);
                try {
                    lore.add(ArgusMenu.caseLineFor(UUID.fromString(e.uuid), e.time));
                } catch (IllegalArgumentException ignored) {
                }
                lore.add("&8ЛКМ — Аргус: разобрать эту сессию");
                lore.add("&8ПКМ — телепорт к месту алерта");
                lore.add("&8Shift+ЛКМ — дела игрока · Shift+ПКМ — карточка");
                return GuiItems.head(e.name, "&e⚠ " + e.check, lore);
            }
            case TEMPBAN, MANUAL_BAN, ARGUS_BAN -> {
                lore.add("&7Срок: &f" + BanManager.formatDuration(Math.max(1, e.durationHours)));
                if (e.details != null) lore.add("&8" + e.details);
                lore.add("&8" + when);
                boolean active = isActiveBan(e);
                lore.add(active ? "&cАктивен &8· клик — разбанить" : "&8истёк/снят");
                String title = kind == HistoryManager.Kind.MANUAL_BAN ? "&4Ручной бан "
                        : kind == HistoryManager.Kind.ARGUS_BAN ? "&5Бан Аргуса " : "&cАвто-бан ";
                return GuiItems.item(kind == HistoryManager.Kind.ARGUS_BAN ? Items.ENDER_EYE : Items.BARRIER,
                        title + e.name, lore);
            }
            case KICK -> {
                if (e.details != null) lore.add("&8" + e.details);
                lore.add("&8" + when);
                return GuiItems.item(Items.IRON_DOOR, "&6Кик " + e.name, lore);
            }
            case UNBAN -> {
                if (e.details != null) lore.add("&8" + e.details);
                lore.add("&8" + when);
                return GuiItems.item(Items.LIME_WOOL, "&aРазбан " + e.name, lore);
            }
            default -> {
                if (e.details != null) lore.add("&8" + e.details);
                lore.add("&8" + when);
                return GuiItems.item(Items.SPONGE, "&bСброс VL " + e.name, lore);
            }
        }
    }

    private static boolean isActiveBan(Event e) {
        try {
            return BanManager.isBanned(UUID.fromString(e.uuid), e.name);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static HistoryManager.Kind parseKind(String s) {
        try {
            return HistoryManager.Kind.valueOf(s);
        } catch (Exception e) {
            return HistoryManager.Kind.ALERT;
        }
    }

    private ACHistoryMenu() {}
}
