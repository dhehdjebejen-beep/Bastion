package dev.bastionac.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.bastionac.BastionAC;
import dev.bastionac.core.Alerts;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.PlayerData;
import dev.bastionac.gui.ACPanel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Admin commands: /bac alerts | status <ник> | reload | version. */
public final class ACCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bac")
                .requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
                .executes(ctx -> {
                    ACPanel.open(ctx.getSource().getPlayerOrThrow());
                    return 1;
                })
                .then(literal("panel").executes(ctx -> {
                    ACPanel.open(ctx.getSource().getPlayerOrThrow());
                    return 1;
                }))
                .then(literal("alerts").executes(ctx -> {
                    ServerPlayerEntity admin = ctx.getSource().getPlayerOrThrow();
                    boolean on = Alerts.toggle(admin);
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            on ? "§a[BAC] Алерты включены" : "§7[BAC] Алерты выключены"), false);
                    return 1;
                }))
                .then(literal("status").then(argument("ник", word()).executes(ctx -> {
                    String name = getString(ctx, "ник");
                    ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
                    if (target == null) {
                        ctx.getSource().sendFeedback(() -> Text.literal("§c[BAC] Игрок " + name + " не онлайн"), false);
                        return 0;
                    }
                    PlayerData d = BastionAC.data(target);
                    Map<CheckType, Double> snapshot = d != null ? d.vl.snapshot() : Map.of();
                    if (snapshot.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> Text.literal(
                                "§a[BAC] " + target.getGameProfile().name() + ": активных нарушений нет"), false);
                        return 1;
                    }
                    StringBuilder sb = new StringBuilder("§e[BAC] ").append(target.getGameProfile().name()).append(":");
                    snapshot.forEach((type, vl) -> sb.append(" §f").append(type.key).append("§7=").append(String.format("%.1f", vl)));
                    ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                    return 1;
                })))
                .then(literal("freeze").then(argument("ник", word())
                        .executes(ctx -> freeze(ctx.getSource(), getString(ctx, "ник"), null))
                        .then(argument("причина", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> freeze(ctx.getSource(), getString(ctx, "ник"),
                                        getString(ctx, "причина"))))))
                .then(literal("unfreeze").then(argument("ник", word()).executes(ctx -> {
                    ServerCommandSource src = ctx.getSource();
                    String name = getString(ctx, "ник");
                    java.util.UUID id = null;
                    for (var en : dev.bastionac.core.FreezeManager.list().entrySet()) {
                        if (en.getValue().name.equalsIgnoreCase(name)) id = en.getKey();
                    }
                    if (id == null || !dev.bastionac.core.FreezeManager.unfreeze(src.getServer(), id, src.getName())) {
                        src.sendFeedback(() -> Text.literal("§c[BAC] " + name + " не заморожен"), false);
                        return 0;
                    }
                    src.sendFeedback(() -> Text.literal("§a[BAC] " + name + " разморожен"), true);
                    return 1;
                })))
                .then(literal("frozen").executes(ctx -> {
                    var all = dev.bastionac.core.FreezeManager.list();
                    if (all.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> Text.literal("§7[BAC] Замороженных нет"), false);
                        return 1;
                    }
                    StringBuilder sb = new StringBuilder("§b[BAC] Заморожены:");
                    all.values().forEach(e -> sb.append("\n §f").append(e.name).append(" §7— ").append(e.reason)
                            .append(" §8(").append(e.by).append(")"));
                    ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                    return 1;
                }))
                .then(literal("argus")
                        .executes(ctx -> {
                            dev.bastionac.gui.ArgusMenu.open(ctx.getSource().getPlayerOrThrow());
                            return 1;
                        })
                        .then(literal("case").then(argument("id", word()).executes(ctx -> {
                            dev.bastionac.gui.ArgusMenu.openCase(ctx.getSource().getPlayerOrThrow(), getString(ctx, "id"));
                            return 1;
                        })))
                        .then(literal("status").executes(ctx -> {
                            for (String line : dev.bastionac.argus.Argus.status()) {
                                ctx.getSource().sendFeedback(() -> dev.bastionac.core.TextFmt.literal("&5[Аргус] " + line), false);
                            }
                            return 1;
                        }))
                        .then(literal("analyze").then(argument("ник", word()).executes(ctx -> {
                            ServerCommandSource src = ctx.getSource();
                            String name = getString(ctx, "ник");
                            ServerPlayerEntity target = src.getServer().getPlayerManager().getPlayer(name);
                            // Offline: the uuid and exact name from the player's own telemetry
                            // (an offline-mode uuid from a mistyped case points at nobody).
                            String[] known = target == null ? dev.bastionac.argus.SessionLocator.byName(name) : null;
                            java.util.UUID id = target != null ? target.getUuid()
                                    : known != null ? java.util.UUID.fromString(known[0])
                                    : net.minecraft.util.Uuids.getOfflinePlayerUuid(name);
                            String real = target != null ? target.getGameProfile().name() : known != null ? known[1] : name;
                            dev.bastionac.argus.Argus.Investigation inv =
                                    dev.bastionac.argus.Argus.investigate(id, real, src.getName(), 0);
                            src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal(inv.caseId() == null
                                    ? "&c[Аргус] " + real + ": " + inv.error()
                                    : "&5[Аргус] &7Дело #" + inv.caseId() + " (" + real + ") — разбор начат &8(" + inv.note()
                                            + "), итог придёт сюда"), false);
                            return inv.caseId() == null ? 0 : 1;
                        })))
                        .then(literal("explain").then(argument("id", word()).executes(ctx -> {
                            dev.bastionac.argus.CaseFile c = dev.bastionac.argus.Argus.get(getString(ctx, "id"));
                            ServerCommandSource src = ctx.getSource();
                            if (c == null) {
                                src.sendFeedback(() -> Text.literal("§c[Аргус] Нет такого дела"), false);
                                return 0;
                            }
                            src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal(String.format(java.util.Locale.ROOT,
                                    "&5[Аргус] &7#%s %s: %s%s &8(%.1f%%)", c.id, c.name, c.verdict.colour, c.verdict.label,
                                    c.score * 100)), false);
                            for (String f : c.findings) {
                                src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal("&7 • &f" + f), false);
                            }
                            if (c.reportFile != null) {
                                src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal("&8 отчёт: " + c.reportFile), false);
                            }
                            return 1;
                        })))
                        .then(literal("mode").then(argument("режим", word())
                                .suggests((c, b) -> b.suggest("shadow").suggest("assist").suggest("auto").buildFuture())
                                .executes(ctx -> {
                                    String m = getString(ctx, "режим").toUpperCase(java.util.Locale.ROOT);
                                    if (!java.util.List.of("SHADOW", "ASSIST", "AUTO").contains(m)) {
                                        ctx.getSource().sendFeedback(() -> Text.literal("§c[Аргус] shadow | assist | auto"), false);
                                        return 0;
                                    }
                                    dev.bastionac.argus.Argus.setMode(m);
                                    ctx.getSource().sendFeedback(() -> dev.bastionac.core.TextFmt.literal(
                                            "&5[Аргус] &7Режим: &f" + dev.bastionac.argus.Argus.modeLabel(m)), true);
                                    return 1;
                                }))))
                .then(literal("telemetry")
                        .executes(ctx -> telemetry(ctx.getSource(), null))
                        .then(argument("ник", word()).executes(ctx -> telemetry(ctx.getSource(), getString(ctx, "ник")))))
                .then(literal("reload").executes(ctx -> {
                    ServerCommandSource source = ctx.getSource();
                    BastionAC.reloadConfig(message -> source.sendFeedback(() -> Text.literal(message), false));
                    return 1;
                }))
                .then(literal("version").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            "§7[BAC] BastionAC " + BastionAC.VERSION + " — VL-движок, лаг-компенсация, "
                                    + CheckType.values().length + " проверок"), false);
                    return 1;
                })));
    }

    /** Where a player's telemetry is going, or the folder as a whole. */
    private static int telemetry(ServerCommandSource src, String name) {
        java.util.function.Consumer<String> say = s -> src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal(s), false);
        dev.bastionac.telemetry.Telemetry.runJanitor();   // the totals below are from the previous pass; this refreshes them
        dev.bastionac.telemetry.TelemetryIO.drain(3_000);
        say.accept("&b[Телеметрия] &7Папка: &f" + (dev.bastionac.telemetry.TelemetryIO.totalBytes() / (1024 * 1024))
                + " МБ &7в &f" + dev.bastionac.telemetry.TelemetryIO.fileCount() + " &7файлах (лимит "
                + BastionAC.config().telemetry.maxTotalMb + " МБ, " + BastionAC.config().telemetry.retentionDays + " дн.)"
                + (BastionAC.config().telemetry.enabled ? "" : " &cВЫКЛЮЧЕНА"));
        if (name == null) {
            for (var w : dev.bastionac.telemetry.Telemetry.openWriters()) {
                say.accept("&7 " + w.name + ": &f" + (w.rawBytes() / 1024) + " КБ&7, записей &f" + w.records());
            }
            return 1;
        }
        ServerPlayerEntity target = src.getServer().getPlayerManager().getPlayer(name);
        if (target == null) {
            say.accept("&c[Телеметрия] " + name + " не онлайн");
            return 0;
        }
        var w = dev.bastionac.telemetry.Telemetry.of(target.getUuid());
        if (w == null) {
            say.accept("&c[Телеметрия] для " + name + " не пишется");
            return 0;
        }
        PlayerData d = BastionAC.data(target);
        say.accept("&7 файл: &f" + w.fileName());
        say.accept("&7 записано: &f" + (w.rawBytes() / 1024) + " КБ &7(до сжатия), записей &f" + w.records());
        if (d != null) {
            say.accept(String.format(java.util.Locale.ROOT, "&7 таймер: &f%.0f мс &7(часы: %s), клавиши: %s",
                    d.timer.balance(), d.tickEndKind == 1 ? "концы тиков" : d.tickEndKind == 2 ? "пакеты (старый клиент)" : "ещё неизвестно",
                    d.inputBits < 0 ? "&8не приходили" : "&fприходят"));
        }
        return 1;
    }

    private static int freeze(ServerCommandSource src, String name, String reason) {
        ServerPlayerEntity target = src.getServer().getPlayerManager().getPlayer(name);
        if (target == null) {
            src.sendFeedback(() -> Text.literal("§c[BAC] Игрок " + name + " не онлайн"), false);
            return 0;
        }
        boolean fresh = dev.bastionac.core.FreezeManager.freeze(target, src.getName(), reason);
        src.sendFeedback(() -> Text.literal(fresh ? "§b[BAC] " + target.getGameProfile().name() + " заморожен"
                : "§7[BAC] " + target.getGameProfile().name() + " уже заморожен (причина обновлена)"), true);
        return 1;
    }

    private ACCommands() {}
}
