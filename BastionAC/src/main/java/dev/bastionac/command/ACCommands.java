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

    private ACCommands() {}
}
