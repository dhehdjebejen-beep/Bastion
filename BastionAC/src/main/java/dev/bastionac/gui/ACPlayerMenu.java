package dev.bastionac.gui;

import dev.bastionac.BastionAC;
import dev.bastionac.core.BanManager;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.HistoryManager;
import dev.bastionac.core.PlayerData;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Online-player browser + per-player card with VL, history and moderation. */
final class ACPlayerMenu {

    /** Resolves an online player by name and opens their card. */
    static void openByName(ServerPlayerEntity admin, String name) {
        MinecraftServer srv = admin.getEntityWorld().getServer();
        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(name);
        if (target == null) {
            admin.sendMessage(TextFmt.literal("&c[BAC] Игрок " + name + " не в сети"), false);
            return;
        }
        openCard(admin, target.getUuid());
    }

    static void openOnline(ServerPlayerEntity admin) {
        MinecraftServer srv = admin.getEntityWorld().getServer();
        List<ServerPlayerEntity> players = srv.getPlayerManager().getPlayerList();
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Онлайн &8(&f" + players.size() + "&8)"), 6);

        int slot = 0;
        for (ServerPlayerEntity target : players) {
            if (slot >= 45) break;
            PlayerData d = BastionAC.data(target);
            double totalVl = d == null ? 0 : d.vl.snapshot().values().stream().mapToDouble(Double::doubleValue).sum();
            String name = target.getGameProfile().name();
            String flag = totalVl <= 0 ? "&aчисто" : totalVl < 5 ? "&eVL " + fmt(totalVl) : "&cVL " + fmt(totalVl);
            gui.button(slot++, GuiItems.head(name, (totalVl >= 5 ? "&c" : "&a") + name,
                            "&7Состояние: " + flag,
                            "&7Пинг: &f" + target.networkHandler.getLatency() + " мс",
                            "&8➜ карточка игрока"),
                    () -> openCard(admin, target.getUuid()));
        }
        if (players.isEmpty()) gui.button(22, GuiItems.item(Items.BARRIER, "&7Никого нет онлайн"), () -> {});

        gui.button(49, GuiItems.back("в панель"), () -> ACPanel.open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    static void openCard(ServerPlayerEntity admin, UUID uuid) {
        MinecraftServer srv = admin.getEntityWorld().getServer();
        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(uuid);
        if (target == null) {
            admin.sendMessage(TextFmt.literal("&c[BAC] Игрок вышел из сети"), false);
            openOnline(admin);
            return;
        }
        String name = target.getGameProfile().name();
        PlayerData d = BastionAC.data(target);
        Map<CheckType, Double> vls = d != null ? d.vl.snapshot() : Map.of();
        boolean banned = BanManager.isBanned(uuid, name);

        ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Игрок &f" + name), 5);

        // Header: live VL breakdown.
        List<String> vlLore = new ArrayList<>();
        if (vls.isEmpty()) {
            vlLore.add("&aАктивных нарушений нет");
        } else {
            vls.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> vlLore.add("&7" + e.getKey().key + ": &f" + fmt(e.getValue())));
        }
        vlLore.add("&8Всего алертов в истории: &7" + HistoryManager.alertCountFor(uuid));
        if (d != null) {
            String xray = dev.bastionac.core.XrayTracker.summary(d);
            if (!xray.equals("нет данных") && !xray.equals("руд=0, камня=0, скрытых=0")) {
                vlLore.add("&7Добыча (xray-статистика): &f" + xray);
            }
        }
        if (banned) vlLore.add("&cСейчас в бане");
        gui.button(4, GuiItems.head(name, "&e" + name, vlLore), () -> {});

        gui.button(19, GuiItems.item(Items.WRITABLE_BOOK, "&e📜 История алертов игрока",
                "&8➜ открыть"), () -> ACHistoryMenu.openPlayerAlerts(admin, uuid, name, 0));
        gui.button(25, GuiItems.item(Items.IRON_BARS, "&c🔨 История наказаний игрока",
                "&8➜ открыть"), () -> ACHistoryMenu.openPlayerPunishments(admin, uuid, name, 0));

        // The link register, filtered to this account: the question "who else
        // is this person" belongs on the card you are already looking at.
        if (dev.bastionac.core.AuthLinks.available()) {
            java.util.List<dev.bastionac.core.AuthLinks.Link> mine =
                    dev.bastionac.core.AuthLinks.links(uuid.toString(), dev.bastionac.core.AuthLinks.MAX_ROWS);
            int open = 0;
            for (dev.bastionac.core.AuthLinks.Link l : mine) if (l.unreviewed()) open++;
            List<String> lore = new ArrayList<>();
            if (mine.isEmpty()) {
                lore.add("&7Связей с другими аккаунтами нет");
            } else {
                int limit = 0;
                for (dev.bastionac.core.AuthLinks.Link l : mine) {
                    if (limit++ >= 5) {
                        lore.add("&8…и ещё " + (mine.size() - 5));
                        break;
                    }
                    String other = l.uuidA().equals(uuid.toString()) ? l.nameB() : l.nameA();
                    lore.add("&7" + other + " &8— " + dev.bastionac.core.AuthLinks.verdictColour(l.verdict())
                            + dev.bastionac.core.AuthLinks.verdictLabel(l.verdict()));
                }
                if (open > 0) lore.add("&eБез решения: &f" + open);
            }
            lore.add("&8➜ открыть реестр");
            gui.button(22, GuiItems.item(mine.isEmpty() ? Items.LIME_DYE : Items.BUNDLE,
                    "&6⛓ Связи аккаунта &8(" + mine.size() + ")", lore), () -> ACLinkMenu.open(admin, 0));
        }

        // Actions.
        gui.button(29, GuiItems.item(Items.ENDER_PEARL, "&bТелепорт к игроку", "&8➜ клик"), () -> {
            admin.teleport((net.minecraft.server.world.ServerWorld) target.getEntityWorld(),
                    target.getX(), target.getY(), target.getZ(), Set.of(), admin.getYaw(), admin.getPitch(), false);
            admin.closeHandledScreen();
        });

        gui.button(30, GuiItems.item(Items.SPONGE, "&aСбросить VL", "&7Обнулить нарушения игрока", "&8➜ клик"), () -> {
            BastionAC.clearViolations(target);
            HistoryManager.recordPunishment(HistoryManager.Kind.VL_CLEAR, uuid, name, null, 0,
                    "админ: " + admin.getGameProfile().name());
            admin.sendMessage(TextFmt.literal("&a[BAC] VL игрока &f" + name + "&a сброшены"), false);
            openCard(admin, uuid);
        });

        gui.button(31, GuiItems.item(Items.IRON_DOOR, "&6Кикнуть", "&8с подтверждением"), () ->
                Confirm.open(admin, "&cКикнуть " + name + "?", "Отключить игрока " + name + "?", "Кикнуть",
                        () -> {
                            if (target.networkHandler != null) {
                                target.networkHandler.disconnect(TextFmt.literal("&cВы были кикнуты администрацией"));
                            }
                            HistoryManager.recordPunishment(HistoryManager.Kind.KICK, uuid, name, null, 0,
                                    "ручной кик, админ: " + admin.getGameProfile().name());
                            admin.sendMessage(TextFmt.literal("&a[BAC] " + name + " кикнут"), false);
                            openOnline(admin);
                        },
                        () -> openCard(admin, uuid)));

        gui.button(32, GuiItems.item(Items.BARRIER, "&cВыдать бан", "&8выбор срока → подтверждение"),
                () -> banDurations(admin, uuid, name));

        if (banned) {
            gui.button(33, GuiItems.item(Items.LIME_WOOL, "&aРазбанить", "&8с подтверждением"), () ->
                    Confirm.open(admin, "&aРазбанить " + name + "?", "Снять бан с " + name + "?", "Разбанить",
                            () -> {
                                BanManager.unban(uuid, name, admin.getGameProfile().name());
                                admin.sendMessage(TextFmt.literal("&a[BAC] " + name + " разбанен"), false);
                                openCard(admin, uuid);
                            },
                            () -> openCard(admin, uuid)));
        }

        gui.button(40, GuiItems.back("к списку"), () -> openOnline(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static void banDurations(ServerPlayerEntity admin, UUID uuid, String name) {
        ChestGui gui = new ChestGui(TextFmt.literal("&cБан &f" + name + " &7— срок"), 3);
        int[] hours = {1, 6, 24, 72, 168, 720};
        String[] labels = {"1 час", "6 часов", "1 день", "3 дня", "1 неделя", "30 дней"};
        int slot = 10;
        for (int i = 0; i < hours.length; i++) {
            int h = hours[i];
            String lbl = labels[i];
            gui.button(slot++, GuiItems.item(Items.CLOCK, "&e" + lbl, "&8➜ выбрать"), () ->
                    Confirm.open(admin, "&cБан " + name, "Забанить " + name + " на " + lbl + "?", "Забанить на " + lbl,
                            () -> {
                                MinecraftServer srv = admin.getEntityWorld().getServer();
                                ServerPlayerEntity t = srv.getPlayerManager().getPlayer(uuid);
                                if (t != null) BanManager.manualBan(t, h, admin.getGameProfile().name());
                                admin.sendMessage(TextFmt.literal("&a[BAC] " + name + " забанен на " + lbl), false);
                                openOnline(admin);
                            },
                            () -> banDurations(admin, uuid, name)));
        }
        gui.button(22, GuiItems.back("к карточке"), () -> openCard(admin, uuid));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private ACPlayerMenu() {}
}
