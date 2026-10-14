package dev.bastionclaims.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.core.ClaimManager;
import dev.bastionclaims.core.ProtectionService;
import dev.bastionclaims.gui.ClaimMenu;
import dev.bastionclaims.model.Claim;
import dev.bastionclaims.util.Fmt;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Admin {@code /claims} command tree (permission level 2+). */
public final class ClaimAdminCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("claims")
                .requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
                .then(literal("reload").executes(ClaimAdminCommands::reload))
                .then(literal("bypass").executes(ClaimAdminCommands::bypass))
                .then(literal("list")
                        .then(argument("игрок", word()).suggests(knownOwners())
                                .executes(ClaimAdminCommands::listOf)))
                .then(literal("delete")
                        .then(argument("игрок", word()).suggests(knownOwners())
                                .then(argument("название", greedyString()).suggests(claimNames())
                                        .executes(ClaimAdminCommands::delete))))
                // Managing someone else's claim used to be possible only by
                // standing inside it and knowing that /claim flag also accepts
                // operators; the admin browser itself could only teleport.
                .then(literal("here").executes(ClaimAdminCommands::here))
                .then(literal("manage")
                        .then(argument("игрок", word()).suggests(knownOwners())
                                .then(argument("название", greedyString()).suggests(claimNames())
                                        .executes(ClaimAdminCommands::manage))))
                .then(literal("flag")
                        .then(argument("игрок", word()).suggests(knownOwners())
                                .then(argument("флаг", word()).suggests(ClaimCommands.flagNames())
                                        .then(argument("значение", word()).suggests(ClaimCommands.onOff())
                                                .then(argument("название", greedyString()).suggests(claimNames())
                                                        .executes(ClaimAdminCommands::flag)))))));
    }

    /** Opens the management screen of whatever claim the operator stands in. */
    private static int here(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = ClaimManager.claimAt(ProtectionService.dimOf(p), p.getBlockX(), p.getBlockY(), p.getBlockZ());
        if (c == null) {
            p.sendMessage(Fmt.text("&7Здесь нет привата."), false);
            return 0;
        }
        ClaimMenu.openClaim(p, c.id);
        return 1;
    }

    private static int manage(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = named(ctx);
        if (c == null) return 0;
        ClaimMenu.openClaim(p, c.id);
        return 1;
    }

    private static int flag(CommandContext<ServerCommandSource> ctx) {
        Claim c = named(ctx);
        if (c == null) return 0;
        String flag = getString(ctx, "флаг");
        boolean on = ClaimCommands.parseOnOff(getString(ctx, "значение"));
        if (!ClaimCommands.applyFlag(c, flag, on)) {
            ctx.getSource().sendFeedback(() -> Fmt.text(cfg().message("flag.unknown")), false);
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Fmt.text(cfg().message("flag.set", flag, c.name, on ? "&aВКЛ" : "&cВЫКЛ")
                + " &8(владелец " + c.owner + ")"), true);
        return 1;
    }

    /** The claim named by the {@code игрок} + {@code название} arguments, or null after saying so. */
    private static Claim named(CommandContext<ServerCommandSource> ctx) {
        String owner = getString(ctx, "игрок");
        String name = getString(ctx, "название").trim();
        Claim c = ClaimManager.byOwnerAndName(owner, name);
        if (c == null) ctx.getSource().sendFeedback(() -> Fmt.text(cfg().message("delete.notFound", name)), false);
        return c;
    }

    private static ClaimConfig cfg() {
        return BastionClaims.config();
    }

    private static int reload(CommandContext<ServerCommandSource> ctx) {
        try {
            BastionClaims.reloadConfig();
            ctx.getSource().sendFeedback(() -> Fmt.text(cfg().message("admin.reloaded")), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFeedback(() -> Fmt.text(cfg().message("admin.reloadFailed", e.getMessage())), false);
            return 0;
        }
    }

    private static int bypass(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        boolean on = ProtectionService.toggleBypass(p);
        p.sendMessage(Fmt.text(cfg().message(on ? "admin.bypassOn" : "admin.bypassOff")), false);
        return 1;
    }

    private static int listOf(CommandContext<ServerCommandSource> ctx) {
        String owner = getString(ctx, "игрок");
        List<Claim> claims = ClaimManager.claimsOf(owner);
        ServerCommandSource src = ctx.getSource();
        src.sendFeedback(() -> Fmt.text(cfg().message("admin.listHeader", owner, claims.size())), false);
        for (Claim c : claims) {
            src.sendFeedback(() -> Fmt.text(cfg().message("list.entry", c.name, c.volume(), c.minX, c.minY, c.minZ)
                    + " &8[" + c.dim.replace("minecraft:", "") + "]"), false);
        }
        return 1;
    }

    private static int delete(CommandContext<ServerCommandSource> ctx) {
        String owner = getString(ctx, "игрок");
        String name = getString(ctx, "название").trim();
        Claim c = ClaimManager.byOwnerAndName(owner, name);
        if (c == null) {
            ctx.getSource().sendFeedback(() -> Fmt.text(cfg().message("delete.notFound", name)), false);
            return 0;
        }
        ClaimManager.remove(c);
        ctx.getSource().sendFeedback(() -> Fmt.text(cfg().message("admin.deleted", name, owner)), true);
        return 1;
    }

    private static SuggestionProvider<ServerCommandSource> knownOwners() {
        return (ctx, b) -> CommandSource.suggestMatching(
                ClaimManager.all().stream().map(c -> c.owner).distinct(), b);
    }

    private static SuggestionProvider<ServerCommandSource> claimNames() {
        return (ctx, b) -> {
            String owner;
            try {
                owner = getString(ctx, "игрок");
            } catch (IllegalArgumentException e) {
                return b.buildFuture();
            }
            return CommandSource.suggestMatching(ClaimManager.claimsOf(owner).stream().map(c -> c.name), b);
        };
    }

    private ClaimAdminCommands() {}
}
