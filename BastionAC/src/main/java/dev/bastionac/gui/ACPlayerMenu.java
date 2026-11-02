package dev.bastionac.gui;

import dev.bastionac.BastionAC;
import dev.bastionac.core.BanManager;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.HistoryManager;
import dev.bastionac.core.Lang;
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
            admin.sendMessage(TextFmt.literal(Lang.t("&c[BAC] Игрок ") + name + Lang.t(" не в сети")), false);
            return;
        }
        openCard(admin, target.getUuid());
    }

    static void openOnline(ServerPlayerEntity admin) {
        MinecraftServer srv = admin.getEntityWorld().getServer();
        List<ServerPlayerEntity> players = srv.getPlayerManager().getPlayerList();
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&cBAC&8] &7Онлайн &8(&f") + players.size() + "&8)"), 6);

        int slot = 0;
        for (ServerPlayerEntity target : players) {
            if (slot >= 45) break;
            PlayerData d = BastionAC.data(target);
            double totalVl = d == null ? 0 : d.vl.snapshot().values().stream().mapToDouble(Double::doubleValue).sum();
            String name = target.getGameProfile().name();
            String flag = totalVl <= 0 ? Lang.t("&aчисто") : totalVl < 5 ? "&eVL " + fmt(totalVl) : "&cVL " + fmt(totalVl);
            gui.button(slot++, GuiItems.head(name, (totalVl >= 5 ? "&c" : "&a") + name,
                            Lang.t("&7Состояние: ") + flag,
                            Lang.t("&7Пинг: &f") + target.networkHandler.getLatency() + Lang.t(" мс"),
                            Lang.t("&8➜ карточка игрока")),
                    () -> openCard(admin, target.getUuid()));
        }
        if (players.isEmpty()) gui.button(22, GuiItems.item(Items.BARRIER, Lang.t("&7Никого нет онлайн")), () -> {});

        gui.button(49, GuiItems.back(Lang.t("в панель")), () -> ACPanel.open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    static void openCard(ServerPlayerEntity admin, UUID uuid) {
        MinecraftServer srv = admin.getEntityWorld().getServer();
        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(uuid);
        if (target == null) {
            admin.sendMessage(TextFmt.literal(Lang.t("&c[BAC] Игрок вышел из сети")), false);
            openOnline(admin);
            return;
        }
        String name = target.getGameProfile().name();
        PlayerData d = BastionAC.data(target);
        Map<CheckType, Double> vls = d != null ? d.vl.snapshot() : Map.of();
        boolean banned = BanManager.isBanned(uuid, name);

        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&cBAC&8] &7Игрок &f") + name), 5);

        // Header: live VL breakdown.
        List<String> vlLore = new ArrayList<>();
        if (vls.isEmpty()) {
            vlLore.add(Lang.t("&aАктивных нарушений нет"));
        } else {
            vls.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> vlLore.add("&7" + e.getKey().key + ": &f" + fmt(e.getValue())));
        }
        vlLore.add(Lang.t("&8Всего алертов в истории: &7") + HistoryManager.alertCountFor(uuid));
        if (d != null) {
            String xray = dev.bastionac.core.XrayTracker.summary(d);
            if (!xray.equals(Lang.t("нет данных")) && !xray.equals((Lang.t("руд=") + 0 + Lang.t(", камня=") + 0 + Lang.t(", скрытых=") + 0))) {
                vlLore.add(Lang.t("&7Добыча (xray-статистика): &f") + xray);
            }
        }
        if (banned) vlLore.add(Lang.t("&cСейчас в бане"));
        gui.button(4, GuiItems.head(name, "&e" + name, vlLore), () -> {});

        gui.button(19, GuiItems.item(Items.WRITABLE_BOOK, Lang.t("&e📜 История алертов игрока"),
                Lang.t("&8➜ открыть")), () -> ACHistoryMenu.openPlayerAlerts(admin, uuid, name, 0));
        gui.button(25, GuiItems.item(Items.IRON_BARS, Lang.t("&c🔨 История наказаний игрока"),
                Lang.t("&8➜ открыть")), () -> ACHistoryMenu.openPlayerPunishments(admin, uuid, name, 0));

        // The link register, filtered to this account: the question "who else
        // is this person" belongs on the card you are already looking at.
        if (dev.bastionac.core.AuthLinks.available()) {
            java.util.List<dev.bastionac.core.AuthLinks.Link> mine =
                    dev.bastionac.core.AuthLinks.links(uuid.toString(), dev.bastionac.core.AuthLinks.MAX_ROWS);
            int open = 0;
            for (dev.bastionac.core.AuthLinks.Link l : mine) if (l.unreviewed()) open++;
            List<String> lore = new ArrayList<>();
            if (mine.isEmpty()) {
                lore.add(Lang.t("&7Связей с другими аккаунтами нет"));
            } else {
                int limit = 0;
                for (dev.bastionac.core.AuthLinks.Link l : mine) {
                    if (limit++ >= 5) {
                        lore.add(Lang.t("&8…и ещё ") + (mine.size() - 5));
                        break;
                    }
                    String other = l.uuidA().equals(uuid.toString()) ? l.nameB() : l.nameA();
                    lore.add("&7" + other + " &8— " + dev.bastionac.core.AuthLinks.verdictColour(l.verdict())
                            + dev.bastionac.core.AuthLinks.verdictLabel(l.verdict()));
                }
                if (open > 0) lore.add(Lang.t("&eБез решения: &f") + open);
            }
            lore.add(Lang.t("&8➜ открыть реестр"));
            gui.button(22, GuiItems.item(mine.isEmpty() ? Items.LIME_DYE : Items.BUNDLE,
                    Lang.t("&6⛓ Связи аккаунта &8(") + mine.size() + ")", lore), () -> ACLinkMenu.open(admin, 0));
        }

        // Argus: this player's cases and a button to start one now.
        {
            java.util.List<dev.bastionac.argus.CaseFile> cases = dev.bastionac.argus.Argus.casesOf(uuid);
            List<String> lore = new ArrayList<>();
            if (cases.isEmpty()) lore.add(Lang.t("&7Расследований не было"));
            int shown = 0;
            for (int i = cases.size() - 1; i >= 0 && shown < 4; i--, shown++) {
                dev.bastionac.argus.CaseFile c = cases.get(i);
                lore.add("&7#" + c.id + " " + c.verdict.colour + c.verdict.label()
                        + (c.label != dev.bastionac.argus.CaseFile.Label.NONE ? " &8(" + c.label.label() + ")" : ""));
            }
            lore.add(Lang.t("&8➜ дела и «начать расследование»"));
            gui.button(20, GuiItems.item(Items.ENDER_EYE, Lang.t("&5👁 Аргус &8(") + cases.size() + ")", lore),
                    () -> ArgusMenu.openPlayer(admin, uuid, name));
        }

        // Actions.
        gui.button(29, GuiItems.item(Items.ENDER_PEARL, Lang.t("&bТелепорт к игроку"), Lang.t("&8➜ клик")), () -> {
            admin.teleport((net.minecraft.server.world.ServerWorld) target.getEntityWorld(),
                    target.getX(), target.getY(), target.getZ(), Set.of(), admin.getYaw(), admin.getPitch(), false);
            admin.closeHandledScreen();
        });

        gui.button(30, GuiItems.item(Items.SPONGE, Lang.t("&aСбросить VL"), Lang.t("&7Обнулить нарушения игрока"), Lang.t("&8➜ клик")), () -> {
            BastionAC.clearViolations(target);
            HistoryManager.recordPunishment(HistoryManager.Kind.VL_CLEAR, uuid, name, null, 0,
                    Lang.t("админ: ") + admin.getGameProfile().name());
            admin.sendMessage(TextFmt.literal(Lang.t("&a[BAC] VL игрока &f") + name + Lang.t("&a сброшены")), false);
            openCard(admin, uuid);
        });

        gui.button(31, GuiItems.item(Items.IRON_DOOR, Lang.t("&6Кикнуть"), Lang.t("&8с подтверждением")), () ->
                Confirm.open(admin, Lang.t("&cКикнуть ") + name + "?", Lang.t("Отключить игрока ") + name + "?", Lang.t("Кикнуть"),
                        () -> {
                            if (target.networkHandler != null) {
                                target.networkHandler.disconnect(TextFmt.literal(Lang.t("&cВы были кикнуты администрацией")));
                            }
                            HistoryManager.recordPunishment(HistoryManager.Kind.KICK, uuid, name, null, 0,
                                    Lang.t("ручной кик, админ: ") + admin.getGameProfile().name());
                            admin.sendMessage(TextFmt.literal("&a[BAC] " + name + Lang.t(" кикнут")), false);
                            openOnline(admin);
                        },
                        () -> openCard(admin, uuid)));

        gui.button(32, GuiItems.item(Items.BARRIER, Lang.t("&cВыдать бан"), Lang.t("&8выбор срока → подтверждение")),
                () -> banDurations(admin, uuid, name));

        if (banned) {
            gui.button(33, GuiItems.item(Items.LIME_WOOL, Lang.t("&aРазбанить"), Lang.t("&8с подтверждением")), () ->
                    Confirm.open(admin, Lang.t("&aРазбанить ") + name + "?", Lang.t("Снять бан с ") + name + "?", Lang.t("Разбанить"),
                            () -> {
                                BanManager.unban(uuid, name, admin.getGameProfile().name());
                                admin.sendMessage(TextFmt.literal("&a[BAC] " + name + Lang.t(" разбанен")), false);
                                openCard(admin, uuid);
                            },
                            () -> openCard(admin, uuid)));
        }

        gui.button(40, GuiItems.back(Lang.t("к списку")), () -> openOnline(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static void banDurations(ServerPlayerEntity admin, UUID uuid, String name) {
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&cБан &f") + name + Lang.t(" &7— срок")), 3);
        int[] hours = {1, 6, 24, 72, 168, 720};
        String[] labels = {Lang.t("1 час"), Lang.t("6 часов"), Lang.t("1 день"), Lang.t("3 дня"), Lang.t("1 неделя"), Lang.t("30 дней")};
        int slot = 10;
        for (int i = 0; i < hours.length; i++) {
            int h = hours[i];
            String lbl = labels[i];
            gui.button(slot++, GuiItems.item(Items.CLOCK, "&e" + lbl, Lang.t("&8➜ выбрать")), () ->
                    Confirm.open(admin, Lang.t("&cБан ") + name, Lang.t("Забанить ") + name + Lang.t(" на ") + lbl + "?", Lang.t("Забанить на ") + lbl,
                            () -> {
                                MinecraftServer srv = admin.getEntityWorld().getServer();
                                ServerPlayerEntity t = srv.getPlayerManager().getPlayer(uuid);
                                if (t != null) BanManager.manualBan(t, h, admin.getGameProfile().name());
                                admin.sendMessage(TextFmt.literal("&a[BAC] " + name + Lang.t(" забанен на ") + lbl), false);
                                openOnline(admin);
                            },
                            () -> banDurations(admin, uuid, name)));
        }
        gui.button(22, GuiItems.back(Lang.t("к карточке")), () -> openCard(admin, uuid));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private ACPlayerMenu() {}
}
