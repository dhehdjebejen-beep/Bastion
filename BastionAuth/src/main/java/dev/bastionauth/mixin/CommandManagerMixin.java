package dev.bastionauth.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import dev.bastionauth.BastionAuth;
import dev.bastionauth.core.AuthManager;
import dev.bastionauth.core.ChatGate;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command gate at the dispatcher level (covers both signed and unsigned
 * command packets without touching the chat signature chain): players who are
 * not authenticated may only run the login/register whitelist.
 */
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {

    /**
     * A command run by a player who has not logged in (only the auth
     * whitelist gets that far) answers them in BastionAuth's voice: its
     * feedback, its errors, vanilla's "incomplete command" — all pass the
     * {@link ChatGate}. Lines it causes for anybody else still wait.
     */
    @WrapMethod(method = "execute")
    private void bastionauth$speakToFrozenSender(ParseResults<ServerCommandSource> parseResults, String command,
                                                Operation<Void> original) {
        AuthManager m = BastionAuth.manager();
        if (m == null || !(parseResults.getContext().getSource().getEntity() instanceof ServerPlayerEntity player)
                || !m.isBlocked(player)) {
            original.call(parseResults, command);
            return;
        }
        java.util.UUID previous = ChatGate.enter(player.getUuid());
        try {
            original.call(parseResults, command);
        } finally {
            ChatGate.exit(previous);
        }
    }

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void bastionauth$execute(ParseResults<ServerCommandSource> parseResults, String command, CallbackInfo ci) {
        AuthManager m = BastionAuth.manager();
        if (m == null) return;
        ServerCommandSource source = parseResults.getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) return;
        if (!m.isBlocked(player)) return;
        if (!m.isCommandWhitelisted(command)) {
            m.warnCommandBlocked(player);
            ci.cancel();
            return;
        }
        // Whitelisted auth command (/login, /register, ...). If it did not parse
        // into an executable command — missing/extra argument, or a space in the
        // password — vanilla echoes the full raw text (password included) as an
        // "Unknown or incomplete command" line in latest.log. Cancel before that
        // happens; the player simply retries with correct syntax.
        if (parseResults.getContext().getCommand() == null || parseResults.getReader().canRead()) {
            // A mistyped agreement command deserves the exact syntax, not the
            // generic "log in first" — that is the line that sends a player
            // in circles between /login and /agreement.
            String root = command.startsWith("/") ? command.substring(1) : command;
            int sp = root.indexOf(' ');
            root = (sp >= 0 ? root.substring(0, sp) : root).toLowerCase(java.util.Locale.ROOT);
            if (root.equals("agreement") || root.equals("соглашение")) {
                dev.bastionauth.core.ChatGate.say(player, m.text("agreement.syntax"), false);
            } else {
                m.warnCommandBlocked(player);
            }
            ci.cancel();
        }
    }
}
