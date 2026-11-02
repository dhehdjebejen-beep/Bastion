package dev.bastionac.gui;

import dev.bastionac.core.Lang;
import dev.bastionac.core.NetworkRegistry;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/**
 * Network registry view: recent ban networks (masked IP + subnet + age)
 * and same-subnet account links. Correlation-level facts, never proofs —
 * the lore of every entry says so, because the decision stays human.
 *
 * <p>Two link flavours: subnet sightings (two accounts seen on one /24 —
 * a weak, purely technical signal) and multi-signal links reported by
 * BastionAuth's composite analysis (device + IP + name + timing +
 * password; LIKELY/CONFIRMED grades only). They are visually distinct:
 * conflating a shared subnet with a confirmed link would overstate the
 * weaker signal.
 */
final class ACNetworkMenu {

    private static final int PER_PAGE = 28;

    /** Russian labels for the auth-bridge signal tokens. */
    private static String signalName(String token) {
        return dev.bastionac.core.AuthLinks.signalName(token);
    }

    /** Order-independent key, matching the one BastionAuth's ledger uses. */
    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * The whole register keyed by pair, read once per page rather than once
     * per tile: a page is 28 entries and each lookup crosses a mod boundary
     * by reflection.
     */
    private static java.util.Map<String, dev.bastionac.core.AuthLinks.Link> registerByPair() {
        java.util.Map<String, dev.bastionac.core.AuthLinks.Link> out = new java.util.HashMap<>();
        if (!dev.bastionac.core.AuthLinks.available()) return out;
        for (dev.bastionac.core.AuthLinks.Link row
                : dev.bastionac.core.AuthLinks.links(null, dev.bastionac.core.AuthLinks.MAX_ROWS)) {
            out.put(pairKey(row.uuidA(), row.uuidB()), row);
        }
        return out;
    }

    static void open(ServerPlayerEntity admin) {
        open(admin, 0);
    }

    static void open(ServerPlayerEntity admin, int page) {
        List<NetworkRegistry.BanNetwork> bans = NetworkRegistry.banNetworks();
        List<NetworkRegistry.LinkSighting> links = NetworkRegistry.links();

        int total = bans.size() + links.size();
        int pages = Math.max(1, (Math.max(total, 1) + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        ChestGui gui = new ChestGui(TextFmt.literal(
                Lang.t("&8[&cBAC&8] &7Сеть &8(&f") + bans.size() + Lang.t(" банов, ") + links.size() + Lang.t(" связей&8)")), 6);

        // Ban networks first, then links, one stream across pages.
        int slot = 0;
        int start = page * PER_PAGE;
        int shown = 0;

        for (int i = start; i < bans.size() && shown < PER_PAGE; i++, shown++) {
            NetworkRegistry.BanNetwork b = bans.get(i);
            String ago = NetworkRegistry.agoPublic(System.currentTimeMillis() - b.at);
            String source = b.source == null ? "auto" : b.source;
            String sourceLabel = switch (source) {
                case "chain" -> Lang.t("&5EDR-цепочка");
                case "manual" -> Lang.t("&6вручную");
                default -> Lang.t("&7авто");
            };
            gui.button(slot++, GuiItems.item(Items.TNT,
                    "&c" + b.name,
                    "&7IP: &f" + b.maskedIp,
                    Lang.t("&7Подсеть: &f") + b.subnet,
                    Lang.t("&7Бан: &f") + ago + Lang.t(" назад &8(") + sourceLabel + "&8)",
                    "&8UUID: " + b.uuid,
                    Lang.t("&8клик — карточка игрока (если онлайн)")),
                    () -> ACPlayerMenu.openByName(admin, b.name));
        }
        java.util.Map<String, dev.bastionac.core.AuthLinks.Link> register = registerByPair();
        for (int i = Math.max(0, start - bans.size());
                i < links.size() && shown < PER_PAGE; i++, shown++) {
            NetworkRegistry.LinkSighting l = links.get(i);
            String ago = NetworkRegistry.agoPublic(System.currentTimeMillis() - l.at);
            boolean authLink = l.subnet != null && l.subnet.startsWith("auth:");
            dev.bastionac.core.AuthLinks.Link registered = null;
            java.util.List<String> lore;
            if (authLink) {
                lore = new java.util.ArrayList<>();
                lore.add(Lang.t("&7Мульти-сигнальная связь &8(BastionAuth)"));
                if (l.signals == null || l.signals.isBlank()) {
                    lore.add(Lang.t("&7Устройство + IP + ник + время + пароль"));
                } else {
                    String[] parts = l.signals.split("\\+");
                    StringBuilder line = new StringBuilder(Lang.t("&7Сигналы: &f"));
                    for (int p = 0; p < parts.length; p++) {
                        if (p > 0) line.append(" &8+&7 ");
                        line.append(signalName(parts[p]));
                    }
                    lore.add(line.toString());
                }
                if (l.score != null && !l.score.isBlank()) {
                    lore.add(Lang.t("&7Оценка: &f") + l.score + "/160 &8("
                            + ("CONFIRMED".equals(l.grade) ? Lang.t("&aподтверждено") : Lang.t("&eвероятно")) + "&8)");
                }
                lore.add(Lang.t("&7Зафиксировано: &f") + ago + Lang.t(" назад"));
                // The standing decision, if the register has one: without it
                // this tab said "these two are one person" and offered nothing
                // to do about it.
                registered = l.uuidB == null ? null : register.get(pairKey(l.uuidA, l.uuidB));
                if (registered != null) {
                    lore.add(Lang.t("&7Статус: ") + dev.bastionac.core.AuthLinks.verdictColour(registered.verdict())
                            + dev.bastionac.core.AuthLinks.verdictLabel(registered.verdict()));
                    lore.add(Lang.t("&8➜ клик — карточка связи и решение"));
                } else {
                    lore.add(Lang.t("&8Корреляция, не улика — решение за человеком"));
                }
            } else {
                lore = List.of(
                        Lang.t("&7Общая подсеть: &f") + l.subnet,
                        Lang.t("&7Замечено: &f") + ago + Lang.t(" назад"),
                        Lang.t("&8Сигнал, не доказательство —"),
                        Lang.t("&8решение остаётся за человеком"));
            }
            final dev.bastionac.core.AuthLinks.Link card = registered;
            gui.button(slot++, GuiItems.item(
                    authLink ? Items.BUNDLE : Items.COMPASS,
                    (authLink ? "&6" : "&d") + l.nameA + " &7↔ " + (authLink ? "&6" : "&d") + l.nameB, lore),
                    card == null ? () -> {} : () -> ACLinkMenu.card(admin, card, 0));
        }

        if (bans.isEmpty() && links.isEmpty()) {
            gui.button(22, GuiItems.item(Items.LIME_DYE, Lang.t("&aСеть чиста"),
                    Lang.t("&7Ни банов с сетевым контекстом,"),
                    Lang.t("&7ни связей аккаунтов не зафиксировано")), () -> {});
        }

        int prev = page - 1, next = page + 1;
        if (page > 0) gui.button(45, GuiItems.item(Items.ARROW, Lang.t("&7← Назад (стр.)")), () -> open(admin, prev));
        gui.button(49, GuiItems.back(Lang.t("в панель")), () -> ACPanel.open(admin));
        if (next < pages) gui.button(53, GuiItems.item(Items.ARROW, Lang.t("&7Вперёд (стр.) →")), () -> open(admin, next));

        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private ACNetworkMenu() {}
}
