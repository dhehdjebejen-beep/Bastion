package dev.bastionac.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.bastionac.BastionAC;
import dev.bastionac.core.Alerts;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.Lang;
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

/** Admin commands: /bac alerts | shield | status <ник> | reload | version. */
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
                .then(literal("shield").executes(ctx -> {
                    ACPanel.openShield(ctx.getSource().getPlayerOrThrow());
                    return 1;
                }))
                .then(literal("alerts").executes(ctx -> {
                    ServerPlayerEntity admin = ctx.getSource().getPlayerOrThrow();
                    boolean on = Alerts.toggle(admin);
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            on ? Lang.t("§a[BAC] Алерты включены") : Lang.t("§7[BAC] Алерты выключены")), false);
                    return 1;
                }))
                .then(literal("status").then(argument(Lang.arg("ник"), word()).executes(ctx -> {
                    String name = getString(ctx, Lang.arg("ник"));
                    ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
                    if (target == null) {
                        ctx.getSource().sendFeedback(() -> Text.literal(Lang.t("§c[BAC] Игрок ") + name + Lang.t(" не онлайн")), false);
                        return 0;
                    }
                    PlayerData d = BastionAC.data(target);
                    Map<CheckType, Double> snapshot = d != null ? d.vl.snapshot() : Map.of();
                    if (snapshot.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> Text.literal(
                                "§a[BAC] " + target.getGameProfile().name() + Lang.t(": активных нарушений нет")), false);
                        return 1;
                    }
                    StringBuilder sb = new StringBuilder("§e[BAC] ").append(target.getGameProfile().name()).append(":");
                    snapshot.forEach((type, vl) -> sb.append(" §f").append(type.key).append("§7=").append(String.format("%.1f", vl)));
                    ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                    return 1;
                })))
                .then(literal("freeze").then(argument(Lang.arg("ник"), word())
                        .executes(ctx -> freeze(ctx.getSource(), getString(ctx, Lang.arg("ник")), null))
                        .then(argument(Lang.arg("причина"), com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> freeze(ctx.getSource(), getString(ctx, Lang.arg("ник")),
                                        getString(ctx, Lang.arg("причина")))))))
                .then(literal("unfreeze").then(argument(Lang.arg("ник"), word()).executes(ctx -> {
                    ServerCommandSource src = ctx.getSource();
                    String name = getString(ctx, Lang.arg("ник"));
                    java.util.UUID id = null;
                    for (var en : dev.bastionac.core.FreezeManager.list().entrySet()) {
                        if (en.getValue().name.equalsIgnoreCase(name)) id = en.getKey();
                    }
                    if (id == null || !dev.bastionac.core.FreezeManager.unfreeze(src.getServer(), id, src.getName())) {
                        src.sendFeedback(() -> Text.literal("§c[BAC] " + name + Lang.t(" не заморожен")), false);
                        return 0;
                    }
                    src.sendFeedback(() -> Text.literal("§a[BAC] " + name + Lang.t(" разморожен")), true);
                    return 1;
                })))
                .then(literal("frozen").executes(ctx -> {
                    var all = dev.bastionac.core.FreezeManager.list();
                    if (all.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> Text.literal(Lang.t("§7[BAC] Замороженных нет")), false);
                        return 1;
                    }
                    StringBuilder sb = new StringBuilder(Lang.t("§b[BAC] Заморожены:"));
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
                                ctx.getSource().sendFeedback(() -> dev.bastionac.core.TextFmt.literal(Lang.t("&5[Аргус] ") + line), false);
                            }
                            return 1;
                        }))
                        .then(literal("analyze").then(argument(Lang.arg("ник"), word()).executes(ctx -> {
                            ServerCommandSource src = ctx.getSource();
                            String name = getString(ctx, Lang.arg("ник"));
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
                                    ? Lang.t("&c[Аргус] ") + real + ": " + inv.error()
                                    : Lang.t("&5[Аргус] &7Дело #") + inv.caseId() + " (" + real + Lang.t(") — разбор начат &8(") + inv.note()
                                            + Lang.t("), итог придёт сюда")), false);
                            return inv.caseId() == null ? 0 : 1;
                        })))
                        .then(literal("explain").then(argument("id", word()).executes(ctx -> {
                            dev.bastionac.argus.CaseFile c = dev.bastionac.argus.Argus.get(getString(ctx, "id"));
                            ServerCommandSource src = ctx.getSource();
                            if (c == null) {
                                src.sendFeedback(() -> Text.literal(Lang.t("§c[Аргус] Нет такого дела")), false);
                                return 0;
                            }
                            src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal(String.format(java.util.Locale.ROOT,
                                    Lang.t("&5[Аргус] &7#%s %s: %s%s &8(%.1f%%)"), c.id, c.name, c.verdict.colour, c.verdict.label(),
                                    c.score * 100)), false);
                            for (String f : c.findings) {
                                src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal("&7 • &f" + f), false);
                            }
                            if (c.reportFile != null) {
                                src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal(Lang.t("&8 отчёт: ") + c.reportFile), false);
                            }
                            return 1;
                        })))
                        .then(literal("mode").then(argument(Lang.arg("режим"), word())
                                .suggests((c, b) -> b.suggest("shadow").suggest("assist").suggest("auto").buildFuture())
                                .executes(ctx -> {
                                    String m = getString(ctx, Lang.arg("режим")).toUpperCase(java.util.Locale.ROOT);
                                    if (!java.util.List.of("SHADOW", "ASSIST", "AUTO").contains(m)) {
                                        ctx.getSource().sendFeedback(() -> Text.literal(Lang.t("§c[Аргус] shadow | assist | auto")), false);
                                        return 0;
                                    }
                                    dev.bastionac.argus.Argus.setMode(m);
                                    ctx.getSource().sendFeedback(() -> dev.bastionac.core.TextFmt.literal(
                                            Lang.t("&5[Аргус] &7Режим: &f") + dev.bastionac.argus.Argus.modeLabel(m)), true);
                                    return 1;
                                }))))
                .then(literal("proactive")
                        .executes(ctx -> proactive(ctx.getSource(), null))
                        .then(literal("on").executes(ctx -> proactive(ctx.getSource(), true)))
                        .then(literal("off").executes(ctx -> proactive(ctx.getSource(), false))))
                .then(literal("telemetry")
                        .executes(ctx -> telemetry(ctx.getSource(), null))
                        .then(argument(Lang.arg("ник"), word()).executes(ctx -> telemetry(ctx.getSource(), getString(ctx, Lang.arg("ник"))))))
                .then(literal("lang")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> dev.bastionac.core.TextFmt.literal(String.format(
                                    Lang.t("&7Язык BastionAC: &f%s &8(доступны: %s)"), Lang.code(),
                                    String.join(", ", Lang.available()))), false);
                            return 1;
                        })
                        .then(argument(Lang.arg("язык"), word())
                                .suggests((c, b) -> net.minecraft.command.CommandSource.suggestMatching(Lang.available(), b))
                                .executes(ctx -> {
                                    String code = getString(ctx, Lang.arg("язык"));
                                    if (!BastionAC.setLanguage(code)) {
                                        ctx.getSource().sendFeedback(() -> dev.bastionac.core.TextFmt.literal(String.format(
                                                Lang.t("&cЯзыка &f%s&c нет. Доступны: &f%s"), Lang.normalize(code),
                                                String.join(", ", Lang.available()))), false);
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(() -> dev.bastionac.core.TextFmt.literal(String.format(
                                            Lang.t("&aЯзык BastionAC: &f%s"), Lang.code())), true);
                                    return 1;
                                })))
                .then(literal("reload").executes(ctx -> {
                    ServerCommandSource source = ctx.getSource();
                    BastionAC.reloadConfig(message -> source.sendFeedback(() -> Text.literal(message), false));
                    return 1;
                }))
                .then(literal("version").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            "§7[BAC] BastionAC " + BastionAC.VERSION + Lang.t(" — VL-движок, лаг-компенсация, ")
                                    + CheckType.values().length + Lang.t(" проверок")), false);
                    return 1;
                })));
    }

    /** Where a player's telemetry is going, or the folder as a whole. */
    private static int telemetry(ServerCommandSource src, String name) {
        java.util.function.Consumer<String> say = s -> src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal(s), false);
        dev.bastionac.telemetry.Telemetry.runJanitor();   // the totals below are from the previous pass; this refreshes them
        dev.bastionac.telemetry.TelemetryIO.drain(3_000);
        say.accept(Lang.t("&b[Телеметрия] &7Папка: &f") + (dev.bastionac.telemetry.TelemetryIO.totalBytes() / (1024 * 1024))
                + Lang.t(" МБ &7в &f") + dev.bastionac.telemetry.TelemetryIO.fileCount() + Lang.t(" &7файлах (лимит ")
                + BastionAC.config().telemetry.maxTotalMb + Lang.t(" МБ, ") + BastionAC.config().telemetry.retentionDays + Lang.t(" дн.)")
                + (BastionAC.config().telemetry.enabled ? "" : Lang.t(" &cВЫКЛЮЧЕНА")));
        if (name == null) {
            for (var w : dev.bastionac.telemetry.Telemetry.openWriters()) {
                say.accept("&7 " + w.name + ": &f" + (w.rawBytes() / 1024) + Lang.t(" КБ&7, записей &f") + w.records());
            }
            return 1;
        }
        ServerPlayerEntity target = src.getServer().getPlayerManager().getPlayer(name);
        if (target == null) {
            say.accept(Lang.t("&c[Телеметрия] ") + name + Lang.t(" не онлайн"));
            return 0;
        }
        var w = dev.bastionac.telemetry.Telemetry.of(target.getUuid());
        if (w == null) {
            say.accept(Lang.t("&c[Телеметрия] для ") + name + Lang.t(" не пишется"));
            return 0;
        }
        PlayerData d = BastionAC.data(target);
        say.accept(Lang.t("&7 файл: &f") + w.fileName());
        say.accept(Lang.t("&7 записано: &f") + (w.rawBytes() / 1024) + Lang.t(" КБ &7(до сжатия), записей &f") + w.records());
        if (d != null) {
            say.accept(String.format(java.util.Locale.ROOT, Lang.t("&7 таймер: &f%.0f мс &7(часы: %s), клавиши: %s"),
                    d.timer.balance(), d.tickEndKind == 1 ? Lang.t("концы тиков") : d.tickEndKind == 2 ? Lang.t("пакеты (старый клиент)") : Lang.t("ещё неизвестно"),
                    d.inputBits < 0 ? Lang.t("&8не приходили") : Lang.t("&fприходят")));
        }
        return 1;
    }

    private static int freeze(ServerCommandSource src, String name, String reason) {
        ServerPlayerEntity target = src.getServer().getPlayerManager().getPlayer(name);
        if (target == null) {
            src.sendFeedback(() -> Text.literal(Lang.t("§c[BAC] Игрок ") + name + Lang.t(" не онлайн")), false);
            return 0;
        }
        boolean fresh = dev.bastionac.core.FreezeManager.freeze(target, src.getName(), reason);
        src.sendFeedback(() -> Text.literal(fresh ? "§b[BAC] " + target.getGameProfile().name() + Lang.t(" заморожен")
                : "§7[BAC] " + target.getGameProfile().name() + Lang.t(" уже заморожен (причина обновлена)")), true);
        return 1;
    }

    /** /bac proactive [on|off] — the checks only alert, Argus decides (3.5 beta). */
    private static int proactive(net.minecraft.server.command.ServerCommandSource src, Boolean on) {
        var p = BastionAC.config().proactive;
        if (on != null) {
            p.enabled = on;
            BastionAC.saveConfig();
        }
        boolean live = dev.bastionac.core.Proactive.enabled();
        int enforced = dev.bastionac.core.Proactive.enforcedCount(System.currentTimeMillis());
        String state = !p.enabled ? Lang.t("&7выключен") : live ? Lang.t("&aвключён") : Lang.t("&cвключён, но Аргус не работает — проверки действуют как обычно");
        src.sendFeedback(() -> dev.bastionac.core.TextFmt.literal(String.format(java.util.Locale.ROOT,
                Lang.t("&5[Аргус] &7Проактивный режим: %s&7. Санкции с оценки &f%.0f%% &7на &f%d мин&7, "
                        + "первый взгляд через &f%d с&7, бан от &f%d мин &7данных. Сейчас под санкциями: &f%d"),
                state, p.enforceScore * 100, p.enforceMinutes, p.firstLookSeconds, p.banMinMinutes, enforced)), on != null);
        return 1;
    }

    private ACCommands() {}
}
