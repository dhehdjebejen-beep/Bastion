package dev.bastionac.gui;

import dev.bastionac.BastionAC;
import dev.bastionac.core.Alerts;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.HistoryManager;
import dev.bastionac.core.Lang;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/** Root screen of the BastionAC admin panel — navigation into every section. */
public final class ACPanel {

    public static void open(ServerPlayerEntity admin) {
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&cBAC&8] &7Панель античита")), 3);

        boolean myAlerts = Alerts.isEnabledFor(admin);
        gui.button(4, GuiItems.item(Items.NETHER_STAR, "&cBastionAC &7v" + BastionAC.VERSION,
                Lang.t("&7Проверок: &f") + CheckType.values().length,
                Lang.t("&7Алертов в истории: &f") + HistoryManager.recentAlerts(99999).size(),
                Lang.t("&7Наказаний в истории: &f") + HistoryManager.recentPunishments(99999).size()), () -> {});

        gui.button(10, GuiItems.item(Items.PLAYER_HEAD, Lang.t("&aОнлайн-игроки"),
                Lang.t("&7VL, история и модерация"), Lang.t("&8➜ открыть")), () -> ACPlayerMenu.openOnline(admin));

        gui.button(11, GuiItems.item(Items.WRITABLE_BOOK, Lang.t("&e📜 История алертов"),
                Lang.t("&7Что и на ком срабатывало"), Lang.t("&8➜ открыть")), () -> ACHistoryMenu.openAlerts(admin, 0));

        gui.button(12, GuiItems.item(Items.IRON_BARS, Lang.t("&c🔨 История наказаний"),
                Lang.t("&7Баны, кики, ручные действия"), Lang.t("&8➜ открыть")), () -> ACHistoryMenu.openPunishments(admin, 0));

        gui.button(13, GuiItems.item(Items.COMPARATOR, Lang.t("&b⚙ Общие настройки"),
                Lang.t("&7Алерты, сетбэк, кик, грейс…"), Lang.t("&8➜ открыть")), () -> ACSettingsMenu.openGeneral(admin));

        gui.button(14, GuiItems.item(Items.TARGET, Lang.t("&6🎯 Проверки"),
                Lang.t("&715 проверок: пороги и веса"), Lang.t("&8➜ открыть")), () -> ACSettingsMenu.openChecks(admin));

        gui.button(15, GuiItems.item(Items.NETHERITE_SWORD, Lang.t("&4🧨 Наказания"),
                Lang.t("&7Авто-бан, пороги, сроки"), Lang.t("&8➜ открыть")), () -> ACSettingsMenu.openPunishment(admin));

        gui.button(20, GuiItems.item(Items.SHIELD, Lang.t("&3🛡 Инфошилд"),
                Lang.t("&7Анти-X-ray, анти-ESP, скрытие данных"),
                Lang.t("&7о других игроках"), Lang.t("&8➜ открыть")), () -> ACShieldMenu.open(admin));

        gui.button(21, GuiItems.item(Items.IRON_CHAIN, Lang.t("&5⛓ Цепочки атак"),
                Lang.t("&7EDR-корреляция: пробы → эксплуатация"),
                Lang.t("&7Цепочек сейчас: &f") + dev.bastionac.core.AttackChainDetector.activeCount(),
                Lang.t("&8➜ открыть")), () -> ACChainMenu.open(admin));

        gui.button(22, GuiItems.item(Items.RECOVERY_COMPASS, Lang.t("&dСеть"),
                Lang.t("&7Баны с сетевым контекстом,"),
                Lang.t("&7связи аккаунтов по подсетям"),
                Lang.t("&7Записей: &f") + (dev.bastionac.core.NetworkRegistry.banNetworks().size()
                        + dev.bastionac.core.NetworkRegistry.links().size()),
                Lang.t("&8➜ открыть")), () -> ACNetworkMenu.open(admin));

        int open = dev.bastionac.core.AuthLinks.unreviewedCount();
        gui.button(23, GuiItems.item(open > 0 ? Items.BUNDLE : Items.LIME_DYE, Lang.t("&6⛓ Реестр связей"),
                Lang.t("&7Пары аккаунтов и решения по ним:"),
                Lang.t("&7доверенная, под наблюдением, мультиаккаунт."),
                open > 0 ? Lang.t("&eБез решения: &f") + open : Lang.t("&7Без решения: &fнет"),
                Lang.t("&8➜ открыть")), () -> ACLinkMenu.open(admin, 0));

        java.util.List<String> argus = new java.util.ArrayList<>();
        argus.add(Lang.t("&7Аналитика поверх проверок: читает"));
        argus.add(Lang.t("&7телеметрию игрока и выносит вердикт"));
        argus.add(Lang.t("&7в течение часа."));
        argus.add("");
        argus.addAll(dev.bastionac.argus.Argus.status());
        argus.add(Lang.t("&8➜ открыть"));
        gui.button(24, GuiItems.item(Items.ENDER_EYE, Lang.t("&5👁 Аргус — расследования"), argus), () -> ArgusMenu.open(admin));

        gui.set(16, GuiItems.item(myAlerts ? Items.LIME_DYE : Items.GRAY_DYE,
                        Lang.t("&fМои алерты: ") + (myAlerts ? Lang.t("&aВКЛ") : Lang.t("&cВЫКЛ")), Lang.t("&8➜ клик — переключить")),
                (p, t) -> { Alerts.toggle(p); open(p); });

        gui.button(18, GuiItems.item(Items.SUNFLOWER, Lang.t("&aПерезагрузить конфиг из файла"),
                "&8/bac reload"), () -> BastionAC.reloadConfig(m -> admin.sendMessage(TextFmt.literal(m), false)));

        gui.button(19, GuiItems.item(Items.BOOK, Lang.t("&aСохранить конфиг в файл"),
                Lang.t("&7Записать текущие настройки")), () -> {
            BastionAC.saveConfig();
            admin.sendMessage(TextFmt.literal(Lang.t("&a[BAC] Конфигурация сохранена в файл")), false);
        });

        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    /** Straight into the info shield settings (/bac shield). */
    public static void openShield(ServerPlayerEntity admin) {
        ACShieldMenu.open(admin);
    }

    private ACPanel() {}
}
