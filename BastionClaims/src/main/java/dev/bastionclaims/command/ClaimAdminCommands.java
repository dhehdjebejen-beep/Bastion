package dev.bastionclaims.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.core.ClaimManager;
import dev.bastionclaims.core.ProtectionService;
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
                                .then(argument("название", greedyString())
                                        .executes(ClaimAdminCommands::delete)))));
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

    private ClaimAdminCommands() {}
}
