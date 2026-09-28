package dev.bastionauth.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.bastionauth.BastionAuth;
import dev.bastionauth.core.AuthManager;
import dev.bastionauth.core.LinkVerdict;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /связи} — the staff register of account links.
 *
 * <p>The chest panel in BastionAC is the comfortable way to use this; these
 * commands are the one that works from console, from a phone, and when the
 * anti-cheat is not installed. Both edit the same ledger.
 *
 * <pre>
 *   /связи                       очередь связей без решения
 *   /связи &lt;ник&gt;                 связи одного аккаунта и их статусы
 *   /связи доверять &lt;a&gt; &lt;b&gt; [заметка]
 *   /связи наблюдать &lt;a&gt; &lt;b&gt; [заметка]
 *   /связи мульти &lt;a&gt; &lt;b&gt; [заметка]
 *   /связи сброс &lt;a&gt; &lt;b&gt;
 *   /связи доверять-все &lt;ник&gt; [заметка]
 * </pre>
 *
 * <p>Player names go through {@code word()}, which is safe because Minecraft
 * names are ASCII; the note is a {@code greedyString()} because Brigadier's
 * {@code word()} rejects Cyrillic and every note here is Russian. The
 * sub-command words are literals, which Brigadier matches by prefix and which
 * therefore may be Cyrillic.
 */
public final class LinkCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(tree("связи"));
        dispatcher.register(tree("links"));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> tree(String name) {
        java.util.function.Predicate<ServerCommandSource> op =
                src -> CommandManager.ADMINS_CHECK.allows(src.getPermissions());
        LiteralArgumentBuilder<ServerCommandSource> root = literal(name).requires(op)
                .executes(ctx -> queue(ctx.getSource()))
                .then(literal("очередь").executes(ctx -> queue(ctx.getSource())))
                .then(literal("queue").executes(ctx -> queue(ctx.getSource())));

        // The verdict verbs, each in the spellings staff actually type.
        verdict(root, LinkVerdict.TRUSTED, "доверять", "доверить", "trust");
        verdict(root, LinkVerdict.WATCH, "наблюдать", "watch");
        verdict(root, LinkVerdict.ALT, "мульти", "alt");
        verdict(root, LinkVerdict.NEW, "сброс", "reset");

        // Bulk: every pair this account is part of. The shortcut for "all
        // three of these are mine" — otherwise that is one command per pair,
        // and the count grows quadratically with the number of accounts.
        for (String word : new String[]{"доверять-все", "доверять-всё", "trust-all"}) {
            root.then(literal(word).then(argument("ник", word())
                    .executes(ctx -> bulk(ctx, LinkVerdict.TRUSTED, ""))
                    .then(argument("заметка", greedyString())
                            .executes(ctx -> bulk(ctx, LinkVerdict.TRUSTED, getString(ctx, "заметка"))))));
        }

        // Bare name: that account's links. Registered last so the literals
        // above win the parse — "доверять" would otherwise read as a nick.
        return root.then(argument("ник", word()).executes(ctx -> {
            AuthManager m = BastionAuth.manager();
            if (m == null) return 0;
            m.adminLinks(ctx.getSource(), getString(ctx, "ник"));
            return 1;
        }));
    }

    private static void verdict(LiteralArgumentBuilder<ServerCommandSource> root,
                                LinkVerdict verdict, String... words) {
        for (String word : words) {
            root.then(literal(word).then(argument("первый", word()).then(argument("второй", word())
                    .executes(ctx -> pair(ctx, verdict, ""))
                    .then(argument("заметка", greedyString())
                            .executes(ctx -> pair(ctx, verdict, getString(ctx, "заметка")))))));
        }
    }

    private static int queue(ServerCommandSource source) {
        AuthManager m = BastionAuth.manager();
        if (m == null) return 0;
        m.adminLinkQueue(source);
        return 1;
    }

    private static int pair(CommandContext<ServerCommandSource> ctx, LinkVerdict verdict, String note) {
        AuthManager m = BastionAuth.manager();
        if (m == null) return 0;
        m.adminSetLinkVerdict(ctx.getSource(), getString(ctx, "первый"), getString(ctx, "второй"), verdict, note);
        return 1;
    }

    private static int bulk(CommandContext<ServerCommandSource> ctx, LinkVerdict verdict, String note) {
        AuthManager m = BastionAuth.manager();
        if (m == null) return 0;
        m.adminSetAllLinkVerdicts(ctx.getSource(), getString(ctx, "ник"), verdict, note);
        return 1;
    }

    private LinkCommands() {}
}
