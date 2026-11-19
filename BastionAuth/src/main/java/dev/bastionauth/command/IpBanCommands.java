package dev.bastionauth.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.bastionauth.BastionAuth;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Address bans by account, the account offline too (1.15, owner 12.11):
 * <pre>
 *   /банip &lt;ник&gt; [срок] [причина]      every address the account was seen on; no term = forever
 *   /разбанip &lt;ник&gt;
 * </pre>
 * Terms: 30м, 12ч, 3д, 2н, 1мес, 1г, навсегда (English: m, h, d, w, mo, y, forever).
 */
public final class IpBanCommands {

    private IpBanCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        for (String name : new String[]{"банip", "банип", "ipban", "banip"}) dispatcher.register(ban(name));
        for (String name : new String[]{"разбанip", "разбанип", "ipunban", "unbanip"}) dispatcher.register(unban(name));
    }

    private static boolean admin(ServerCommandSource src) {
        return CommandManager.ADMINS_CHECK.allows(src.getPermissions());
    }

    private static LiteralArgumentBuilder<ServerCommandSource> ban(String name) {
        return literal(name).requires(IpBanCommands::admin)
                .then(argument("ник", StringArgumentType.word())
                        .executes(ctx -> ban(ctx.getSource(), StringArgumentType.getString(ctx, "ник"), ""))
                        .then(argument("срок_и_причина", StringArgumentType.greedyString())
                                .executes(ctx -> ban(ctx.getSource(), StringArgumentType.getString(ctx, "ник"),
                                        StringArgumentType.getString(ctx, "срок_и_причина")))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> unban(String name) {
        return literal(name).requires(IpBanCommands::admin)
                .then(argument("ник", StringArgumentType.word())
                        .executes(ctx -> {
                            String who = ctx.getSource().getName();
                            ctx.getSource().sendFeedback(() -> Text.literal(
                                    BastionAuth.liftAddressBans(StringArgumentType.getString(ctx, "ник"), who)), true);
                            return 1;
                        }));
    }

    private static int ban(ServerCommandSource src, String target, String rest) {
        Term t = Term.parse(rest);
        String reason = t.rest().isBlank() ? "без причины" : t.rest();
        String report = BastionAuth.banAddresses(target, t.minutes(), reason, src.getName());
        src.sendFeedback(() -> Text.literal(report), true);
        return 1;
    }

    /** "3д читы" -> 4320 min + "читы"; no term -> forever (-1). */
    record Term(long minutes, String rest) {
        static Term parse(String text) {
            String s = text == null ? "" : text.trim();
            if (s.isEmpty()) return new Term(-1, "");
            String[] parts = s.split("\\s+", 2);
            String head = parts[0].toLowerCase(java.util.Locale.ROOT);
            String tail = parts.length > 1 ? parts[1] : "";
            if (head.startsWith("навсегда") || head.equals("forever") || head.startsWith("perm")) return new Term(-1, tail);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)([a-zа-яё]*)$").matcher(head);
            if (!m.matches()) return new Term(-1, s);
            long n = Long.parseLong(m.group(1));
            String u = m.group(2);
            long mult;
            if (u.isEmpty() || u.startsWith("м") && !u.startsWith("мес") || u.equals("m") || u.startsWith("min")) mult = 1;
            else if (u.startsWith("ч") || u.startsWith("h")) mult = 60;
            else if (u.startsWith("д") || u.startsWith("сут") || u.startsWith("d")) mult = 1440;
            else if (u.startsWith("н") || u.startsWith("w")) mult = 10080;
            else if (u.startsWith("мес") || u.startsWith("mo")) mult = 43200;
            else if (u.startsWith("г") || u.startsWith("л") || u.startsWith("y")) mult = 525600;
            else return new Term(-1, s);
            return new Term(n * mult, tail);
        }
    }
}
