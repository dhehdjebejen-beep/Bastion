package dev.bastionauth.core;

import java.util.Locale;
import java.util.Set;

/**
 * Hides the secret arguments of the auth commands in the command text other
 * mods may log (1.13). See {@code ServerPlayNetworkHandlerMixin}.
 */
public final class CommandMask {

    /** Commands whose arguments are a password, a code or the code word. */
    static final Set<String> SECRET_ROOTS = Set.of("login", "l", "register", "reg", "changepassword", "changepw",
            "2fa", "codeword", "слово");

    private CommandMask() {}

    /** "login hunter2" becomes "login ***"; anything else comes back as it was. */
    public static String mask(String command) {
        if (command == null) return null;
        boolean slash = command.startsWith("/");
        String body = slash ? command.substring(1) : command;
        int space = body.indexOf(' ');
        if (space < 0) return command;
        String root = body.substring(0, space).toLowerCase(Locale.ROOT);
        if (!SECRET_ROOTS.contains(root)) return command;
        return (slash ? "/" : "") + body.substring(0, space) + " ***";
    }
}
