package dev.bastionac.gui;

import dev.bastionac.argus.Argus;
import dev.bastionac.argus.CaseFile;
import dev.bastionac.core.AuthLinks;
import dev.bastionac.core.HistoryManager;
import dev.bastionac.core.Lang;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The staff dossier of one player, online or not (3.7.0 b21, owner 12.11): everything the
 * anticheat and the authentication know about the account on one screen.
 *
 * <ul>
 *   <li>alerts per check over the stored history, the first and the last;</li>
 *   <li>Argus cases with verdicts and the owner's labels;</li>
 *   <li>kicks, bans, cleared VL;</li>
 *   <li>recorded sessions (telemetry files) and when the player was last seen;</li>
 *   <li>from BastionAuth: registration, every address (full ones from Auth 1.15), address bans;</li>
 *   <li>the account links, and an address ban that works on an offline account.</li>
 * </ul>
 *
 * The server runs offline-mode, so a name that never joined still has a well-defined uuid; the
 * registered one from the authentication wins when it differs.
 */
public final class ACDossierMenu {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private ACDossierMenu() {}

    /** Opens the dossier by name; the player does not have to be online. */
    public static void openByName(ServerPlayerEntity admin, String name) {
        ServerPlayerEntity online = admin.getEntityWorld().getServer().getPlayerManager().getPlayer(name);
        if (online != null) {
            open(admin, online.getUuid(), online.getGameProfile().name());
            return;
        }
        String registered = AuthLinks.uuidOfName(name);
        UUID uuid;
        try {
            uuid = registered != null ? UUID.fromString(registered)
                    : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }
        open(admin, uuid, name);
    }

    public static void open(ServerPlayerEntity admin, UUID uuid, String name) {
        boolean online = admin.getEntityWorld().getServer().getPlayerManager().getPlayer(uuid) != null;
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&cBAC&8] &7Досье &f") + name), 5);

        // ---- recorded sessions (counted early so the header can show the last known client version)
        int[] sessions = new int[1];
        long[] lastSession = new long[1];
        int[] lastProto = new int[]{-1};
        countSessions(admin, uuid, sessions, lastSession, lastProto);

        // ---- header
        ServerPlayerEntity live = admin.getEntityWorld().getServer().getPlayerManager().getPlayer(uuid);
        dev.bastionac.core.PlayerData liveData = live != null ? dev.bastionac.BastionAC.data(live) : null;
        List<String> head = new ArrayList<>();
        head.add(online ? Lang.t("&aСейчас на сервере") : Lang.t("&7Не в сети"));
        if (live != null) {
            String ver = liveData != null && liveData.clientVersionLabel != null && !liveData.clientVersionLabel.isEmpty()
                    ? liveData.clientVersionLabel : dev.bastionac.core.ClientVersion.versionLabel(uuid);
            String brand = liveData != null && liveData.brand != null ? liveData.brand : "?";
            head.add(Lang.t("&7Клиент: &f") + ver + Lang.t(" &8· бренд: &f") + brand);
            head.add(Lang.t("&7Пинг: &f") + live.networkHandler.getLatency() + Lang.t(" мс"));
        } else if (lastProto[0] > 0) {
            head.add(Lang.t("&7Последний клиент: &f") + dev.bastionac.core.ClientVersion.versionLabel(lastProto[0]));
        }
        head.add("&8" + uuid);
        gui.button(4, GuiItems.head(name, "&e" + name, head),
                () -> {
                    if (online) ACPlayerMenu.openCard(admin, uuid);
                });

        // ---- alerts by check
        List<HistoryManager.Event> alerts = HistoryManager.alertsFor(uuid, 4000);
        Map<String, int[]> perCheck = new HashMap<>();
        long first = Long.MAX_VALUE, last = 0;
        for (HistoryManager.Event e : alerts) {
            String key = e.check == null ? "?" : e.check;
            perCheck.computeIfAbsent(key, k -> new int[1])[0]++;
            first = Math.min(first, e.time);
            last = Math.max(last, e.time);
        }
        List<String> alertLore = new ArrayList<>();
        if (alerts.isEmpty()) {
            alertLore.add(Lang.t("&aАлертов нет (за 30 дней)"));
        } else {
            alertLore.add(Lang.t("&7Всего за 30 дней: &f") + alerts.size());
            alertLore.add(Lang.t("&7Первый: &f") + at(first) + Lang.t(" &7последний: &f") + at(last));
            perCheck.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(10)
                    .forEach(e -> alertLore.add("&7 " + e.getKey() + ": &f" + e.getValue()[0]));
        }
        alertLore.add(Lang.t("&8➜ все алерты"));
        gui.button(19, GuiItems.item(Items.WRITABLE_BOOK, Lang.t("&e📜 Алерты по проверкам"), alertLore),
                () -> ACHistoryMenu.openPlayerAlerts(admin, uuid, name, 0));

        // ---- Argus
        List<CaseFile> cases = Argus.casesOf(uuid);
        Map<String, Integer> verdicts = new HashMap<>();
        for (CaseFile c : cases) verdicts.merge(c.verdict.label(), 1, Integer::sum);
        List<String> argusLore = new ArrayList<>();
        if (cases.isEmpty()) argusLore.add(Lang.t("&7Дел не было"));
        verdicts.forEach((v, n) -> argusLore.add("&7 " + v + ": &f" + n));
        int shown = 0;
        for (int i = cases.size() - 1; i >= 0 && shown < 5; i--, shown++) {
            CaseFile c = cases.get(i);
            argusLore.add("&7#" + c.id + " " + c.verdict.colour + c.verdict.label()
                    + (c.label != CaseFile.Label.NONE ? " &8(" + c.label.label() + ")" : ""));
        }
        argusLore.add(Lang.t("&8➜ дела Аргуса"));
        gui.button(20, GuiItems.item(Items.ENDER_EYE, Lang.t("&5👁 Аргус &8(") + cases.size() + ")", argusLore),
                () -> ArgusMenu.openPlayer(admin, uuid, name, () -> open(admin, uuid, name)));

        // ---- punishments
        List<HistoryManager.Event> pun = HistoryManager.punishmentsFor(uuid, 500);
        Map<String, Integer> kinds = new HashMap<>();
        for (HistoryManager.Event e : pun) kinds.merge(e.kind == null ? "?" : e.kind, 1, Integer::sum);
        List<String> punLore = new ArrayList<>();
        if (pun.isEmpty()) punLore.add(Lang.t("&aНаказаний нет"));
        kinds.forEach((k, n) -> punLore.add("&7 " + k + ": &f" + n));
        punLore.add(Lang.t("&8➜ история наказаний"));
        gui.button(21, GuiItems.item(Items.IRON_BARS, Lang.t("&c🔨 Наказания &8(") + pun.size() + ")", punLore),
                () -> ACHistoryMenu.openPlayerPunishments(admin, uuid, name, 0));

        // ---- recorded sessions
        gui.button(22, GuiItems.item(Items.CLOCK, Lang.t("&b⏱ Записи сессий"),
                Lang.t("&7Записей за 30 дней: &f") + sessions[0],
                Lang.t("&7Последняя: &f") + (lastSession[0] > 0 ? at(lastSession[0]) : "—"),
                Lang.t("&8Файлы: logs/bastionac/sessions")), () -> {});

        // ---- authentication: registration, addresses, address bans
        List<String> auth = AuthLinks.dossier(uuid.toString());
        List<String> authLore = new ArrayList<>(auth.isEmpty()
                ? List.of(Lang.t("&7Авторизация недоступна")) : auth);
        gui.button(23, GuiItems.item(Items.NAME_TAG, Lang.t("&6🔐 Авторизация и адреса"), authLore), () -> {
            for (String line : auth) admin.sendMessage(TextFmt.literal(line.replace('§', '&')), false);
        });

        // ---- links
        if (AuthLinks.available()) {
            List<AuthLinks.Link> mine = AuthLinks.links(uuid.toString(), AuthLinks.MAX_ROWS);
            List<String> lore = new ArrayList<>();
            if (mine.isEmpty()) lore.add(Lang.t("&7Связей с другими аккаунтами нет"));
            int limit = 0;
            for (AuthLinks.Link l : mine) {
                if (limit++ >= 8) {
                    lore.add(Lang.t("&8…и ещё ") + (mine.size() - 8));
                    break;
                }
                String other = l.uuidA().equals(uuid.toString()) ? l.nameB() : l.nameA();
                lore.add("&7" + other + " &8" + l.bestScore() + " " + l.signals() + " — "
                        + AuthLinks.verdictColour(l.verdict()) + AuthLinks.verdictLabel(l.verdict()));
            }
            lore.add(Lang.t("&8➜ связи списком, переход к досье"));
            gui.button(24, GuiItems.item(Items.BUNDLE, Lang.t("&6⛓ Связи &8(") + mine.size() + ")", lore),
                    () -> openLinks(admin, uuid, name, 0));
        }

        // ---- address ban (works offline)
        gui.button(31, GuiItems.item(Items.BARRIER, Lang.t("&c⛔ Бан по IP"),
                        Lang.t("&7Все адреса, с которых заходил аккаунт,"),
                        Lang.t("&7навсегда. Работает и без игрока онлайн."),
                        Lang.t("&8с подтверждением")),
                () -> Confirm.open(admin, Lang.t("&cБан по IP ") + name, Lang.t("Заблокировать все адреса ") + name + "?",
                        Lang.t("Заблокировать"),
                        () -> {
                            String report = AuthLinks.banAddresses(uuid.toString(), -1,
                                    Lang.t("решение администратора (античит)"), admin.getGameProfile().name());
                            admin.sendMessage(TextFmt.literal(report.replace('§', '&')), false);
                            open(admin, uuid, name);
                        },
                        () -> open(admin, uuid, name)));

        gui.button(40, GuiItems.back(Lang.t("в панель")), () -> ACPanel.open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private static final int LINKS_PER_PAGE = 28;

    /**
     * The account's links as heads (12.11, owner): left click opens the other player's dossier —
     * online or not — so a chain of accounts can be walked from one to the next; right click opens
     * the pair's card with the rulings.
     */
    public static void openLinks(ServerPlayerEntity admin, UUID uuid, String name, int page) {
        List<AuthLinks.Link> mine = AuthLinks.links(uuid.toString(), AuthLinks.MAX_ROWS);
        int pages = Math.max(1, (mine.size() + LINKS_PER_PAGE - 1) / LINKS_PER_PAGE);
        int shown = Math.max(0, Math.min(page, pages - 1));
        ChestGui gui = new ChestGui(TextFmt.literal(Lang.t("&8[&cBAC&8] &7Связи &f") + name
                + (pages > 1 ? " &8(" + (shown + 1) + "/" + pages + ")" : "")), 6);
        String me = uuid.toString();
        int slot = 10;
        for (int i = shown * LINKS_PER_PAGE; i < mine.size() && i < (shown + 1) * LINKS_PER_PAGE; i++) {
            AuthLinks.Link l = mine.get(i);
            String other = l.uuidA().equals(me) ? l.nameB() : l.nameA();
            String otherUuid = l.uuidA().equals(me) ? l.uuidB() : l.uuidA();
            List<String> lore = new ArrayList<>();
            if (l.manual()) lore.add(Lang.t("&7Заведена вручную"));
            else lore.add(Lang.t("&7Оценка: &f") + l.bestScore() + " &8(" + ACLinkMenu.grade(l.bestGrade()) + "&8)");
            if (!l.manual()) lore.add(ACLinkMenu.signalsLine(l.signals()));
            lore.add(Lang.t("&7Решение: ") + AuthLinks.verdictColour(l.verdict()) + AuthLinks.verdictLabel(l.verdict()));
            lore.add(Lang.t("&7Последний раз: &f") + GuiItems.stamp(l.lastSeen()));
            lore.add("");
            lore.add(Lang.t("&eЛКМ &7— досье ") + other);
            lore.add(Lang.t("&eПКМ &7— карточка связи и решение"));
            gui.set(slot, GuiItems.head(other, AuthLinks.verdictColour(l.verdict()) + other, lore), (p, click) -> {
                if (click.left()) {
                    UUID next;
                    try {
                        next = UUID.fromString(otherUuid);
                    } catch (IllegalArgumentException e) {
                        openByName(admin, other);
                        return;
                    }
                    open(admin, next, other);
                } else {
                    ACLinkMenu.card(admin, l, 0);
                }
            });
            slot++;
            if (slot % 9 == 8) slot += 2;
        }
        if (mine.isEmpty()) {
            gui.button(22, GuiItems.item(Items.LIME_DYE, Lang.t("&aСвязей с другими аккаунтами нет"),
                    Lang.t("&7Пары, которые модель больше не связывает,"),
                    Lang.t("&7уходят в архив при ежедневном пересчёте")), () -> {});
        }
        int prev = shown - 1, next = shown + 1;
        if (shown > 0) gui.button(45, GuiItems.item(Items.ARROW, Lang.t("&7← Назад (стр.)")),
                () -> openLinks(admin, uuid, name, prev));
        gui.button(49, GuiItems.back(Lang.t("в досье")), () -> open(admin, uuid, name));
        if (next < pages) gui.button(53, GuiItems.item(Items.ARROW, Lang.t("&7Вперёд (стр.) →")),
                () -> openLinks(admin, uuid, name, next));
        gui.button(51, GuiItems.item(Items.BOOKSHELF, Lang.t("&7Реестр всех связей")), () -> ACLinkMenu.open(admin, 0));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    /** Telemetry files of this player in the session folders (one folder per day). */
    private static void countSessions(ServerPlayerEntity admin, UUID uuid, int[] count, long[] last, int[] lastProto) {
        Path root = admin.getEntityWorld().getServer().getRunDirectory().resolve("logs").resolve("bastionac").resolve("sessions");
        String prefix = uuid + "__";
        Path newestFile = null;
        try (var days = Files.list(root)) {
            for (Path day : (Iterable<Path>) days::iterator) {
                if (!Files.isDirectory(day)) continue;
                try (var files = Files.list(day)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (!f.getFileName().toString().startsWith(prefix)) continue;
                        count[0]++;
                        long mtime = Files.getLastModifiedTime(f).toMillis();
                        if (mtime >= last[0]) {
                            last[0] = mtime;
                            newestFile = f;
                        }
                    }
                }
            }
            if (newestFile != null) {
                lastProto[0] = readHeaderProtocol(newestFile);
            }
        } catch (Exception ignored) {
            // No folder yet, or a file vanished under the janitor: the count is what it is.
        }
    }

    private static int readHeaderProtocol(Path btlGz) {
        try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(btlGz))) {
            byte[] buf = in.readNBytes(512);
            String head = new String(buf, StandardCharsets.UTF_8);
            int idx = head.indexOf("\"protocol\":");
            if (idx >= 0) {
                int i = idx + "\"protocol\":".length();
                while (i < head.length() && (head.charAt(i) == ' ' || head.charAt(i) == '\t')) i++;
                int start = i;
                if (i < head.length() && head.charAt(i) == '-') i++;
                while (i < head.length() && Character.isDigit(head.charAt(i))) i++;
                if (i > start) return Integer.parseInt(head.substring(start, i));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String at(long ms) {
        return AT.format(Instant.ofEpochMilli(ms));
    }
}
