package dev.bastionac.gui;

import dev.bastionac.core.AttackChainDetector;
import dev.bastionac.core.HistoryManager;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/**
 * EDR view: the attack chains currently open, strongest first. One item per
 * chain — the lore lists the last events with their timestamps and details,
 * the score and both thresholds; a chain that reached BURNED is flagged in
 * red. Burned chains themselves reset (a fresh episode starts clean), so
 * the second row group shows the recently burned incidents from the
 * punishment history: the pattern that already paid out.
 */
final class ACChainMenu {

    /** Human-readable stage names for the lore. */
    private static String stageName(AttackChainDetector.Stage s) {
        return switch (s) {
            case RECON -> "разведка";
            case PROBE -> "проба";
            case EXPLOIT -> "эксплойт";
            case EVASION -> "уклонение";
        };
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case "fly" -> "полёт";
            case "speed" -> "скорость";
            case "noslow" -> "без замедления";
            case "bigmove" -> "рывок";
            case "reach" -> "дистанция удара";
            case "timer" -> "таймер";
            case "blink" -> "blink";
            case "xray" -> "xray";
            case "esp-tracking" -> "ведение сквозь стены";
            case "staff-toggle" -> "отключение при стаффе";
            case "panic-pause" -> "замирание после алерта";
            case "relog-during-punishment" -> "релог под наказанием";
            default -> kind;
        };
    }

    static void open(ServerPlayerEntity admin) {
        List<AttackChainDetector.Chain> chains = AttackChainDetector.activeChains();
        List<HistoryManager.Event> burned = HistoryManager.recentPunishments(50).stream()
                .filter(e -> "EDR_CHAIN_BAN".equals(e.kind))
                .toList();

        ChestGui gui = new ChestGui(TextFmt.literal(
                "&8[&cBAC&8] &7Цепочки атак &8(&f" + chains.size() + "&8 активн., &f"
                        + burned.size() + "&8 сгор.)"), 6);

        if (chains.isEmpty() && burned.isEmpty()) {
            gui.button(13, GuiItems.item(Items.LIME_DYE, "&aАктивных цепочек нет",
                    "&7Пограничные флаги, наказания и",
                    "&7релог-паттерны собираются здесь,",
                    "&7как только образуют паттерн"), () -> {});
        }

        // Active chains: strongest first, one item each.
        int slot = 0;
        for (AttackChainDetector.Chain chain : chains) {
            if (slot > 26) break;
            AttackChainDetector.Verdict verdict = chain.verdict();
            boolean burnedFlag = verdict == AttackChainDetector.Verdict.BURNED;
            boolean armed = verdict == AttackChainDetector.Verdict.WATCH;
            List<AttackChainDetector.ChainEvent> events = List.copyOf(chain.events);
            java.util.Set<String> alertKinds = chain.alertKinds();
            java.util.Set<String> eligible = chain.eligibleKinds();

            List<String> lore = new java.util.ArrayList<>();
            lore.add("&7Разных проверок на алерте: &f" + alertKinds.size()
                    + " &8(к бану идут " + eligible.size() + ")"
                    + (burnedFlag ? " &c(бан)" : armed ? " &e(на контроле)" : " &7(своя лестница)"));
            lore.add("&8Событий в окне: " + chain.rawScore);
            // Module breakdown — which checks reached alert level.
            StringBuilder mods = new StringBuilder("&8Проверки: ");
            boolean firstMod = true;
            for (String kind : alertKinds) {
                if (!firstMod) mods.append(" &8/");
                firstMod = false;
                mods.append(eligible.contains(kind) ? "&c" : "&7").append(kindName(kind));
            }
            if (alertKinds.isEmpty()) mods.append("&7только пробы/контекст");
            lore.add(mods.toString());
            int from = Math.max(0, events.size() - 5);
            for (int i = from; i < events.size(); i++) {
                AttackChainDetector.ChainEvent e = events.get(i);
                lore.add("&f" + GuiItems.stamp(e.at()) + " &8" + stageName(e.stage()) + "&7/" + kindName(e.kind())
                        + (e.points() > 0 ? " &f+" + e.points() : " &8+0")
                        + (e.detail() == null || e.detail().isBlank() ? "" : " &8— " + e.detail()));
            }
            String color = burnedFlag ? "&c" : armed ? "&e" : "&7";
            lore.add("&8Клик — карточка игрока");
            gui.button(slot++, GuiItems.item(burnedFlag ? Items.TNT : armed ? Items.BLAZE_POWDER : Items.GLOWSTONE_DUST,
                            color + chain.name, lore),
                    () -> ACPlayerMenu.openByName(admin, chain.name));
        }

        // Burned incidents (recent chain bans) — the pattern that paid out.
        int bslot = 27;
        for (HistoryManager.Event e : burned) {
            if (bslot > 44) break;
            gui.button(bslot++, GuiItems.item(Items.REDSTONE,
                    "&c" + e.name + " &7— цепь #" + e.details,
                    "&7Сгорела: &f" + GuiItems.stamp(e.time) + " &8(" + GuiItems.ago(System.currentTimeMillis() - e.time) + " назад)",
                    "&7Срок: &f" + e.durationHours + " ч",
                    "&8Клик — карточка игрока"),
                    () -> ACPlayerMenu.openByName(admin, e.name));
        }

        gui.button(49, GuiItems.back("в панель"), () -> ACPanel.open(admin));
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private ACChainMenu() {}
}
