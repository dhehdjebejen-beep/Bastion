package dev.bastionauth.core;

import dev.bastionauth.BastionAuth;
import dev.bastionauth.mixin.InteractionManagerInvoker;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spectator until the password (1.11).
 *
 * <h2>Why</h2>
 * {@link StateMask} drew a survival player on the login screen, but the tab
 * list still carried the account's real game mode — the client reads its own
 * mode from there too. Rather than chase every packet that names a mode, the
 * player simply <em>is</em> a spectator until proven: the join packet, the tab
 * list and the abilities all say so, the HUD has no hearts, armour or hotbar to
 * leak, nobody can hurt or see them, and the freeze holds as before.
 *
 * <h2>The real mode is never lost</h2>
 * It is switched before the join packet without a packet, kept here, written
 * back for every save of the player (the autosave and the one on disconnect),
 * and restored on login. A saved mode that is spectator or unknown comes back
 * as survival: the server is a survival server, and the gate must never become
 * a way to keep spectator.
 *
 * <h2>Not while the agreement is owed</h2>
 * A spectator cannot hold or use items, and the agreement volumes are read by
 * right-clicking them in the hotbar. So a connection that still owes the
 * agreement waits as before (the plain survival picture) and becomes a
 * spectator the moment it accepts.
 */
public final class SpectatorGate {

    private record Modes(GameMode mode, GameMode previous) {}

    private static final Map<UUID, Modes> REAL = new ConcurrentHashMap<>();

    private SpectatorGate() {}

    private static boolean enabled() {
        AuthManager m = BastionAuth.manager();
        return m != null && m.config().protection.spectatorBeforeLogin;
    }

    /** Whether this player is held as a spectator right now. */
    public static boolean active(ServerPlayerEntity p) {
        return p != null && REAL.containsKey(p.getUuid());
    }

    private static boolean agreementOnFile(ServerPlayerEntity p) {
        AuthManager m = BastionAuth.manager();
        return m != null && m.hasAcceptedAgreement(p);
    }

    /** What the player goes back to: their own mode, or survival. */
    private static GameMode restored(Modes real) {
        return real.mode() == null || real.mode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : real.mode();
    }

    private static void silently(ServerPlayerEntity p, GameMode mode, GameMode previous) {
        ((InteractionManagerInvoker) p.interactionManager).bastionauth$setGameMode(mode, previous);
    }

    /**
     * At the very start of {@code onPlayerConnect}: nothing has been sent yet,
     * so the switch needs no packet and the join packet already says spectator.
     */
    public static void atConnect(ServerPlayerEntity p) {
        if (!enabled() || p == null || REAL.containsKey(p.getUuid())) return;
        try {
            if (!agreementOnFile(p)) return;
            GameMode mode = p.interactionManager.getGameMode();
            REAL.put(p.getUuid(), new Modes(mode, p.interactionManager.getPreviousGameMode()));
            silently(p, GameMode.SPECTATOR, mode);
        } catch (Throwable t) {
            REAL.remove(p.getUuid());
            BastionAuth.LOGGER.warn("Could not hold {} as a spectator: {}", p.getGameProfile().name(), t.toString());
        }
    }

    /** For a player already in the world: /logout, the agreement just accepted. */
    public static void enter(ServerPlayerEntity p) {
        if (!enabled() || p == null || REAL.containsKey(p.getUuid())) return;
        try {
            if (!agreementOnFile(p)) return;
            REAL.put(p.getUuid(), new Modes(p.interactionManager.getGameMode(), p.interactionManager.getPreviousGameMode()));
            p.changeGameMode(GameMode.SPECTATOR);
        } catch (Throwable t) {
            BastionAuth.LOGGER.warn("Could not hold {} as a spectator: {}", p.getGameProfile().name(), t.toString());
        }
    }

    /** The account is proven: back to the real mode, announced to everybody as any change is. */
    public static void leave(ServerPlayerEntity p) {
        if (p == null) return;
        Modes real = REAL.remove(p.getUuid());
        if (real == null) return;
        GameMode target = restored(real);
        try {
            p.changeGameMode(target);
            // The previous mode is the server's business (F3+F4); put back silently.
            silently(p, target, real.previous());
        } catch (Throwable t) {
            BastionAuth.LOGGER.error("Could not give {} their game mode back: {}", p.getGameProfile().name(), t.toString());
        }
    }

    /** Before a save: the real mode, silently. */
    public static void beforeSave(ServerPlayerEntity p) {
        Modes real = p == null ? null : REAL.get(p.getUuid());
        if (real != null) silently(p, restored(real), real.previous());
    }

    /** After a save: spectator again, silently. */
    public static void afterSave(ServerPlayerEntity p) {
        Modes real = p == null ? null : REAL.get(p.getUuid());
        if (real != null) silently(p, GameMode.SPECTATOR, restored(real));
    }

    /** Leaving without the password: the real mode goes back for good, before the final save. */
    public static void onRemove(ServerPlayerEntity p) {
        if (p == null) return;
        Modes real = REAL.remove(p.getUuid());
        if (real != null) silently(p, restored(real), real.previous());
    }
}
