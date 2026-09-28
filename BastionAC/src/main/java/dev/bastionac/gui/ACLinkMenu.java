package dev.bastionac.gui;

import dev.bastionac.core.AuthLinks;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.AnvilGui;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The link register: every account pair the correlation model has ever built,
 * and the standing decision staff have made about it.
 *
 * <p>Why a register and not a stream of alerts. The model is deliberately
 * suspicious — a shared device, a shared subnet and a similar nick are exactly
 * as true of two brothers on one family PC as of a ban-evader, and it is right
 * to fire on both. What it cannot do is remember that a human already looked.
 * Without that memory every gate in the economy re-asked the same question
 * about the same pair forever: the owner could not pay their own second account
 * without filing a 115-ФЗ form, could not duel it, could not buy its auction
 * lot. The register is the missing half — the model finds pairs, a person rules
 * on them once, and the ruling sticks.
 *
 * <p>Two screens: a paginated list (unreviewed first, because those are the
 * ones that still owe a decision) and one pair's card with the four verdicts
 * on it. The verdicts live in BastionAuth's ledger; this is only the view.
 */
final class ACLinkMenu {

    private static final int PER_PAGE = 28;

    // ------------------------------------------------------------------ list

    static void open(ServerPlayerEntity admin, int page) {
        if (!AuthLinks.available()) {
            ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Реестр связей"), 3);
            gui.button(13, GuiItems.item(Items.BARRIER, "&cРеестр недоступен",
                    "&7BastionAuth не установлен или старой версии.",
                    "&7Связи аккаунтов заводит и хранит он;",
                    "&7здесь только их просмотр."), () -> {});
            gui.button(22, GuiItems.back("в панель"), () -> ACPanel.open(admin));
            gui.fill(GuiItems.filler());
            gui.open(admin);
            return;
        }

        List<AuthLinks.Link> links = AuthLinks.links(null, AuthLinks.MAX_ROWS);
        int pages = Math.max(1, (links.size() + PER_PAGE - 1) / PER_PAGE);
        int shownPage = Math.max(0, Math.min(page, pages - 1));

        int open = 0;
        for (AuthLinks.Link l : links) if (l.unreviewed()) open++;

        ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Реестр связей &8("
                + links.size() + ", без решения " + open + ")"), 6);

        int slot = 0;
        int start = shownPage * PER_PAGE;
        for (int i = start; i < links.size() && slot < PER_PAGE; i++, slot++) {
            AuthLinks.Link l = links.get(i);
            gui.button(slot, tile(l), () -> card(admin, l, shownPage));
        }

        if (links.isEmpty()) {
            gui.button(22, GuiItems.item(Items.LIME_DYE, "&aСвязей не зафиксировано",
                    "&7Ни одной пары аккаунтов,",
                    "&7которые выглядели бы одним человеком."), () -> {});
        }

        gui.button(48, GuiItems.item(Items.WRITABLE_BOOK, "&fЗавести связь вручную",
                "&7Если два аккаунта заведомо связаны,",
                "&7но модель их ещё не нашла — или наоборот,",
                "&7их надо заранее пропустить.",
                "&8➜ клик — ввести два ника"), () -> askPair(admin, shownPage));

        int prev = shownPage - 1, next = shownPage + 1;
        if (shownPage > 0) gui.button(45, GuiItems.item(Items.ARROW, "&7← Назад (стр.)"), () -> open(admin, prev));
        gui.button(49, GuiItems.back("в панель"), () -> ACPanel.open(admin));
        if (next < pages) gui.button(53, GuiItems.item(Items.ARROW, "&7Вперёд (стр.) →"), () -> open(admin, next));

        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    /** One row of the list. The icon carries the verdict so the page reads at a glance. */
    private static net.minecraft.item.ItemStack tile(AuthLinks.Link l) {
        String colour = AuthLinks.verdictColour(l.verdict());
        List<String> lore = new ArrayList<>();
        lore.add("&7Статус: " + colour + AuthLinks.verdictLabel(l.verdict()));
        if (l.manual()) {
            lore.add("&7Заведена вручную, без оценки модели");
        } else {
            lore.add("&7Оценка: &f" + l.bestScore() + " &8(" + grade(l.bestGrade()) + "&8)");
            lore.add(signalsLine(l.signals()));
        }
        lore.add("&7Замечено: &f" + l.times() + " раз&7, последний "
                + GuiItems.ago(System.currentTimeMillis() - l.lastSeen()) + " назад");
        if (l.verdictBy() != null && !l.verdictBy().isBlank()) {
            lore.add("&8решение: " + l.verdictBy() + ", " + GuiItems.stamp(l.verdictAt()));
        }
        lore.add("&8➜ клик — открыть карточку связи");
        return GuiItems.item(icon(l.verdict()),
                colour + l.nameA() + " &7↔ " + colour + l.nameB(), lore);
    }

    private static net.minecraft.item.Item icon(String verdict) {
        return switch (verdict == null ? "NEW" : verdict) {
            case "TRUSTED" -> Items.LIME_DYE;
            case "WATCH" -> Items.ORANGE_DYE;
            case "ALT" -> Items.RED_DYE;
            default -> Items.BUNDLE;
        };
    }

    private static String grade(String g) {
        return switch (g == null ? "" : g) {
            case "CONFIRMED" -> "&aподтверждено";
            case "LIKELY" -> "&eвероятно";
            case "POSSIBLE" -> "&6возможно";
            default -> "&7без оценки";
        };
    }

    private static String signalsLine(String signals) {
        if (signals == null || signals.isBlank()) return "&7Сигналы: &8не записаны";
        StringBuilder line = new StringBuilder("&7Сигналы: &f");
        String[] parts = signals.split("\\+");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) line.append(" &8+&f ");
            line.append(AuthLinks.signalName(parts[i]));
        }
        return line.toString();
    }

    // ------------------------------------------------------------------ one pair

    /** The pair's card: who, on what evidence, and the four rulings. */
    static void card(ServerPlayerEntity admin, AuthLinks.Link l, int backPage) {
        String colour = AuthLinks.verdictColour(l.verdict());
        ChestGui gui = new ChestGui(TextFmt.literal("&8[&cBAC&8] &7Связь &f"
                + l.nameA() + " &7↔ &f" + l.nameB()), 5);

        gui.button(11, GuiItems.head(l.nameA(), "&f" + l.nameA(),
                "&8" + l.uuidA(),
                "&8➜ клик — карточка игрока (если онлайн)"), () -> ACPlayerMenu.openByName(admin, l.nameA()));
        gui.button(15, GuiItems.head(l.nameB(), "&f" + l.nameB(),
                "&8" + l.uuidB(),
                "&8➜ клик — карточка игрока (если онлайн)"), () -> ACPlayerMenu.openByName(admin, l.nameB()));

        List<String> evidence = new ArrayList<>();
        if (l.manual()) {
            evidence.add("&7Связь заведена вручную.");
            evidence.add("&7Модель её самостоятельно не нашла.");
        } else {
            evidence.add("&7Оценка: &f" + l.bestScore() + " &8(" + grade(l.bestGrade()) + "&8)");
            evidence.add(signalsLine(l.signals()));
        }
        evidence.add("&7Впервые: &f" + GuiItems.stamp(l.firstSeen()));
        evidence.add("&7Последний раз: &f" + GuiItems.stamp(l.lastSeen()));
        evidence.add("&7Срабатываний: &f" + l.times());
        evidence.add("");
        evidence.add("&8Корреляция, а не улика:");
        evidence.add("&8одна семья за одним ПК даёт те же сигналы.");
        gui.button(13, GuiItems.item(Items.BUNDLE, colour + "Связь " + l.nameA() + " ↔ " + l.nameB(),
                evidence), () -> {});

        gui.button(22, GuiItems.item(Items.PAPER, "&fТекущий статус: " + colour
                        + AuthLinks.verdictLabel(l.verdict()),
                "&7" + AuthLinks.verdictEffect(l.verdict()),
                l.verdictBy() == null || l.verdictBy().isBlank()
                        ? "&8решения ещё не было"
                        : "&8поставил " + l.verdictBy() + ", " + GuiItems.stamp(l.verdictAt()),
                l.note() == null || l.note().isBlank() ? "&8без заметки" : "&8заметка: " + l.note()), () -> {});

        gui.button(29, verdictButton(l, "TRUSTED", Items.LIME_WOOL, "Доверенная",
                        "&7Аккаунты принадлежат одному человеку,",
                        "&7и это нормально: семья, свой альт, тесты.",
                        "&7Между ними снимаются ограничения —",
                        "&7переводы, дуэли, аукцион, обмен, ЗАГС;",
                        "&7просрочка одного не мешает займу другого.",
                        "&8Реферальный бонус по-прежнему",
                        "&8считает их одним лицом."),
                () -> apply(admin, l, "TRUSTED", backPage));

        gui.button(31, verdictButton(l, "WATCH", Items.ORANGE_WOOL, "Под наблюдением",
                        "&7Связь просмотрена, но ограничения остаются.",
                        "&7Пара перестаёт висеть в очереди без решения.",
                        "&7Поведение то же, что у непроверенной."),
                () -> apply(admin, l, "WATCH", backPage));

        gui.button(33, verdictButton(l, "ALT", Items.RED_WOOL, "Мультиаккаунт",
                        "&7Связь подтверждена как мультиаккаунт.",
                        "&7Ограничения действуют всегда, даже если",
                        "&7оценка со временем просядет ниже порога."),
                () -> apply(admin, l, "ALT", backPage));

        gui.button(35, verdictButton(l, "NEW", Items.YELLOW_WOOL, "Сбросить решение",
                        "&7Вернуть пару в очередь: решать снова",
                        "&7будет оценка модели."),
                () -> apply(admin, l, "NEW", backPage));

        gui.button(39, GuiItems.item(Items.WRITABLE_BOOK, "&fЗаметка к решению",
                "&7Зачем статус поставлен — увидят другие операторы.",
                l.note() == null || l.note().isBlank() ? "&8сейчас пусто" : "&8сейчас: " + l.note(),
                "&8➜ клик — ввести текст"), () -> askNote(admin, l, backPage));

        gui.button(41, GuiItems.item(Items.BEACON, "&aДоверять всем связям &f" + l.nameA(),
                "&7Ставит «Доверенная» на каждую пару,",
                "&7в которой участвует &f" + l.nameA() + "&7.",
                "&7Это короткий путь для «все эти аккаунты мои»:",
                "&7иначе решений нужно столько же, сколько пар.",
                "&8➜ клик — подтверждение"), () -> confirmBulk(admin, l, backPage));

        gui.button(40, GuiItems.back("в реестр"), () -> open(admin, backPage));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static net.minecraft.item.ItemStack verdictButton(AuthLinks.Link l, String verdict,
                                                              net.minecraft.item.Item item,
                                                              String title, String... why) {
        boolean current = verdict.equals(l.verdict());
        List<String> lore = new ArrayList<>(List.of(why));
        lore.add("");
        lore.add(current ? "&8уже стоит" : "&8➜ клик — поставить");
        return GuiItems.item(current ? Items.LIGHT_GRAY_WOOL : item,
                (current ? "&8" : AuthLinks.verdictColour(verdict)) + title, lore);
    }

    // ------------------------------------------------------------------ actions

    private static void apply(ServerPlayerEntity admin, AuthLinks.Link l, String verdict, int backPage) {
        boolean ok = AuthLinks.setVerdict(l.uuidA(), l.uuidB(), verdict,
                admin.getGameProfile().name(), l.note());
        admin.sendMessage(TextFmt.literal(ok
                ? "&a[BAC] Связь &f" + l.nameA() + " &a↔ &f" + l.nameB() + "&a: "
                        + AuthLinks.verdictColour(verdict) + AuthLinks.verdictLabel(verdict)
                : "&c[BAC] Не удалось записать статус связи."), false);
        reopen(admin, l, backPage);
    }

    private static void askNote(ServerPlayerEntity admin, AuthLinks.Link l, int backPage) {
        AnvilGui.open(admin, "&8Заметка к связи", l.note() == null ? "" : l.note(), text -> {
            String note = text == null ? "" : text.strip();
            if (note.length() > 120) note = note.substring(0, 120);
            // A note is part of the decision, so writing one re-files the
            // current verdict rather than inventing a separate edit path.
            AuthLinks.setVerdict(l.uuidA(), l.uuidB(), l.verdict(), admin.getGameProfile().name(), note);
            reopen(admin, l, backPage);
        });
    }

    private static void confirmBulk(ServerPlayerEntity admin, AuthLinks.Link l, int backPage) {
        Confirm.open(admin, "&8[&cBAC&8] &7Доверять всем связям",
                "Все связи " + l.nameA() + " → Доверенная?",
                "Да, это его аккаунты",
                () -> {
                    int n = AuthLinks.setVerdictForAll(l.uuidA(), "TRUSTED",
                            admin.getGameProfile().name(), l.note());
                    admin.sendMessage(TextFmt.literal("&a[BAC] Доверенными помечено связей: &f" + n), false);
                    open(admin, backPage);
                },
                () -> reopen(admin, l, backPage));
    }

    /** Re-reads the pair from the register so the card shows what was actually written. */
    private static void reopen(ServerPlayerEntity admin, AuthLinks.Link l, int backPage) {
        for (AuthLinks.Link fresh : AuthLinks.links(l.uuidA(), AuthLinks.MAX_ROWS)) {
            if (fresh.uuidA().equals(l.uuidB()) || fresh.uuidB().equals(l.uuidB())) {
                card(admin, fresh, backPage);
                return;
            }
        }
        open(admin, backPage);
    }

    // ------------------------------------------------------------------ manual pair

    /** Two nicks through the anvil, then straight to the new pair's card. */
    private static void askPair(ServerPlayerEntity admin, int backPage) {
        AnvilGui.open(admin, "&8Два ника через пробел", "", text -> {
            String[] parts = text == null ? new String[0] : text.strip().split("\\s+");
            if (parts.length < 2) {
                admin.sendMessage(TextFmt.literal(
                        "&c[BAC] Нужно два ника через пробел, например: &fSteve Alex"), false);
                open(admin, backPage);
                return;
            }
            // The register is keyed by uuid, so both sides must be accounts
            // that actually registered — a name nobody has used would make a
            // row no real player can ever match.
            String a = AuthLinks.uuidOfName(parts[0]);
            String b = AuthLinks.uuidOfName(parts[1]);
            if (a == null || b == null) {
                admin.sendMessage(TextFmt.literal("&c[BAC] Не найден аккаунт: &f"
                        + (a == null ? parts[0] : parts[1])), false);
                open(admin, backPage);
                return;
            }
            if (a.equals(b)) {
                admin.sendMessage(TextFmt.literal("&c[BAC] Это один и тот же аккаунт."), false);
                open(admin, backPage);
                return;
            }
            // WATCH is the neutral landing status: it records the pair without
            // pre-clearing or pre-condemning it.
            boolean ok = AuthLinks.setVerdict(a, b, "WATCH", admin.getGameProfile().name(),
                    "заведена вручную из панели");
            admin.sendMessage(TextFmt.literal(ok
                    ? "&7[BAC] Связь &f" + parts[0] + " &7↔ &f" + parts[1]
                            + "&7 заведена со статусом &6Под наблюдением&7 — откройте её и поставьте нужный."
                    : "&c[BAC] Не удалось завести связь."), false);
            open(admin, backPage);
        });
    }

    private ACLinkMenu() {}
}
