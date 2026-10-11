package dev.bastionac.gui;

import dev.bastionac.argus.Argus;
import dev.bastionac.argus.CaseFile;
import dev.bastionac.argus.Calibration;
import dev.bastionac.argus.Signal;
import dev.bastionac.argus.features.Features;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Argus section of the panel: the list of cases, one case's file with
 * the findings and the owner's buttons (cheater / not a cheater / unclear,
 * ban, re-analyse, close), the mode and the model.
 */
public final class ArgusMenu {

    private static final int PAGE = 36;

    private ArgusMenu() {}

    // ---------------------------------------------------------------- list

    public static void open(ServerPlayerEntity admin) {
        open(admin, 0, false);
    }

    static void open(ServerPlayerEntity admin, int page, boolean all) {
        List<CaseFile> cases = new ArrayList<>(Argus.cases());
        if (!all) cases.removeIf(c -> !c.open() && c.label == CaseFile.Label.NONE);
        // Open and suspicious first, then newest.
        cases.sort(Comparator.<CaseFile>comparingInt(c -> c.open() ? 0 : 1)
                .thenComparingInt(c -> -c.verdict.ordinal())
                .thenComparingLong(c -> -Math.max(c.lastSignalAt, c.lastAnalyzedAt)));
        int pages = Math.max(1, (cases.size() + PAGE - 1) / PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&5Аргус&8] &7Дела &8(&f" + cases.size() + "&8)"), 6);

        int slot = 0;
        for (int i = p * PAGE; i < Math.min(cases.size(), (p + 1) * PAGE); i++) {
            CaseFile c = cases.get(i);
            gui.button(slot++, caseIcon(c), () -> openCase(admin, c.id));
        }
        if (cases.isEmpty()) {
            gui.button(22, GuiItems.item(Items.LIME_DYE, "&aДел нет",
                    "&7Дело откроется само: цепочка EDR, алерт,",
                    "&7проба X-ray, откаты сервера — или", "&7«Начать расследование» в карточке игрока."), () -> {});
        }

        List<String> st = new ArrayList<>(Argus.status());
        st.add("");
        st.add("&8Аргус не банит за одиночный флаг: он читает");
        st.add("&8всю телеметрию игрока вокруг события и даёт");
        st.add("&8вердикт в течение часа.");
        gui.button(45, GuiItems.item(Items.ENDER_EYE, "&5Аргус — расследования", st), () -> {});
        gui.button(46, modeButton(), () -> chooseMode(admin));
        gui.button(47, modelButton(), () -> {});
        gui.button(48, GuiItems.item(all ? Items.BOOK : Items.WRITABLE_BOOK,
                all ? "&fПоказаны все дела" : "&fПоказаны открытые и размеченные",
                "&8клик — переключить"), () -> open(admin, 0, !all));
        gui.button(49, GuiItems.back("в панель"), () -> ACPanel.open(admin));
        if (p > 0) gui.button(52, GuiItems.item(Items.ARROW, "&7← стр. " + p), () -> open(admin, p - 1, all));
        if (p + 1 < pages) gui.button(53, GuiItems.item(Items.ARROW, "&7стр. " + (p + 2) + " →"), () -> open(admin, p + 1, all));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static net.minecraft.item.ItemStack caseIcon(CaseFile c) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Вердикт: " + c.verdict.colour + c.verdict.label
                + (c.analyses > 0 ? String.format(Locale.ROOT, " &8(%.0f%%)", c.score * 100) : ""));
        lore.add("&7Статус: &f" + statusLabel(c));
        if (c.label != CaseFile.Label.NONE) lore.add("&7Решение: &f" + c.label.label + " &8(" + c.labelBy + ")");
        lore.add("&7Открыто: &f" + GuiItems.ago(System.currentTimeMillis() - c.openedAt) + " назад");
        lore.add("&7Сигналы: &f" + signalsLine(c));
        int n = 0;
        for (String f : c.findings) {
            if (n++ >= 3) break;
            for (String w : wrap("• " + f, 46)) lore.add("&8" + w);
        }
        lore.add("&8клик — открыть дело");
        return GuiItems.head(c.name, iconColour(c) + c.name + " &8#" + c.id, lore);
    }

    // ---------------------------------------------------------------- one case

    public static void openCase(ServerPlayerEntity admin, String id) {
        CaseFile c = Argus.get(id);
        if (c == null) {
            admin.sendMessage(TextFmt.literal("&c[Аргус] Дело #" + id + " не найдено"), false);
            return;
        }
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&5Аргус&8] &7Дело &f#" + c.id + " &7— &f" + c.name), 6);

        List<String> head = new ArrayList<>();
        head.add("&7Вердикт: " + c.verdict.colour + c.verdict.label);
        if (c.analyses > 0) {
            head.add(String.format(Locale.ROOT, "&7Оценка: &f%.1f%% &8(улик %.1f, сигналы %.1f)", c.score * 100, c.evidence, c.signalEvidence));
            c.categories.forEach((k, v) -> head.add(String.format(Locale.ROOT, "&7  %s: &f%.0f%%", k, v * 100)));
        }
        head.add("&7Статус: &f" + statusLabel(c));
        head.add("&7Разборов: &f" + c.analyses + (c.analyses > 0
                ? " &8(последний " + GuiItems.ago(System.currentTimeMillis() - c.lastAnalyzedAt) + " назад)" : ""));
        head.add(String.format(Locale.ROOT, "&7Данных: &f%.1f мин &8(файлов %d%s)", c.dataMinutes, c.sessionFiles.size(),
                c.worldSampled ? ", мир прочитан" : ""));
        if (c.lastError != null) head.add("&cОшибка разбора: " + c.lastError);
        if (c.reportFile != null) {
            head.add("&8Отчёт:");
            for (String w : wrap(c.reportFile, 46)) head.add("&8" + w);
        }
        gui.button(4, GuiItems.head(c.name, iconColour(c) + c.name + " &8#" + c.id, head), () -> {});

        // Findings.
        List<String> fl = new ArrayList<>();
        if (c.findings.isEmpty()) fl.add("&7Разбора ещё не было");
        for (String f : c.findings) for (String w : wrap("• " + f, 50)) fl.add("&f" + w);
        gui.button(19, GuiItems.item(Items.SPYGLASS, "&eВыводы", fl), () -> {});

        // Signals.
        List<String> sl = new ArrayList<>();
        int from = Math.max(0, c.signals.size() - 12);
        for (int i = from; i < c.signals.size(); i++) {
            CaseFile.SignalEntry e = c.signals.get(i);
            String kind;
            try {
                kind = Signal.Kind.valueOf(e.kind).label;
            } catch (IllegalArgumentException ex) {
                kind = e.kind;
            }
            sl.add("&f" + GuiItems.stamp(e.at) + " &7" + kind);
            if (e.detail != null && !e.detail.isBlank()) for (String w : wrap(e.detail, 50)) sl.add("&8  " + w);
        }
        if (sl.isEmpty()) sl.add("&7Сигналов нет");
        gui.button(21, GuiItems.item(Items.BELL, "&6Почему открыто &8(" + c.signals.size() + ")", sl), () -> {});

        // Features.
        List<String> rows = new ArrayList<>();
        int n = 0;
        for (CaseFile.FeatureRow f : c.features) {
            if (n++ >= 14) break;
            Features.Spec spec = Features.spec(f.key);
            String title = spec == null ? f.key : spec.title();
            String val = spec == null ? String.format(Locale.ROOT, "%.3f", f.value) : spec.format(f.value);
            String ref = spec == null ? "" : spec.format(f.ref);
            rows.add((f.evidence > 0.05 ? "&c" : "&7") + title);
            rows.add(String.format(Locale.ROOT, "&8  %s (норма %s), z=%.1f, улика %.1f", val, ref, f.z, f.evidence));
        }
        if (rows.isEmpty()) rows.add("&7Нет данных");
        gui.button(23, GuiItems.item(Items.COMPARATOR, "&bВсе признаки", rows), () -> {});

        gui.button(25, GuiItems.item(Items.RECOVERY_COMPASS, "&aРазобрать заново",
                "&7Прочитать телеметрию сейчас", "&8клик"), () -> {
            Argus.reanalyze(c.id);
            admin.sendMessage(TextFmt.literal("&5[Аргус] &7Дело #" + c.id + " поставлено в очередь"), false);
            openCase(admin, c.id);
        });

        // The owner's call — what calibrates the model.
        gui.button(29, labelButton(c, CaseFile.Label.CHEATER, Items.RED_WOOL, "&cЧитер",
                "&7Модель учится: такие признаки — читерство.", "&7Его сессии уходят из норм сервера."),
                () -> label(admin, c, CaseFile.Label.CHEATER));
        gui.button(30, labelButton(c, CaseFile.Label.CLEAN, Items.LIME_WOOL, "&aНе читер",
                "&7Модель учится: это честная игра.", "&7Автобан не сработает ниже этой оценки."),
                () -> label(admin, c, CaseFile.Label.CLEAN));
        gui.button(31, labelButton(c, CaseFile.Label.UNSURE, Items.YELLOW_WOOL, "&eНеясно",
                "&7Без обучения: дело просто отмечено."),
                () -> label(admin, c, CaseFile.Label.UNSURE));

        boolean banned = c.action == CaseFile.Action.BANNED;
        gui.button(33, GuiItems.item(banned ? Items.GRAY_DYE : Items.ENDER_EYE,
                banned ? "&8Уже забанен (" + c.actionBy + ")" : "&5Бан Аргуса",
                "&7Временный бан по лестнице сроков,", "&7в истории — «бан Аргуса».", "&8с подтверждением"), () -> {
            if (banned) return;
            Confirm.open(admin, "&5Бан Аргуса: " + c.name, "Забанить " + c.name + " по делу #" + c.id + "?",
                    "Забанить", () -> {
                        Argus.ban(c.id, admin.getGameProfile().name());
                        admin.sendMessage(TextFmt.literal("&5[Аргус] &7" + c.name + " забанен по делу #" + c.id), false);
                        openCase(admin, c.id);
                    }, () -> openCase(admin, c.id));
        });
        gui.button(34, GuiItems.item(Items.BARRIER, c.open() ? "&7Закрыть дело" : "&8Дело закрыто",
                "&7Без бана и без решения", "&8клик"), () -> {
            if (c.open()) Argus.dismiss(c.id, admin.getGameProfile().name());
            openCase(admin, c.id);
        });

        UUID uuid = UUID.fromString(c.uuid);
        boolean online = admin.getEntityWorld().getServer().getPlayerManager().getPlayer(uuid) != null;
        if (online) {
            gui.button(40, GuiItems.item(Items.PLAYER_HEAD, "&aКарточка игрока", "&8онлайн"),
                    () -> ACPlayerMenu.openCard(admin, uuid));
        }
        gui.button(49, GuiItems.back("к делам"), () -> open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    /** Cases of one player, from the player card. */
    static void openPlayer(ServerPlayerEntity admin, UUID uuid, String name) {
        List<CaseFile> cases = new ArrayList<>(Argus.casesOf(uuid));
        cases.sort(Comparator.comparingLong((CaseFile c) -> c.openedAt).reversed());
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&5Аргус&8] &7" + name), 4);
        int slot = 0;
        for (CaseFile c : cases) {
            if (slot >= 27) break;
            gui.button(slot++, caseIcon(c), () -> openCase(admin, c.id));
        }
        gui.button(31, GuiItems.item(Items.SPYGLASS, "&5Начать расследование",
                "&7Аргус прочитает последний час", "&7телеметрии " + name + " сейчас."), () -> {
            String id = Argus.manual(uuid, name, admin.getGameProfile().name());
            admin.sendMessage(TextFmt.literal(id == null ? "&c[Аргус] Аргус не запущен"
                    : "&5[Аргус] &7Дело #" + id + " — разбор начат, итог придёт сюда"), false);
            if (id != null) openCase(admin, id);
        });
        gui.button(27, GuiItems.back("к карточке"), () -> ACPlayerMenu.openCard(admin, uuid));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    // ---------------------------------------------------------------- mode & model

    private static net.minecraft.item.ItemStack modeButton() {
        String m = Argus.mode();
        return GuiItems.item(m.equals("AUTO") ? Items.TNT : m.equals("SHADOW") ? Items.GRAY_DYE : Items.BELL,
                "&fРежим: &e" + Argus.modeLabel(m),
                "&7Тень — только дела и отчёты.",
                "&7Помощник — персонал получает вердикт",
                "&7  и кнопку бана.",
                "&7Авто — уверенный вердикт банит сам",
                "&7  (только при прогретых нормах",
                "&7  и двух независимых уликах).",
                "&8клик — сменить");
    }

    private static void chooseMode(ServerPlayerEntity admin) {
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&5Аргус&8] &7Режим"), 3);
        gui.button(11, GuiItems.item(Items.GRAY_DYE, "&7Тень", "&8только дела и отчёты"), () -> setMode(admin, "SHADOW"));
        gui.button(13, GuiItems.item(Items.BELL, "&eПомощник", "&8вердикт персоналу + кнопка"), () -> setMode(admin, "ASSIST"));
        gui.button(15, GuiItems.item(Items.TNT, "&cАвто", "&8уверенный вердикт банит сам", "&8с подтверждением"), () ->
                Confirm.open(admin, "&cАргус: авто-режим", "Разрешить Аргусу банить самому?", "Разрешить",
                        () -> setMode(admin, "AUTO"), () -> chooseMode(admin)));
        gui.button(22, GuiItems.back("к делам"), () -> open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static void setMode(ServerPlayerEntity admin, String mode) {
        Argus.setMode(mode);
        admin.sendMessage(TextFmt.literal("&5[Аргус] &7Режим: &f" + Argus.modeLabel(mode)), false);
        open(admin);
    }

    private static net.minecraft.item.ItemStack modelButton() {
        Calibration cal = Argus.calibration();
        List<String> lore = new ArrayList<>();
        lore.add("&7Меток: &f" + cal.cheaters + " &7читеров, &f" + cal.clean + " &7чистых");
        lore.add(String.format(Locale.ROOT, "&7Смещение: &f%.2f", cal.bias));
        if (cal.clean > 0) lore.add(String.format(Locale.ROOT, "&7Макс. оценка у чистых: &f%.1f%%", cal.maxCleanScore * 100));
        if (cal.cheaters > 0) lore.add("&7Читеров ниже порога подозрения: &f" + cal.missedCheaters);
        int shown = 0;
        for (var e : cal.weights.entrySet()) {
            if (Math.abs(e.getValue() - 1.0) < 0.05) continue;
            if (shown++ >= 8) break;
            Features.Spec spec = Features.spec(e.getKey());
            lore.add(String.format(Locale.ROOT, "&8  %s ×%.2f", spec == null ? e.getKey() : spec.title(), e.getValue()));
        }
        lore.add("&8Веса учатся по вашим решениям в делах.");
        return GuiItems.item(Items.KNOWLEDGE_BOOK, "&dМодель", lore);
    }

    private static void label(ServerPlayerEntity admin, CaseFile c, CaseFile.Label l) {
        Argus.label(c.id, l, admin.getGameProfile().name(), null);
        admin.sendMessage(TextFmt.literal("&5[Аргус] &7Дело #" + c.id + ": &f" + l.label + " &8— модель переобучается"), false);
        openCase(admin, c.id);
    }

    private static net.minecraft.item.ItemStack labelButton(CaseFile c, CaseFile.Label l, Item icon, String title,
                                                            String... lore) {
        boolean current = c.label == l;
        List<String> lines = new ArrayList<>(List.of(lore));
        if (current) lines.add("&a✔ выбрано (" + c.labelBy + ")");
        else lines.add("&8клик — отметить");
        return GuiItems.item(current ? Items.NETHER_STAR : icon, title, lines);
    }

    // ---------------------------------------------------------------- text helpers

    private static String statusLabel(CaseFile c) {
        return switch (c.status) {
            case COLLECTING -> "сбор данных, разбор через "
                    + GuiItems.ago(Math.max(0, c.dueAt - System.currentTimeMillis()));
            case QUEUED -> "в очереди на разбор";
            case ANALYZING -> "разбирается";
            case ANALYZED -> c.finalPass ? "разобрано (итог)" : "разобрано, итог до "
                    + GuiItems.stamp(c.deadlineAt);
            case CLOSED -> "закрыто";
        };
    }

    private static String iconColour(CaseFile c) {
        return switch (c.verdict) {
            case GUILTY -> "&c";
            case SUSPECT -> "&e";
            case CLEAN -> "&a";
            default -> "&7";
        };
    }

    private static String signalsLine(CaseFile c) {
        List<String> parts = new ArrayList<>();
        c.signalCounts.forEach((k, v) -> {
            String label;
            try {
                label = Signal.Kind.valueOf(k).label;
            } catch (IllegalArgumentException e) {
                label = k;
            }
            parts.add(label + (v > 1 ? " ×" + v : ""));
        });
        String s = String.join(", ", parts);
        return s.length() > 60 ? s.substring(0, 57) + "…" : s;
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String rest = text;
        while (rest.length() > width) {
            int cut = rest.lastIndexOf(' ', width);
            if (cut < width / 2) cut = width;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }
}
