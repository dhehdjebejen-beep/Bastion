package dev.bastionac.gui;

import dev.bastionac.BastionAC;
import dev.bastionac.config.ACConfig;
import dev.bastionac.core.Lang;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.AnvilGui;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The info shield: what the server keeps from a client so that a cheat has
 * nothing to draw — other players' health and gear, sound origins, buried ores
 * and storage, players and items behind walls. Edits apply at once and persist,
 * like the rest of the settings.
 */
final class ACShieldMenu {

    static void open(ServerPlayerEntity admin) {
        ACConfig.General g = BastionAC.config().general;
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&cBAC&8] &7Инфошилд")), 6);

        gui.button(4, GuiItems.item(Items.SHIELD, Lang.t("&3🛡 Инфошилд"),
                Lang.t("&7Сервер не отправляет клиенту то,"),
                Lang.t("&7что игрок не должен видеть."),
                Lang.t("&7Читу нечего рисовать."),
                "",
                Lang.t("&7Изменения действуют сразу и"),
                Lang.t("&7сохраняются в config.json.")), () -> {});

        // ---- row 1: other players' data
        section(gui, 9, Items.NAME_TAG, Lang.t("&fДанные о других игроках"));
        toggle(gui, 10, Items.GOLDEN_APPLE, Lang.t("Скрывать здоровье"), g.hideOtherPlayersHealth,
                List.of(Lang.t("&7Чужое здоровье уходит как «полное»,"), Lang.t("&7поглощение — как нулевое.")),
                () -> g.hideOtherPlayersHealth = !g.hideOtherPlayersHealth, admin);
        toggle(gui, 11, Items.DIAMOND_CHESTPLATE, Lang.t("Скрывать детали снаряжения"), g.hideOtherPlayersEquipmentDetails,
                List.of(Lang.t("&7Видно, что надето и в руке,"), Lang.t("&7но без чар, прочности и названий.")),
                () -> g.hideOtherPlayersEquipmentDetails = !g.hideOtherPlayersEquipmentDetails, admin);
        toggle(gui, 12, Items.NOTE_BLOCK, Lang.t("Скрывать источник звуков"), g.hideSoundOrigins,
                List.of(Lang.t("&7Иссушитель, дракон, портал, горны:"), Lang.t("&7направление верное, координат нет.")),
                () -> g.hideSoundOrigins = !g.hideSoundOrigins, admin);
        toggle(gui, 13, Items.SKELETON_SKULL, Lang.t("Мёртвые не действуют"), g.ignoreDeadPlayerActions,
                List.of(Lang.t("&7С экрана смерти нельзя кликать,"), Lang.t("&7копать и двигать предметы.")),
                () -> g.ignoreDeadPlayerActions = !g.ignoreDeadPlayerActions, admin);
        number(gui, 14, Items.NETHERRACK, Lang.t("Прорисовка в Незере (чанки)"), g.netherViewDistance, 1, 0, 32,
                List.of(Lang.t("&7Потолок прорисовки в Незере:"), Lang.t("&7дальше чит не видит базы."),
                        Lang.t("&70 — без потолка.")),
                v -> g.netherViewDistance = v == 1 ? (g.netherViewDistance == 0 ? 2 : 0) : v, admin);

        // ---- row 2: ores and storage
        section(gui, 18, Items.DIAMOND_ORE, Lang.t("&fРуды и хранилища (анти-X-ray)"));
        toggle(gui, 19, Items.DEEPSLATE_DIAMOND_ORE, Lang.t("Прятать руды в толще"), g.hideEnclosedOres,
                List.of(Lang.t("&7Руда без соседнего воздуха"), Lang.t("&7уходит клиенту камнем."), chunks()),
                () -> g.hideEnclosedOres = !g.hideEnclosedOres, admin);
        toggle(gui, 20, Items.CHEST, Lang.t("Прятать замурованные хранилища"), g.hideEnclosedContainers,
                List.of(Lang.t("&7Сундук, закрытый со всех сторон,"), Lang.t("&7уходит клиенту камнем."), chunks()),
                () -> g.hideEnclosedContainers = !g.hideEnclosedContainers, admin);
        toggle(gui, 21, Items.BARREL, Lang.t("Прятать хранилища вдали"), g.hideFarStorage,
                List.of(Lang.t("&7Сундуки, печи, бочки, шалкеры и т.д."), Lang.t("&7дальше радиуса показа — камнем.")),
                () -> g.hideFarStorage = !g.hideFarStorage, admin);
        number(gui, 22, Items.SPYGLASS, Lang.t("Радиус показа хранилищ"), g.storageRevealRadius, 4, 8, 128,
                List.of(Lang.t("&7Ближе — показывается, если есть"), Lang.t("&7прямая видимость от глаз игрока.")),
                v -> g.storageRevealRadius = v, admin);
        number(gui, 23, Items.TORCH, Lang.t("Хранилища видны всегда ближе"), g.storageAlwaysVisibleRadius, 1, 0, 32,
                List.of(Lang.t("&7Блоков: внутри — без проверки")),
                v -> g.storageAlwaysVisibleRadius = v, admin);
        number(gui, 24, Items.CLOCK, Lang.t("Проверка хранилищ (тики)"), g.storageCheckIntervalTicks, 1, 1, 100,
                List.of(Lang.t("&7Как часто пересчитывать видимость."), Lang.t("&7Меньше — быстрее, но дороже.")),
                v -> g.storageCheckIntervalTicks = v, admin);

        // ---- row 3: players through walls
        section(gui, 27, Items.ENDER_EYE, Lang.t("&fИгроки сквозь стены (анти-ESP)"));
        toggle(gui, 28, Items.ENDER_EYE, Lang.t("Прятать игроков за стенами"), g.espShield,
                List.of(Lang.t("&7Игрок уходит клиенту, только пока"), Lang.t("&7его можно увидеть глазами.")),
                () -> g.espShield = !g.espShield, admin);
        number(gui, 29, Items.LEAD, Lang.t("Игроки видны всегда ближе"), g.espAlwaysVisibleRadius, 1, 0, 64,
                List.of(Lang.t("&7Блоков: внутри — без проверки")),
                v -> g.espAlwaysVisibleRadius = v, admin);
        number(gui, 30, Items.COMPASS, Lang.t("Угол обзора (градусы)"), g.espViewConeDegrees, 10, 90, 360,
                List.of(Lang.t("&7Игрок за спиной вне этого угла"), Lang.t("&7не отправляется. 360 — без угла.")),
                v -> g.espViewConeDegrees = v, admin);
        number(gui, 31, Items.REPEATER, Lang.t("Проверка игроков (тики)"), g.espCheckIntervalTicks, 1, 1, 40,
                List.of(Lang.t("&7Как часто пересчитывать видимость."), Lang.t("&7Меньше — быстрее, но дороже.")),
                v -> g.espCheckIntervalTicks = v, admin);
        number(gui, 32, Items.CLOCK, Lang.t("Задержка скрытия (тики)"), g.espHideDelayTicks, 5, 0, 200,
                List.of(Lang.t("&7Сколько ждать, прежде чем убрать"), Lang.t("&7ушедшего за угол: без мерцания.")),
                v -> g.espHideDelayTicks = v, admin);

        // ---- row 4: items through walls
        section(gui, 36, Items.HOPPER, Lang.t("&fПредметы сквозь стены"));
        toggle(gui, 37, Items.HOPPER, Lang.t("Прятать предметы за стенами"), g.espShieldItems,
                List.of(Lang.t("&7Выброшенный предмет уходит клиенту,"), Lang.t("&7только пока до него есть видимость.")),
                () -> g.espShieldItems = !g.espShieldItems, admin);
        number(gui, 38, Items.TORCH, Lang.t("Предметы видны всегда ближе"), g.espItemAlwaysVisibleRadius, 1, 0, 32,
                List.of(Lang.t("&7Блоков: внутри — без проверки")),
                v -> g.espItemAlwaysVisibleRadius = v, admin);
        number(gui, 39, Items.REPEATER, Lang.t("Проверка предметов (тики)"), g.espItemCheckIntervalTicks, 1, 1, 100,
                List.of(Lang.t("&7Как часто пересчитывать видимость."), Lang.t("&7Меньше — быстрее, но дороже.")),
                v -> g.espItemCheckIntervalTicks = v, admin);

        gui.button(49, GuiItems.back(Lang.t("в панель")), () -> ACPanel.open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    /** A chunk the client already has keeps what it was sent: the switch shows on chunks sent after it. */
    private static String chunks() {
        return Lang.t("&8Действует на заново загруженные чанки.");
    }

    private static void section(ChestGui gui, int slot, Item icon, String title) {
        gui.button(slot, GuiItems.item(icon, title), () -> {});
    }

    private static void toggle(ChestGui gui, int slot, Item icon, String name, boolean value, List<String> about,
                               Runnable flip, ServerPlayerEntity admin) {
        List<String> lore = new ArrayList<>(about);
        lore.add(Lang.t("&8➜ клик — переключить"));
        gui.set(slot, GuiItems.item(icon, (value ? "&a" : "&c") + name + ": " + (value ? Lang.t("&aВКЛ") : Lang.t("&cВЫКЛ")),
                lore), (p, t) -> {
            flip.run();
            BastionAC.saveConfig();
            open(admin);
        });
    }

    private static void number(ChestGui gui, int slot, Item icon, String label, int value, int step, int min, int max,
                               List<String> about, IntConsumer setter, ServerPlayerEntity admin) {
        List<String> lore = new ArrayList<>(about);
        lore.add(Lang.t("&fЗначение: &a") + value);
        lore.add(Lang.t("&8ЛКМ +") + step + Lang.t(" · ПКМ −") + step);
        lore.add(Lang.t("&8Shift — ввести точно"));
        gui.set(slot, GuiItems.item(icon, "&e" + label, lore), (p, t) -> {
            if (t.shift()) {
                AnvilGui.open(admin, "&7" + label, String.valueOf(value), s -> {
                    try {
                        setter.accept(Math.max(min, Math.min(max, Integer.parseInt(s.trim()))));
                        BastionAC.saveConfig();
                    } catch (NumberFormatException ignored) {
                    }
                    open(admin);
                });
            } else {
                setter.accept(Math.max(min, Math.min(max, value + (t.left() ? step : -step))));
                BastionAC.saveConfig();
                open(admin);
            }
        });
    }

    private ACShieldMenu() {}
}
