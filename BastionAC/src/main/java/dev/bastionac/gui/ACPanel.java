package dev.bastionac.gui;

import dev.bastionac.BastionAC;
import dev.bastionac.core.Alerts;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.HistoryManager;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/** Root screen of the BastionAC admin panel — navigation into every section. */
public final class ACPanel {

    public static void open(ServerPlayerEntity admin) {
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Панель античита"), 3);

        boolean myAlerts = Alerts.isEnabledFor(admin);
        gui.button(4, GuiItems.item(Items.NETHER_STAR, "&cBastionAC &7v" + BastionAC.VERSION,
                "&7Проверок: &f" + CheckType.values().length,
                "&7Алертов в истории: &f" + HistoryManager.recentAlerts(99999).size(),
                "&7Наказаний в истории: &f" + HistoryManager.recentPunishments(99999).size()), () -> {});

        gui.button(10, GuiItems.item(Items.PLAYER_HEAD, "&aОнлайн-игроки",
                "&7VL, история и модерация", "&8➜ открыть"), () -> ACPlayerMenu.openOnline(admin));

        gui.button(11, GuiItems.item(Items.WRITABLE_BOOK, "&e📜 История алертов",
                "&7Что и на ком срабатывало", "&8➜ открыть"), () -> ACHistoryMenu.openAlerts(admin, 0));

        gui.button(12, GuiItems.item(Items.IRON_BARS, "&c🔨 История наказаний",
                "&7Баны, кики, ручные действия", "&8➜ открыть"), () -> ACHistoryMenu.openPunishments(admin, 0));

        gui.button(13, GuiItems.item(Items.COMPARATOR, "&b⚙ Общие настройки",
                "&7Алерты, сетбэк, кик, грейс…", "&8➜ открыть"), () -> ACSettingsMenu.openGeneral(admin));

        gui.button(14, GuiItems.item(Items.TARGET, "&6🎯 Проверки",
                "&715 проверок: пороги и веса", "&8➜ открыть"), () -> ACSettingsMenu.openChecks(admin));

        gui.button(15, GuiItems.item(Items.NETHERITE_SWORD, "&4🧨 Наказания",
                "&7Авто-бан, пороги, сроки", "&8➜ открыть"), () -> ACSettingsMenu.openPunishment(admin));

        gui.button(21, GuiItems.item(Items.IRON_CHAIN, "&5⛓ Цепочки атак",
                "&7EDR-корреляция: пробы → эксплуатация",
                "&7Цепочек сейчас: &f" + dev.bastionac.core.AttackChainDetector.activeCount(),
                "&8➜ открыть"), () -> ACChainMenu.open(admin));

        gui.button(22, GuiItems.item(Items.RECOVERY_COMPASS, "&dСеть",
                "&7Баны с сетевым контекстом,",
                "&7связи аккаунтов по подсетям",
                "&7Записей: &f" + (dev.bastionac.core.NetworkRegistry.banNetworks().size()
                        + dev.bastionac.core.NetworkRegistry.links().size()),
                "&8➜ открыть"), () -> ACNetworkMenu.open(admin));

        int open = dev.bastionac.core.AuthLinks.unreviewedCount();
        gui.button(23, GuiItems.item(open > 0 ? Items.BUNDLE : Items.LIME_DYE, "&6⛓ Реестр связей",
                "&7Пары аккаунтов и решения по ним:",
                "&7доверенная, под наблюдением, мультиаккаунт.",
                open > 0 ? "&eБез решения: &f" + open : "&7Без решения: &fнет",
                "&8➜ открыть"), () -> ACLinkMenu.open(admin, 0));

        gui.set(16, GuiItems.item(myAlerts ? Items.LIME_DYE : Items.GRAY_DYE,
                        "&fМои алерты: " + (myAlerts ? "&aВКЛ" : "&cВЫКЛ"), "&8➜ клик — переключить"),
                (p, t) -> { Alerts.toggle(p); open(p); });

        gui.button(18, GuiItems.item(Items.SUNFLOWER, "&aПерезагрузить конфиг из файла",
                "&8/bac reload"), () -> BastionAC.reloadConfig(m -> admin.sendMessage(TextFmt.literal(m), false)));

        gui.button(19, GuiItems.item(Items.BOOK, "&aСохранить конфиг в файл",
                "&7Записать текущие настройки"), () -> {
            BastionAC.saveConfig();
            admin.sendMessage(TextFmt.literal("&a[BAC] Конфигурация сохранена в файл"), false);
        });

        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private ACPanel() {}
}
