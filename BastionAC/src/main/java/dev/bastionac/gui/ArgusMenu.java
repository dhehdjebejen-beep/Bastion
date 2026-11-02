package dev.bastionac.gui;

import dev.bastionac.argus.Argus;
import dev.bastionac.argus.ArgusProgress;
import dev.bastionac.argus.CaseFile;
import dev.bastionac.argus.Calibration;
import dev.bastionac.argus.Signal;
import dev.bastionac.argus.features.Features;
import dev.bastionac.core.Lang;
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
        // Closed, undecided "suspicious"/"guilty" cases (e.g. replaced by a new
        // episode) stay listed: closing must not hide a verdict nobody looked at.
        // Also kept by default: cases closed in the last day (3.3.2) — "Argus
        // looked and cleared it" must be as visible as "Argus is looking".
        long dayAgo = System.currentTimeMillis() - 86_400_000L;
        if (!all) cases.removeIf(c -> !c.open() && c.label == CaseFile.Label.NONE && !c.awaitsDecision()
                && c.closedAt < dayAgo);
        // Open and suspicious first, then newest.
        cases.sort(Comparator.<CaseFile>comparingInt(c -> c.open() ? 0 : 1)
                .thenComparingInt(c -> -c.verdict.ordinal())
                .thenComparingLong(c -> -Math.max(c.lastSignalAt, c.lastAnalyzedAt)));
        int pages = Math.max(1, (cases.size() + PAGE - 1) / PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&5Аргус&8] &7Дела &8(&f") + cases.size() + "&8)"), 6);

        int slot = 0;
        for (int i = p * PAGE; i < Math.min(cases.size(), (p + 1) * PAGE); i++) {
            CaseFile c = cases.get(i);
            if (moving(c)) live(admin, gui, slot, c.id, false);
            gui.button(slot++, caseIcon(c), () -> openCase(admin, c.id));
        }
        if (cases.isEmpty()) {
            gui.button(22, GuiItems.item(Items.LIME_DYE, Lang.t("&aДел нет"),
                    Lang.t("&7Дело откроется само: цепочка EDR, алерт,"),
                    Lang.t("&7проба X-ray, откаты сервера — или"), Lang.t("&7«Начать расследование» в карточке игрока.")), () -> {});
        }

        List<String> st = new ArrayList<>(Argus.status());
        st.add("");
        st.add(Lang.t("&8Аргус не банит за одиночный флаг: он читает"));
        st.add(Lang.t("&8всю телеметрию игрока вокруг события: первый"));
        st.add(Lang.t("&8вердикт — через несколько минут, итог — сразу"));
        st.add(Lang.t("&8при уверенном «виновен» или уходе игрока,"));
        st.add(Lang.t("&8иначе к сроку."));
        gui.button(45, GuiItems.item(Items.ENDER_EYE, Lang.t("&5Аргус — расследования"), st), () -> {});
        gui.button(46, modeButton(), () -> chooseMode(admin));
        gui.button(47, modelButton(), () -> {});
        gui.button(48, GuiItems.item(all ? Items.BOOK : Items.WRITABLE_BOOK,
                all ? Lang.t("&fПоказаны все дела") : Lang.t("&fПоказаны открытые, размеченные и за сутки"),
                Lang.t("&8клик — переключить")), () -> open(admin, 0, !all));
        gui.button(49, GuiItems.back(Lang.t("в панель")), () -> ACPanel.open(admin));
        if (p > 0) gui.button(52, GuiItems.item(Items.ARROW, Lang.t("&7← стр. ") + p), () -> open(admin, p - 1, all));
        if (p + 1 < pages) gui.button(53, GuiItems.item(Items.ARROW, Lang.t("&7стр. ") + (p + 2) + " →"), () -> open(admin, p + 1, all));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static net.minecraft.item.ItemStack caseIcon(CaseFile c) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.t("&7Вердикт: ") + c.verdict.colour + c.verdict.label()
                + (c.analyses > 0 ? String.format(Locale.ROOT, " &8(%.0f%%)", c.score * 100) : ""));
        lore.add(Lang.t("&7Статус: &f") + statusLabel(c));
        String now = liveLine(c);
        if (now != null) lore.add(now);
        if (c.label != CaseFile.Label.NONE) lore.add(Lang.t("&7Решение: &f") + c.label.label() + " &8(" + c.labelBy + ")");
        else if (c.awaitsDecision()) lore.add(Lang.t("&eЖдёт решения персонала"));
        if (c.closeNote != null) lore.add("&8" + c.closeNote);
        if (c.previousCaseId != null) lore.add(Lang.t("&7Прошлое дело: &f#") + c.previousCaseId);
        lore.add(Lang.t("&7Открыто: &f") + GuiItems.ago(System.currentTimeMillis() - c.openedAt) + Lang.t(" назад"));
        lore.add(Lang.t("&7Сигналы: &f") + signalsLine(c));
        int n = 0;
        for (String f : c.findings) {
            if (n++ >= 3) break;
            for (String w : wrap("• " + f, 46)) lore.add("&8" + w);
        }
        lore.add(Lang.t("&8клик — открыть дело"));
        return GuiItems.head(c.name, iconColour(c) + c.name + " &8#" + c.id, lore);
    }

    // ---------------------------------------------------------------- one case

    public static void openCase(ServerPlayerEntity admin, String id) {
        CaseFile c = Argus.get(id);
        if (c == null) {
            admin.sendMessage(TextFmt.literal(Lang.t("&c[Аргус] Дело #") + id + Lang.t(" не найдено")), false);
            return;
        }
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&5Аргус&8] &7Дело &f#") + c.id + " &7— &f" + c.name), 6);

        List<String> head = new ArrayList<>();
        head.add(Lang.t("&7Вердикт: ") + c.verdict.colour + c.verdict.label());
        if (c.closeNote != null) head.add("&8" + c.closeNote);
        if (c.previousCaseId != null) head.add(Lang.t("&7Прошлое дело: &f#") + c.previousCaseId);
        if (c.windowNote != null) head.add(Lang.t("&7Читается: &f") + c.windowNote);
        if (c.analyses > 0) {
            head.add(String.format(Locale.ROOT, Lang.t("&7Оценка: &f%.1f%% &8(улик %.1f, сигналы %.1f)"), c.score * 100, c.evidence, c.signalEvidence));
            c.categories.forEach((k, v) -> head.add(String.format(Locale.ROOT, "&7  %s: &f%.0f%%", k, v * 100)));
        }
        head.add(Lang.t("&7Статус: &f") + statusLabel(c));
        head.add(Lang.t("&7Разборов: &f") + c.analyses + (c.analyses > 0
                ? Lang.t(" &8(последний ") + GuiItems.ago(System.currentTimeMillis() - c.lastAnalyzedAt) + Lang.t(" назад)") : ""));
        head.add(String.format(Locale.ROOT, Lang.t("&7Данных: &f%.1f мин &8(файлов %d%s)"), c.dataMinutes, c.sessionFiles.size(),
                c.worldSampled ? Lang.t(", мир прочитан") : ""));
        if (c.lastError != null) head.add(Lang.t("&cОшибка разбора: ") + c.lastError);
        if (c.reportFile != null) {
            head.add(Lang.t("&8Отчёт:"));
            for (String w : wrap(c.reportFile, 46)) head.add("&8" + w);
        }
        gui.button(4, GuiItems.head(c.name, iconColour(c) + c.name + " &8#" + c.id, head), () -> {});

        // How far Argus is with this case — redrawn twice a second while the card is open.
        gui.button(13, progressItem(c), () -> openCase(admin, c.id));
        live(admin, gui, 13, c.id, true);

        // Findings.
        List<String> fl = new ArrayList<>();
        if (c.findings.isEmpty()) fl.add(Lang.t("&7Разбора ещё не было"));
        for (String f : c.findings) for (String w : wrap("• " + f, 50)) fl.add("&f" + w);
        gui.button(19, GuiItems.item(Items.SPYGLASS, Lang.t("&eВыводы"), fl), () -> {});

        // Signals.
        List<String> sl = new ArrayList<>();
        int from = Math.max(0, c.signals.size() - 12);
        for (int i = from; i < c.signals.size(); i++) {
            CaseFile.SignalEntry e = c.signals.get(i);
            String kind;
            try {
                kind = Signal.Kind.valueOf(e.kind).label();
            } catch (IllegalArgumentException ex) {
                kind = e.kind;
            }
            sl.add("&f" + GuiItems.stamp(e.at) + " &7" + kind);
            if (e.detail != null && !e.detail.isBlank()) for (String w : wrap(e.detail, 50)) sl.add("&8  " + w);
        }
        if (sl.isEmpty()) sl.add(Lang.t("&7Сигналов нет"));
        gui.button(21, GuiItems.item(Items.BELL, Lang.t("&6Почему открыто &8(") + c.signals.size() + ")", sl), () -> {});

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
            rows.add(String.format(Locale.ROOT, Lang.t("&8  %s (норма %s), z=%.1f, улика %.1f"), val, ref, f.z, f.evidence));
            String when = f.when();
            if (when != null) rows.add(Lang.t("&8  когда: &7") + when);
        }
        if (rows.isEmpty()) rows.add(Lang.t("&7Нет данных"));
        gui.button(23, GuiItems.item(Items.COMPARATOR, Lang.t("&bВсе признаки"), rows), () -> {});

        gui.button(25, GuiItems.item(Items.RECOVERY_COMPASS, Lang.t("&aРазобрать заново"),
                Lang.t("&7Прочитать телеметрию сейчас"),
                c.open() ? Lang.t("&8клик") : Lang.t("&8клик — дело не откроется, только цифры")), () -> {
            boolean busy = c.inFlight;
            Argus.reanalyze(c.id, admin.getGameProfile().name());
            admin.sendMessage(TextFmt.literal(Lang.t("&5[Аргус] &7Дело #") + c.id + (busy
                    ? Lang.t(" уже разбирается — перечитаю сразу после, итог придёт сюда")
                    : c.open() ? Lang.t(" поставлено в очередь, итог придёт сюда")
                    : Lang.t(" закрыто — обновлю цифры, решение останется твоим"))), false);
            openCase(admin, c.id);
        });

        // The owner's call — what calibrates the model.
        gui.button(29, labelButton(c, CaseFile.Label.CHEATER, Items.RED_WOOL, Lang.t("&cЧитер"),
                Lang.t("&7Модель учится: такие признаки — читерство."), Lang.t("&7Его сессии уходят из норм сервера.")),
                () -> label(admin, c, CaseFile.Label.CHEATER));
        gui.button(30, labelButton(c, CaseFile.Label.CLEAN, Items.LIME_WOOL, Lang.t("&aНе читер"),
                Lang.t("&7Модель учится: это честная игра."), Lang.t("&7Автобан не сработает ниже этой оценки.")),
                () -> label(admin, c, CaseFile.Label.CLEAN));
        gui.button(31, labelButton(c, CaseFile.Label.UNSURE, Items.YELLOW_WOOL, Lang.t("&eНеясно"),
                Lang.t("&7Без обучения: дело просто отмечено.")),
                () -> label(admin, c, CaseFile.Label.UNSURE));

        boolean banned = c.action == CaseFile.Action.BANNED;
        gui.button(33, GuiItems.item(banned ? Items.GRAY_DYE : Items.ENDER_EYE,
                banned ? Lang.t("&8Уже забанен (") + c.actionBy + ")" : Lang.t("&5Бан Аргуса"),
                Lang.t("&7Временный бан по лестнице сроков,"), Lang.t("&7в истории — «бан Аргуса»."), Lang.t("&8с подтверждением")), () -> {
            if (banned) return;
            Confirm.open(admin, Lang.t("&5Бан Аргуса: ") + c.name, Lang.t("Забанить ") + c.name + Lang.t(" по делу #") + c.id + "?",
                    Lang.t("Забанить"), () -> {
                        Argus.ban(c.id, admin.getGameProfile().name());
                        admin.sendMessage(TextFmt.literal(Lang.t("&5[Аргус] &7") + c.name + Lang.t(" забанен по делу #") + c.id), false);
                        openCase(admin, c.id);
                    }, () -> openCase(admin, c.id));
        });
        gui.button(34, GuiItems.item(Items.BARRIER, c.open() ? Lang.t("&7Закрыть дело") : Lang.t("&8Дело закрыто"),
                Lang.t("&7Без бана и без решения"), Lang.t("&8клик")), () -> {
            if (c.open()) Argus.dismiss(c.id, admin.getGameProfile().name());
            openCase(admin, c.id);
        });

        UUID uuid = UUID.fromString(c.uuid);
        boolean online = admin.getEntityWorld().getServer().getPlayerManager().getPlayer(uuid) != null;
        if (online) {
            gui.button(40, GuiItems.item(Items.PLAYER_HEAD, Lang.t("&aКарточка игрока"), Lang.t("&8онлайн")),
                    () -> ACPlayerMenu.openCard(admin, uuid));
        }
        gui.button(49, GuiItems.back(Lang.t("к делам")), () -> open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    /** Cases of one player, from the player card. */
    static void openPlayer(ServerPlayerEntity admin, UUID uuid, String name) {
        openPlayer(admin, uuid, name, () -> ACPlayerMenu.openCard(admin, uuid));
    }

    /** As above, with where "back" leads (the alert history for a player who is offline). */
    static void openPlayer(ServerPlayerEntity admin, UUID uuid, String name, Runnable back) {
        List<CaseFile> cases = new ArrayList<>(Argus.casesOf(uuid));
        cases.sort(Comparator.comparingLong((CaseFile c) -> c.openedAt).reversed());
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&5Аргус&8] &7") + name), 4);
        int slot = 0;
        for (CaseFile c : cases) {
            if (slot >= 27) break;
            if (moving(c)) live(admin, gui, slot, c.id, false);
            gui.button(slot++, caseIcon(c), () -> openCase(admin, c.id));
        }
        gui.button(31, GuiItems.item(Items.SPYGLASS, Lang.t("&5Начать расследование"),
                Lang.t("&7В сети — последний час телеметрии,"), Lang.t("&7не в сети — последняя сессия ") + name + "."),
                () -> investigate(admin, uuid, name, 0));
        gui.button(27, GuiItems.back(Lang.t("назад")), back);
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    // ---------------------------------------------------------------- mode & model

    private static net.minecraft.item.ItemStack modeButton() {
        String m = Argus.mode();
        return GuiItems.item(m.equals("AUTO") ? Items.TNT : m.equals("SHADOW") ? Items.GRAY_DYE : Items.BELL,
                Lang.t("&fРежим: &e") + Argus.modeLabel(m),
                Lang.t("&7Тень — только дела и отчёты."),
                Lang.t("&7Помощник — персонал получает вердикт"),
                Lang.t("&7  и кнопку бана."),
                Lang.t("&7Авто — уверенный вердикт банит сам"),
                Lang.t("&7  (только при прогретых нормах,"),
                Lang.t("&7  двух независимых уликах и не меньше"),
                "&7  " + dev.bastionac.BastionAC.config().argus.autoBanMinMinutes + Lang.t(" мин данных)."),
                Lang.t("&8клик — сменить"));
    }

    private static void chooseMode(ServerPlayerEntity admin) {
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&5Аргус&8] &7Режим")), 3);
        gui.button(11, GuiItems.item(Items.GRAY_DYE, Lang.t("&7Тень"), Lang.t("&8только дела и отчёты")), () -> setMode(admin, "SHADOW"));
        gui.button(13, GuiItems.item(Items.BELL, Lang.t("&eПомощник"), Lang.t("&8вердикт персоналу + кнопка")), () -> setMode(admin, "ASSIST"));
        gui.button(15, GuiItems.item(Items.TNT, Lang.t("&cАвто"), Lang.t("&8уверенный вердикт банит сам"), Lang.t("&8с подтверждением")), () ->
                Confirm.open(admin, Lang.t("&cАргус: авто-режим"), Lang.t("Разрешить Аргусу банить самому?"), Lang.t("Разрешить"),
                        () -> setMode(admin, "AUTO"), () -> chooseMode(admin)));
        gui.button(22, GuiItems.back(Lang.t("к делам")), () -> open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static void setMode(ServerPlayerEntity admin, String mode) {
        Argus.setMode(mode);
        admin.sendMessage(TextFmt.literal(Lang.t("&5[Аргус] &7Режим: &f") + Argus.modeLabel(mode)), false);
        open(admin);
    }

    private static net.minecraft.item.ItemStack modelButton() {
        Calibration cal = Argus.calibration();
        List<String> lore = new ArrayList<>();
        lore.add(Lang.t("&7Меток: &f") + cal.cheaters + Lang.t(" &7читеров, &f") + cal.clean + Lang.t(" &7чистых"));
        lore.add(String.format(Locale.ROOT, Lang.t("&7Смещение: &f%.2f"), cal.bias));
        if (cal.clean > 0) lore.add(String.format(Locale.ROOT, Lang.t("&7Макс. оценка у чистых: &f%.1f%%"), cal.maxCleanScore * 100));
        if (cal.cheaters > 0) lore.add(Lang.t("&7Читеров ниже порога подозрения: &f") + cal.missedCheaters);
        int shown = 0;
        for (var e : cal.weights.entrySet()) {
            if (Math.abs(e.getValue() - 1.0) < 0.05) continue;
            if (shown++ >= 8) break;
            Features.Spec spec = Features.spec(e.getKey());
            lore.add(String.format(Locale.ROOT, "&8  %s ×%.2f", spec == null ? e.getKey() : spec.title(), e.getValue()));
        }
        lore.add(Lang.t("&8Веса учатся по вашим решениям в делах."));
        return GuiItems.item(Items.KNOWLEDGE_BOOK, Lang.t("&dМодель"), lore);
    }

    private static void label(ServerPlayerEntity admin, CaseFile c, CaseFile.Label l) {
        Argus.label(c.id, l, admin.getGameProfile().name(), null);
        admin.sendMessage(TextFmt.literal(Lang.t("&5[Аргус] &7Дело #") + c.id + ": &f" + l.label() + Lang.t(" &8— модель переобучается")), false);
        openCase(admin, c.id);
    }

    private static net.minecraft.item.ItemStack labelButton(CaseFile c, CaseFile.Label l, Item icon, String title,
                                                            String... lore) {
        boolean current = c.label == l;
        List<String> lines = new ArrayList<>(List.of(lore));
        if (current) lines.add(Lang.t("&a✔ выбрано (") + c.labelBy + ")");
        else lines.add(Lang.t("&8клик — отметить"));
        return GuiItems.item(current ? Items.NETHER_STAR : icon, title, lines);
    }

    /**
     * Starts an investigation — online or not, now or at a past moment — tells
     * the admin what is being read, and opens the case (3.3.2).
     */
    static void investigate(ServerPlayerEntity admin, UUID uuid, String name, long atMs) {
        Argus.Investigation inv = Argus.investigate(uuid, name, admin.getGameProfile().name(), atMs);
        if (inv.caseId() == null) {
            admin.sendMessage(TextFmt.literal(Lang.t("&c[Аргус] ") + name + ": " + inv.error()), false);
            return;
        }
        admin.sendMessage(TextFmt.literal(Lang.t("&5[Аргус] &7Дело #") + inv.caseId() + " (" + name + Lang.t(") — разбор начат &8(")
                + inv.note() + Lang.t(")&7, итог придёт сюда")), false);
        openCase(admin, inv.caseId());
    }

    /** One line for an alert: what Argus made of the player around that moment. */
    static String caseLineFor(UUID uuid, long atMs) {
        CaseFile best = null;
        for (CaseFile c : Argus.casesOf(uuid)) {
            // A case the alert itself opened or fed: opened at most 6 h before it, not after it.
            if (c.openedAt > atMs + 120_000L || c.openedAt < atMs - 6 * 3_600_000L) continue;
            if (best == null || c.openedAt > best.openedAt) best = c;
        }
        if (best == null) return Lang.t("&5Аргус: &7дела по этому моменту нет");
        return Lang.t("&5Аргус: &7дело #") + best.id + " — " + best.verdict.colour + best.verdict.label()
                + (best.analyses > 0 ? String.format(Locale.ROOT, " &8(%.0f%%)", best.score * 100) : "")
                + (best.open() ? "" : Lang.t(" &8· закрыто"));
    }

    // ---------------------------------------------------------------- live progress (3.2)

    /** A slot of an open menu that shows something changing by itself. */
    private record Live(ServerPlayerEntity admin, ChestGui gui, int slot, String caseId, boolean card) {}

    private static final List<Live> LIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Registers a live slot; the admin's slots of any other (older) panel are dropped. */
    private static void live(ServerPlayerEntity admin, ChestGui gui, int slot, String caseId, boolean card) {
        LIVE.removeIf(l -> l.admin() == admin && l.gui() != gui);
        LIVE.add(new Live(admin, gui, slot, caseId, card));
    }

    /**
     * Server thread, twice a second (from {@code Argus.tick}): redraws every
     * live slot that is still on someone's screen and forgets the rest. Only
     * slots whose item changed are sent.
     */
    public static void tickLive() {
        for (Live l : LIVE) {
            CaseFile c = Argus.get(l.caseId());
            if (c == null || l.admin().isRemoved() || !l.gui().isShownTo(l.admin())) {
                LIVE.remove(l);
                continue;
            }
            net.minecraft.item.ItemStack item = l.card() ? progressItem(c) : caseIcon(c);
            if (!l.gui().refresh(l.admin(), l.slot(), item)) LIVE.remove(l);
        }
    }

    /** A case whose list icon changes on its own: collecting, queued, being read or waiting for its final pass. */
    private static boolean moving(CaseFile c) {
        return c.inFlight || (c.open() && !(c.status == CaseFile.Status.ANALYZED && c.finalPass));
    }

    /** One line for the list: what Argus is doing with the case right now, or null. */
    private static String liveLine(CaseFile c) {
        ArgusProgress.View p = Argus.progress(c.id);
        if (!c.inFlight || p == null || p.done()) return null;
        long now = System.currentTimeMillis();
        return "&d▶ " + p.stage().label() + (p.detail() == null ? "" : " &8(" + p.detail() + ")")
                + " &7" + dur(now - p.stageAt());
    }

    /**
     * The live "Ход расследования" item: what Argus is doing with the case
     * now, how long each stage takes, and what comes next.
     */
    static net.minecraft.item.ItemStack progressItem(CaseFile c) {
        long now = System.currentTimeMillis();
        ArgusProgress.View p = Argus.progress(c.id);
        List<String> lore = new ArrayList<>();
        Item icon;
        String title;
        if (c.inFlight && p != null && !p.done()) {
            icon = Items.CLOCK;
            switch (p.stage()) {
                case QUEUED -> {
                    title = Lang.t("&eВ очереди на разбор");
                    lore.add(Lang.t("&7Задач перед делом: &f") + p.ahead());
                    String cur = Argus.currentJob();
                    if (cur != null) lore.add(Lang.t("&8сейчас: ") + cur);
                }
                case WAITING_SERVER -> {
                    title = Lang.t("&6Ждёт разгрузки сервера");
                    if (p.detail() != null) lore.add("&7" + p.detail());
                    lore.add(Lang.t("&8Аргус уступает игре, пока сервер"));
                    lore.add(Lang.t("&8тормозит (дело ждёт не больше 20 с)"));
                }
                default -> {
                    title = Lang.t("&dРазбор: ") + p.stage().label();
                    if (p.detail() != null) lore.add(Lang.t("&7Сейчас: &f") + p.detail());
                }
            }
            lore.add(bar(p.fraction()) + String.format(Locale.ROOT, " &f%.0f%%", p.fraction() * 100));
            lore.add(Lang.t("&7Этап идёт: &f") + dur(now - p.stageAt()));
            lore.add(Lang.t("&7С постановки в очередь: &f") + dur(now - p.queuedAt()));
            if (Argus.lastDurationMs() > 0) lore.add(Lang.t("&8обычно разбор длится ~") + dur(Argus.lastDurationMs()));
            lore.add("");
            stages(lore, p, now);
        } else {
            switch (c.status) {
                case COLLECTING -> {
                    icon = Items.HOPPER;
                    title = Lang.t("&eСбор данных");
                    long start = c.lastAnalyzedAt > 0 ? c.lastAnalyzedAt : c.openedAt;
                    long total = Math.max(1, c.dueAt - start);
                    long left = c.dueAt - now;
                    lore.add(Lang.t("&7Аргус копит телеметрию перед разбором"));
                    lore.add(bar((double) (now - start) / total));
                    lore.add(left > 0 ? Lang.t("&7Разбор через: &f") + clock(left) : Lang.t("&7Разбор — сейчас"));
                }
                case QUEUED, ANALYZING -> {
                    icon = Items.HOPPER;
                    title = Lang.t("&eОжидает разбора");
                    lore.add(Lang.t("&8задача будет поставлена заново"));
                }
                case ANALYZED -> {
                    if (!c.finalPass) {
                        icon = Items.COMPASS;
                        title = Lang.t("&bРазобрано, итог к сроку");
                        long total = Math.max(1, c.deadlineAt - c.openedAt);
                        lore.add(bar((double) (now - c.openedAt) / total));
                        lore.add(Lang.t("&7Итоговый разбор: &f") + GuiItems.stamp(c.deadlineAt)
                                + Lang.t(" &8(через ") + clock(Math.max(0, c.deadlineAt - now)) + ")");
                        if (c.pendingSignals) {
                            long again = c.lastAnalyzedAt + dev.bastionac.BastionAC.config().argus.reanalyzeMinutes * 60_000L - now;
                            lore.add(Lang.t("&7Новые сигналы: повтор ") + (again > 0 ? Lang.t("через &f") + clock(again) : Lang.t("&fсейчас")));
                        }
                    } else {
                        icon = Items.LIME_DYE;
                        title = Lang.t("&aИтог вынесен");
                        lore.add(Lang.t("&7Вердикт: ") + c.verdict.colour + c.verdict.label());
                    }
                }
                default -> {
                    icon = Items.GRAY_DYE;
                    title = Lang.t("&8Дело закрыто");
                    if (c.closeNote != null) lore.add("&8" + c.closeNote);
                }
            }
            if (p != null && p.done()) {
                lore.add("");
                lore.add(Lang.t("&7Последний разбор: &f") + dur(p.runMs(now)) + " &8(" + GuiItems.ago(now - p.finishedAt())
                        + Lang.t(" назад)"));
                stages(lore, p, now);
            }
        }
        lore.add(Lang.t("&8обновляется вживую · клик — обновить карточку"));
        return GuiItems.item(icon, title, lore);
    }

    /** The stages of a run: done with their time, the current one, those still to come. */
    private static void stages(List<String> lore, ArgusProgress.View p, long now) {
        Long q = p.took().get(ArgusProgress.Stage.QUEUED);
        Long w = p.took().get(ArgusProgress.Stage.WAITING_SERVER);
        if (q != null) lore.add(Lang.t("&a✔ &7очередь &8") + dur(q));
        if (w != null) lore.add(Lang.t("&a✔ &7ожидание сервера &8") + dur(w));
        for (ArgusProgress.Stage s : ArgusProgress.Stage.values()) {
            if (!s.working()) continue;
            Long took = p.took().get(s);
            if (s == p.stage()) {
                lore.add("&d▶ &f" + s.label() + " &8" + dur(now - p.stageAt()));
            } else if (took != null) {
                lore.add("&a✔ &7" + s.label() + " &8" + dur(took));
            } else if (p.done() || s.ordinal() < p.stage().ordinal()) {
                lore.add("&8– " + s.label() + Lang.t(" (не понадобилось)"));
            } else {
                lore.add("&8○ " + s.label());
            }
        }
    }

    /** A twenty-notch bar. */
    private static String bar(double fraction) {
        int k = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 20);
        return "&a" + "|".repeat(k) + "&8" + "|".repeat(20 - k);
    }

    /** "45 мс", "1.2 с", "2:05", "1 ч 05 мин". */
    static String dur(long ms) {
        ms = Math.max(0, ms);
        if (ms < 1000) return ms + Lang.t(" мс");
        if (ms < 60_000) return String.format(Locale.ROOT, Lang.t("%.1f с"), ms / 1000.0);
        return clock(ms);
    }

    /** "2:05", or "1 ч 05 мин" from an hour up. */
    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s >= 3600) return String.format(Locale.ROOT, Lang.t("%d ч %02d мин"), s / 3600, (s % 3600) / 60);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- text helpers

    private static String statusLabel(CaseFile c) {
        return switch (c.status) {
            case COLLECTING -> Lang.t("сбор данных, разбор через ")
                    + clock(Math.max(0, c.dueAt - System.currentTimeMillis()));
            case QUEUED -> Lang.t("в очереди на разбор");
            case ANALYZING -> Lang.t("разбирается");
            case ANALYZED -> (c.finalPass ? Lang.t("разобрано (итог)") : Lang.t("разобрано, итог до ")
                    + GuiItems.stamp(c.deadlineAt)) + (c.rerunRequested ? Lang.t(", повтор запрошен") : "");
            case CLOSED -> c.inFlight ? Lang.t("закрыто (дочитываю цифры)") : Lang.t("закрыто");
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
                label = Signal.Kind.valueOf(k).label();
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
