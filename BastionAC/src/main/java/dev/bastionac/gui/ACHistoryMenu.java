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
            gui.button(i, icon(e, showName), () -> onClick(admin, e));
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
        if ((kind == HistoryManager.Kind.TEMPBAN || kind == HistoryManager.Kind.MANUAL_BAN)) {
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

    private static void appendEvidence(List<String> lore, Event e) {
        if (e.evidence == null || e.evidence.isEmpty()) return;
        String tick = e.evidence.getOrDefault("tick", "?");
        String tickMs = e.evidence.getOrDefault("tickMs", "?");
        String ping = e.evidence.getOrDefault("pingMs", "?");
        String world = e.evidence.getOrDefault("world", "?");
        lore.add("&8tick " + tick + " · " + tickMs + " ms · ping " + ping + " ms");
        lore.add("&8" + world + " · " + e.evidence.getOrDefault("serverPos", "позиция неизвестна"));
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
                return GuiItems.head(e.name, "&e⚠ " + e.check, lore);
            }
            case TEMPBAN, MANUAL_BAN -> {
                lore.add("&7Срок: &f" + BanManager.formatDuration(Math.max(1, e.durationHours)));
                if (e.details != null) lore.add("&8" + e.details);
                lore.add("&8" + when);
                boolean active = isActiveBan(e);
                lore.add(active ? "&cАктивен &8· клик — разбанить" : "&8истёк/снят");
                return GuiItems.item(Items.BARRIER, (kind == HistoryManager.Kind.MANUAL_BAN ? "&4Ручной бан " : "&cАвто-бан ") + e.name, lore);
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
