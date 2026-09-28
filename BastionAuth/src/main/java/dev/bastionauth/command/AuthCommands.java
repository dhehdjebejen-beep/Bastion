package dev.bastionauth.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.bastionauth.BastionAuth;
import dev.bastionauth.core.AuthManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Player commands: /register /reg, /login /l, /logout,
 * /changepassword /changepw and the /auth admin tree.
 *
 * <p>Passwords are plain {@code word()} arguments and must not contain spaces
 * (also enforced by the password policy). Vanilla does not log a
 * successfully-parsed player command, but it DOES echo the full raw text of a
 * command that fails to parse ("Unknown or incomplete command"). The command
 * gate ({@code CommandManagerMixin}) therefore cancels malformed auth commands
 * from unauthenticated players before that log line can be written.
 */
public final class AuthCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(buildRegister("register"));
        dispatcher.register(buildRegister("reg"));
        dispatcher.register(buildLogin("login"));
        dispatcher.register(buildLogin("l"));
        dispatcher.register(buildChangePassword("changepassword"));
        dispatcher.register(buildChangePassword("changepw"));
        dispatcher.register(agreementTree("agreement"));
        dispatcher.register(agreementTree("соглашение"));
        dispatcher.register(literal("logout").executes(ctx -> {
            AuthManager m = BastionAuth.manager();
            if (m == null) return 0;
            m.logout(ctx.getSource().getPlayerOrThrow());
            return 1;
        }));
        dispatcher.register(adminTree());
        TwoFactorCommands.register(dispatcher);
        LinkCommands.register(dispatcher);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildRegister(String name) {
        return literal(name)
                .then(argument("пароль", word())
                        .then(argument("повтор", word())
                                .executes(ctx -> {
                                    AuthManager m = BastionAuth.manager();
                                    if (m == null) return 0;
                                    m.tryRegister(ctx.getSource().getPlayerOrThrow(),
                                            getString(ctx, "пароль"), getString(ctx, "повтор"));
                                    return 1;
                                })));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildLogin(String name) {
        return literal(name)
                .then(argument("пароль", word())
                        .executes(ctx -> {
                            AuthManager m = BastionAuth.manager();
                            if (m == null) return 0;
                            m.tryLogin(ctx.getSource().getPlayerOrThrow(), getString(ctx, "пароль"));
                            return 1;
                        })
                        // Password and second factor in one line, for those who prefer it.
                        .then(argument("код", word()).executes(ctx -> {
                            AuthManager m = BastionAuth.manager();
                            if (m == null) return 0;
                            m.tryLogin(ctx.getSource().getPlayerOrThrow(), getString(ctx, "пароль"), getString(ctx, "код"));
                            return 1;
                        })));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildChangePassword(String name) {
        return literal(name)
                .then(argument("старый", word())
                        .then(argument("новый", word())
                                .then(argument("повтор", word())
                                        .executes(ctx -> {
                                            AuthManager m = BastionAuth.manager();
                                            if (m == null) return 0;
                                            m.tryChangePassword(ctx.getSource().getPlayerOrThrow(),
                                                    getString(ctx, "старый"),
                                                    getString(ctx, "новый"),
                                                    getString(ctx, "повтор"));
                                            return 1;
                                        }))));
    }

    /** The accept action, shared by every spelling of it. */
    private static int accept(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        AuthManager m = BastionAuth.manager();
        if (m == null) return 0;
        var p = ctx.getSource().getPlayerOrThrow();
        if (!m.acceptAgreement(p)) {
            dev.bastionauth.core.ChatGate.say(p, m.text("agreement.already"), false);
            return 0;
        }
        dev.bastionauth.core.ChatGate.say(p, m.text("agreement.accepted"), false);
        m.sendNextStepAfterAgreement(p);
        return 1;
    }

    /** {@code /agreement} — take the books, read a page, accept. */
    private static LiteralArgumentBuilder<ServerCommandSource> agreementTree(String name) {
        LiteralArgumentBuilder<ServerCommandSource> tree = literal(name);
        // Players type what they see; the prompt is Russian, so the Russian
        // spellings must work too, not only the English literal.
        for (String word : new String[]{"принять", "да", "yes", "ok", "согласен", "согласна"}) {
            tree.then(literal(word).executes(AuthCommands::accept));
        }
        return tree
                .executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    var p = ctx.getSource().getPlayerOrThrow();
                    if (m.hasAcceptedAgreement(p)) {
                        dev.bastionauth.core.ChatGate.say(p, m.text("agreement.already"), false);
                        dev.bastionauth.core.AgreementManager.giveBooks(p);
                        return 1;
                    }
                    m.promptAgreement(p);
                    return 1;
                })
                .then(literal("accept").executes(AuthCommands::accept))
                .then(literal("books").executes(ctx -> {
                    var p = ctx.getSource().getPlayerOrThrow();
                    AuthManager m = BastionAuth.manager();
                    // Before the agreement is accepted the volumes are drawn into
                    // the view, never given: see InventoryMask.
                    if (m != null && m.showsAgreementBooks(p.getUuid())) {
                        m.promptAgreement(p);
                        return 1;
                    }
                    int given = dev.bastionauth.core.AgreementManager.giveBooks(p);
                    if (m != null) dev.bastionauth.core.ChatGate.say(p, m.text("agreement.books", given), false);
                    return 1;
                }))
                .then(literal("read")
                        .executes(ctx -> readPage(ctx.getSource(), 0, 0))
                        .then(argument("том", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 9))
                                .executes(ctx -> readPage(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "том") - 1, 0))
                                .then(argument("страница", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> readPage(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "том") - 1,
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "страница") - 1)))));
    }

    /** Chat fallback for a single page, for clients that cannot be handed a book. */
    private static int readPage(ServerCommandSource source, int volume, int page) {
        var vols = dev.bastionauth.core.AgreementManager.volumes();
        if (volume < 0 || volume >= vols.size()) {
            source.sendFeedback(() -> net.minecraft.text.Text.literal("§cНет такого тома."), false);
            return 0;
        }
        var v = vols.get(volume);
        var text = dev.bastionauth.core.AgreementManager.page(volume, page);
        if (text.isEmpty()) {
            source.sendFeedback(() -> net.minecraft.text.Text.literal("§cНет такой страницы."), false);
            return 0;
        }
        int shown = page + 1;
        source.sendFeedback(() -> net.minecraft.text.Text.literal(
                "§6" + v.title() + " §8— страница " + shown + "/" + v.pages().size()), false);
        source.sendFeedback(() -> net.minecraft.text.Text.literal("§f" + text.get()), false);
        return 1;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> adminTree() {
        java.util.function.Predicate<ServerCommandSource> op = src -> CommandManager.ADMINS_CHECK.allows(src.getPermissions());
        return literal("auth")
                // The account's own trail and the panic lock: every player.
                .then(literal("история").executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.showHistory(ctx.getSource().getPlayerOrThrow());
                    return 1;
                }))
                .then(literal("history").executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.showHistory(ctx.getSource().getPlayerOrThrow());
                    return 1;
                }))
                .then(literal("lock").executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.panicLock(ctx.getSource().getPlayerOrThrow());
                    return 1;
                }))
                .then(literal("2fa").requires(op).then(literal("reset").then(argument("ник", word()).executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.adminTwoFactorReset(ctx.getSource(), getString(ctx, "ник"));
                    return 1;
                }))))
                .then(literal("unregister").requires(op).then(argument("ник", word()).executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.adminUnregister(ctx.getSource(), getString(ctx, "ник"));
                    return 1;
                })))
                .then(literal("unlock").requires(op).then(argument("ник", word()).executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.adminUnlock(ctx.getSource(), getString(ctx, "ник"));
                    return 1;
                })))
                .then(literal("status").requires(op).then(argument("ник", word()).executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.adminStatus(ctx.getSource(), getString(ctx, "ник"));
                    return 1;
                })))
                .then(literal("links").requires(op).then(argument("ник", word()).executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.adminLinks(ctx.getSource(), getString(ctx, "ник"));
                    return 1;
                })))
                .then(literal("sessions").requires(op).then(literal("clear").executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.adminSessionsClear(ctx.getSource());
                    return 1;
                })))
                .then(literal("agreement").requires(op).then(literal("stats").executes(ctx -> {
                    var db = dev.bastionauth.core.AgreementManager.VERSION;
                    ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal(
                            "§7Действующая редакция: §f" + dev.bastionauth.core.AgreementManager.REVISION
                                    + " §8(" + db + ")§7, томов: §f"
                                    + dev.bastionauth.core.AgreementManager.volumes().size()
                                    + "§7, страниц: §f" + dev.bastionauth.core.AgreementManager.totalPages()), false);
                    return 1;
                })))
                .then(literal("reload").requires(op).executes(ctx -> {
                    AuthManager m = BastionAuth.manager();
                    if (m == null) return 0;
                    m.adminReload(ctx.getSource());
                    return 1;
                }));
    }

    private AuthCommands() {}
}
