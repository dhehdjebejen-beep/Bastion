package dev.bastionauth.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.bastionauth.BastionAuth;
import dev.bastionauth.core.AuthManager;
import net.minecraft.server.command.ServerCommandSource;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /2fa} and {@code /слово}.
 *
 * <p>{@code /2fa <код>} is the login step and is on the pre-login whitelist;
 * everything else under {@code /2fa} needs an authenticated player and is
 * refused by the manager otherwise. Codes and words are {@code word()}
 * arguments — the command gate keeps malformed ones out of the log.
 */
public final class TwoFactorCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(twoFa("2fa"));
        dispatcher.register(codeword("слово"));
        dispatcher.register(codeword("codeword"));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> twoFa(String name) {
        LiteralArgumentBuilder<ServerCommandSource> tree = literal(name).executes(ctx -> {
            AuthManager m = BastionAuth.manager();
            if (m == null) return 0;
            m.twoFaStatus(ctx.getSource().getPlayerOrThrow());
            return 1;
        });
        for (String w : new String[]{"включить", "enable", "on"}) {
            tree.then(literal(w).executes(ctx -> {
                AuthManager m = BastionAuth.manager();
                if (m == null) return 0;
                m.twoFaBegin(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
        }
        for (String w : new String[]{"подтвердить", "confirm"}) {
            tree.then(literal(w).then(argument("код", word()).executes(ctx -> {
                AuthManager m = BastionAuth.manager();
                if (m == null) return 0;
                m.twoFaConfirm(ctx.getSource().getPlayerOrThrow(), getString(ctx, "код"));
                return 1;
            })));
        }
        for (String w : new String[]{"выключить", "disable", "off"}) {
            tree.then(literal(w).then(argument("код", word()).executes(ctx -> {
                AuthManager m = BastionAuth.manager();
                if (m == null) return 0;
                m.twoFaDisable(ctx.getSource().getPlayerOrThrow(), getString(ctx, "код"));
                return 1;
            })));
        }
        for (String w : new String[]{"коды", "codes"}) {
            tree.then(literal(w).then(argument("код", word()).executes(ctx -> {
                AuthManager m = BastionAuth.manager();
                if (m == null) return 0;
                m.twoFaNewBackupCodes(ctx.getSource().getPlayerOrThrow(), getString(ctx, "код"));
                return 1;
            })));
        }
        // The login step: a bare code.
        tree.then(argument("код", word()).executes(ctx -> {
            AuthManager m = BastionAuth.manager();
            if (m == null) return 0;
            m.tryCode(ctx.getSource().getPlayerOrThrow(), getString(ctx, "код"));
            return 1;
        }));
        return tree;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> codeword(String name) {
        LiteralArgumentBuilder<ServerCommandSource> tree = literal(name).executes(ctx -> {
            AuthManager m = BastionAuth.manager();
            if (m == null) return 0;
            m.codewordStatus(ctx.getSource().getPlayerOrThrow());
            return 1;
        });
        for (String w : new String[]{"установить", "set"}) {
            tree.then(literal(w).then(argument("слово", word()).executes(ctx -> {
                AuthManager m = BastionAuth.manager();
                if (m == null) return 0;
                m.codewordSet(ctx.getSource().getPlayerOrThrow(), getString(ctx, "слово"));
                return 1;
            })));
        }
        for (String w : new String[]{"снять", "remove"}) {
            tree.then(literal(w).then(argument("слово", word()).executes(ctx -> {
                AuthManager m = BastionAuth.manager();
                if (m == null) return 0;
                m.codewordSpeak(ctx.getSource().getPlayerOrThrow(), getString(ctx, "слово"), true);
                return 1;
            })));
        }
        tree.then(argument("слово", word()).executes(ctx -> {
            AuthManager m = BastionAuth.manager();
            if (m == null) return 0;
            m.codewordSpeak(ctx.getSource().getPlayerOrThrow(), getString(ctx, "слово"), false);
            return 1;
        }));
        return tree;
    }

    private TwoFactorCommands() {}
}
