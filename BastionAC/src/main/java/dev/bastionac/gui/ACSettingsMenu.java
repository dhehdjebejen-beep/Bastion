package dev.bastionac.gui;

import dev.bastionac.BastionAC;
import dev.bastionac.config.ACConfig;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.AnvilGui;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/** Live settings editor: general, per-check and punishment. Edits apply instantly and persist. */
final class ACSettingsMenu {

    // ------------------------------------------------------------------ general

    static void openGeneral(ServerPlayerEntity admin) {
        ACConfig.General g = BastionAC.config().general;
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Общие настройки"), 6);

        toggle(gui, 10, Items.BELL, "Алерты опам (по умолч.)", g.alertsToOps,
                () -> { g.alertsToOps = !g.alertsToOps; saveAnd(admin, () -> openGeneral(admin)); });
        toggle(gui, 11, Items.ENDER_PEARL, "Сетбэк (откат назад)", g.setbackEnabled,
                () -> { g.setbackEnabled = !g.setbackEnabled; saveAnd(admin, () -> openGeneral(admin)); });
        toggle(gui, 12, Items.FEATHER, "Мгновенный сетбэк", g.instantMovementSetback,
                () -> { g.instantMovementSetback = !g.instantMovementSetback; saveAnd(admin, () -> openGeneral(admin)); });
        toggle(gui, 13, Items.IRON_DOOR, "Кик по VL (мастер)", g.kickEnabled,
                () -> { g.kickEnabled = !g.kickEnabled; saveAnd(admin, () -> openGeneral(admin)); });
        toggle(gui, 14, Items.WRITABLE_BOOK, "Лог нарушений в консоль", g.logViolationsToConsole,
                () -> { g.logViolationsToConsole = !g.logViolationsToConsole; saveAnd(admin, () -> openGeneral(admin)); });

        numInt(gui, 19, admin, Items.CLOCK, "Кулдаун алертов (сек)", g.alertCooldownSeconds, 1, 1, 300,
                v -> g.alertCooldownSeconds = v, () -> openGeneral(admin));
        numInt(gui, 20, admin, Items.REPEATER, "Мин. интервал сетбэка (тики)", g.setbackMinIntervalTicks, 1, 1, 100,
                v -> g.setbackMinIntervalTicks = v, () -> openGeneral(admin));
        numInt(gui, 21, admin, Items.SHIELD, "Грейс после входа (тики)", g.joinGraceTicks, 10, 20, 1200,
                v -> g.joinGraceTicks = v, () -> openGeneral(admin));
        numDouble(gui, 22, admin, Items.SUGAR, "Допуск скорости (×)", g.speedToleranceMultiplier, 0.05, 0.5, 5.0,
                v -> g.speedToleranceMultiplier = v, () -> openGeneral(admin));
        numInt(gui, 23, admin, Items.COMPASS, "Timer: мин. интервал (мс)", g.timerMinIntervalMs, 1, 10, 49,
                v -> g.timerMinIntervalMs = v, () -> openGeneral(admin));
        numInt(gui, 24, admin, Items.DIAMOND_SWORD, "Потолок CPS (ударов/сек)", g.maxCps, 1, 10, 20,
                v -> g.maxCps = v, () -> openGeneral(admin));

        gui.button(49, GuiItems.back("в панель"), () -> ACPanel.open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    // ------------------------------------------------------------------ checks list

    static void openChecks(ServerPlayerEntity admin) {
        ACConfig cfg = BastionAC.config();
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Проверки &8(&f" + CheckType.values().length + "&8)"), 6);
        int slot = 0;
        for (CheckType type : CheckType.values()) {
            ACConfig.CheckCfg c = cfg.check(type.key);
            gui.button(slot++, GuiItems.item(c.enabled ? Items.LIME_DYE : Items.GRAY_DYE,
                            (c.enabled ? "&a" : "&c") + type.key,
                            "&7alertVl &f" + fmt(c.alertVl) + " &8· &7mitigate &f" + disp(c.mitigateVl)
                                    + " &8· &7kick &f" + disp(c.kickVl),
                            "&7weight &f" + fmt(c.weight) + " &8· &7decay &f" + fmt(c.decayPerSecond),
                            "&8➜ редактировать"),
                    () -> openCheckEditor(admin, type));
        }
        gui.button(49, GuiItems.back("в панель"), () -> ACPanel.open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    static void openCheckEditor(ServerPlayerEntity admin, CheckType type) {
        ACConfig.CheckCfg c = BastionAC.config().check(type.key);
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Проверка &f" + type.key), 5);

        toggle(gui, 4, c.enabled ? Items.LIME_WOOL : Items.RED_WOOL, "Проверка включена", c.enabled,
                () -> { c.enabled = !c.enabled; saveAnd(admin, () -> openCheckEditor(admin, type)); });

        numDouble(gui, 19, admin, Items.GOLD_NUGGET, "weight (вес нарушения)", c.weight, 0.5, 0.1, 50,
                v -> c.weight = v, () -> openCheckEditor(admin, type));
        numDouble(gui, 20, admin, Items.YELLOW_DYE, "alertVl (порог алерта)", c.alertVl, 0.5, 0.5, 1000,
                v -> c.alertVl = v, () -> openCheckEditor(admin, type));
        numDouble(gui, 21, admin, Items.ENDER_PEARL, "mitigateVl (сетбэк, 0=выкл)", c.mitigateVl, 0.5, 0, 1000,
                v -> c.mitigateVl = v, () -> openCheckEditor(admin, type));
        numDouble(gui, 22, admin, Items.IRON_DOOR, "kickVl (кик, 0=выкл)", c.kickVl, 0.5, 0, 1000,
                v -> c.kickVl = v, () -> openCheckEditor(admin, type));
        numDouble(gui, 23, admin, Items.CLOCK, "decayPerSecond (спад VL/сек)", c.decayPerSecond, 0.05, 0.05, 100,
                v -> c.decayPerSecond = v, () -> openCheckEditor(admin, type));

        gui.button(40, GuiItems.back("к списку проверок"), () -> openChecks(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    // ------------------------------------------------------------------ punishment

    static void openPunishment(ServerPlayerEntity admin) {
        ACConfig.Punishment p = BastionAC.config().punishment;
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Наказания"), 6);

        toggle(gui, 10, Items.NETHERITE_SWORD, "Авто-темп-бан", p.autoTempbanEnabled,
                () -> { p.autoTempbanEnabled = !p.autoTempbanEnabled; saveAnd(admin, () -> openPunishment(admin)); });
        toggle(gui, 11, Items.BELL, "Оповещать о бане всех", p.broadcastBan,
                () -> { p.broadcastBan = !p.broadcastBan; saveAnd(admin, () -> openPunishment(admin)); });

        numInt(gui, 19, admin, Items.PAPER, "Алертов до бана", p.alertsToBan, 1, 1, 100,
                v -> p.alertsToBan = v, () -> openPunishment(admin));
        numInt(gui, 20, admin, Items.CLOCK, "Окно алертов (мин)", p.alertWindowMinutes, 5, 1, 1440,
                v -> p.alertWindowMinutes = v, () -> openPunishment(admin));
        numInt(gui, 21, admin, Items.COMPASS, "История банов (часы)", p.banHistoryHours, 6, 1, 8760,
                v -> p.banHistoryHours = v, () -> openPunishment(admin));

        // Escalation ladder (edit existing steps in place).
        if (p.banStepsHours != null) {
            int slot = 28;
            for (int i = 0; i < p.banStepsHours.size() && slot <= 34; i++, slot++) {
                final int idx = i;
                int val = p.banStepsHours.get(i) == null ? 1 : p.banStepsHours.get(i);
                numInt(gui, slot, admin, Items.IRON_BARS, "Ступень " + (i + 1) + " (часы)", val, 1, 1, 8760,
                        v -> p.banStepsHours.set(idx, v), () -> openPunishment(admin));
            }
        }
        gui.button(16, GuiItems.item(Items.BOOK, "&7Исключённые из бана проверки",
                "&f" + (BastionAC.config().punishment.banExcludedChecks == null ? "—"
                        : String.join(", ", BastionAC.config().punishment.banExcludedChecks)),
                "&8Правится в config.json"), () -> {});

        gui.button(49, GuiItems.back("в панель"), () -> ACPanel.open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    // ------------------------------------------------------------------ widgets

    private static void toggle(ChestGui gui, int slot, Item icon, String name, boolean value, Runnable onFlip) {
        gui.set(slot, GuiItems.item(icon, (value ? "&a" : "&c") + name + ": " + (value ? "&aВКЛ" : "&cВЫКЛ"),
                "&8➜ клик — переключить"), (p, t) -> onFlip.run());
    }

    private static void numInt(ChestGui gui, int slot, ServerPlayerEntity admin, Item icon, String label,
                               int value, int step, int min, int max, IntConsumer setter, Runnable reopen) {
        gui.set(slot, GuiItems.item(icon, "&e" + label,
                        "&fЗначение: &a" + value,
                        "&8ЛКМ +" + step + " · ПКМ −" + step, "&8Shift — ввести точно"),
                (p, t) -> {
                    if (t.shift()) {
                        AnvilGui.open(admin, "&7" + label, String.valueOf(value), s -> {
                            try {
                                setter.accept(clampI(Integer.parseInt(s.trim()), min, max));
                                BastionAC.saveConfig();
                            } catch (NumberFormatException ignored) {
                            }
                            reopen.run();
                        });
                    } else {
                        setter.accept(clampI(value + (t.left() ? step : -step), min, max));
                        BastionAC.saveConfig();
                        reopen.run();
                    }
                });
    }

    private static void numDouble(ChestGui gui, int slot, ServerPlayerEntity admin, Item icon, String label,
                                  double value, double step, double min, double max, DoubleConsumer setter, Runnable reopen) {
        gui.set(slot, GuiItems.item(icon, "&e" + label,
                        "&fЗначение: &a" + disp(value),
                        "&8ЛКМ +" + fmt(step) + " · ПКМ −" + fmt(step), "&8Shift — ввести точно"),
                (p, t) -> {
                    if (t.shift()) {
                        AnvilGui.open(admin, "&7" + label, fmt(value), s -> {
                            try {
                                setter.accept(clampD(Double.parseDouble(s.trim().replace(',', '.')), min, max));
                                BastionAC.saveConfig();
                            } catch (NumberFormatException ignored) {
                            }
                            reopen.run();
                        });
                    } else {
                        setter.accept(clampD(value + (t.left() ? step : -step), min, max));
                        BastionAC.saveConfig();
                        reopen.run();
                    }
                });
    }

    private static void saveAnd(ServerPlayerEntity admin, Runnable reopen) {
        BastionAC.saveConfig();
        reopen.run();
    }

    private static int clampI(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double clampD(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    /** Threshold display: "выкл" for a disabled (≤0) threshold. */
    private static String disp(double v) { return v <= 0 ? "выкл" : fmt(v); }

    private ACSettingsMenu() {}
}
