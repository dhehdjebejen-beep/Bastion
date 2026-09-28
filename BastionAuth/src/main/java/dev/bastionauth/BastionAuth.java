package dev.bastionauth;

import dev.bastionauth.command.AuthCommands;
import dev.bastionauth.config.AuthConfig;
import dev.bastionauth.core.AuthManager;
import dev.bastionauth.crypto.Secrets;
import dev.bastionauth.db.Database;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point (dedicated servers only). Wires the {@link AuthManager} into
 * lifecycle, connection, tick and interaction events. The packet-level
 * enforcement lives in the mixins under {@code dev.bastionauth.mixin}.
 */
public final class BastionAuth implements DedicatedServerModInitializer {
    public static final String MOD_ID = "bastionauth";
    public static final Logger LOGGER = LoggerFactory.getLogger("BastionAuth");

    private static volatile AuthManager manager;

    public static AuthManager manager() {
        return manager;
    }

    /**
     * The client brand string this connection announced ("vanilla", "fabric",
     * or whatever a modified launcher sends), lower-case, or null when it has
     * not arrived. Exposed for BastionAC: the brand payload lands during the
     * configuration phase, before a player object exists, so the anti-cheat
     * cannot capture it on its own.
     */
    public static String clientBrand(ServerPlayerEntity player) {
        try {
            return dev.bastionauth.core.DeviceFingerprint.brandOf(player);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether the twin analysis links these two accounts at LIKELY or above.
     * Exposed for MaxCore's economy (alt-funnelling guard); false whenever
     * authentication is not running, so an absent mod never blocks a payment.
     */
    public static boolean accountsLinked(java.util.UUID a, java.util.UUID b) {
        AuthManager m = manager;
        if (m == null) return false;
        try {
            return m.accountsLinked(a, b, "LIKELY");
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 1.8: the link register, exposed for BastionAC's admin panel
    //
    // Everything here speaks in Strings and primitives on purpose. The panel
    // lives in another mod and must keep working when this one is absent or
    // older, so the bridge carries no types of ours across the boundary.
    // ------------------------------------------------------------------

    /** Field separator inside an encoded ledger row. Never appears in a name, uuid or note. */
    public static final String LINK_FIELD_SEP = "\u0001";

    /**
     * The link ledger as flat rows, unreviewed pairs first:
     * {@code uuidA|nameA|uuidB|nameB|bestScore|bestGrade|bestSignals|verdict|verdictBy|verdictAt|verdictNote|firstSeen|lastSeen|times},
     * joined with {@link #LINK_FIELD_SEP}. Pass a uuid to get only that
     * account's pairs, or null for the whole register.
     */
    public static java.util.List<String> linkLedger(String uuidOrNull, int limit) {
        AuthManager m = manager;
        if (m == null) return java.util.List.of();
        try {
            java.util.List<dev.bastionauth.db.Database.LinkLedgerRow> rows = uuidOrNull == null
                    ? m.allLinks(limit)
                    : m.linksOf(java.util.UUID.fromString(uuidOrNull));
            java.util.List<String> out = new java.util.ArrayList<>(rows.size());
            for (dev.bastionauth.db.Database.LinkLedgerRow r : rows) {
                out.add(String.join(LINK_FIELD_SEP,
                        nz(r.uuidA()), nz(r.nameA()), nz(r.uuidB()), nz(r.nameB()),
                        String.valueOf(r.bestScore()), nz(r.bestGrade()), nz(r.bestSignals()),
                        nz(r.verdict()), nz(r.verdictBy()), String.valueOf(r.verdictAt()), nz(r.verdictNote()),
                        String.valueOf(r.firstSeen()), String.valueOf(r.lastSeen()), String.valueOf(r.times())));
                if (out.size() >= limit) break;
            }
            return out;
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** The standing decision about a pair: NEW / TRUSTED / WATCH / ALT. */
    public static String linkVerdict(String uuidA, String uuidB) {
        AuthManager m = manager;
        if (m == null) return "NEW";
        try {
            return m.linkVerdict(java.util.UUID.fromString(uuidA), java.util.UUID.fromString(uuidB)).name();
        } catch (Throwable t) {
            return "NEW";
        }
    }

    /**
     * Files a decision about a pair, creating the ledger row when the analysis
     * never made one. {@code verdict} is a {@link dev.bastionauth.core.LinkVerdict}
     * name; anything unknown is refused rather than silently read as NEW.
     */
    public static boolean setLinkVerdict(String uuidA, String uuidB, String verdict, String by, String note) {
        AuthManager m = manager;
        if (m == null) return false;
        try {
            dev.bastionauth.core.LinkVerdict v =
                    dev.bastionauth.core.LinkVerdict.valueOf(verdict.trim().toUpperCase(java.util.Locale.ROOT));
            return m.setLinkVerdict(java.util.UUID.fromString(uuidA), java.util.UUID.fromString(uuidB), v, by, note);
        } catch (Throwable t) {
            return false;
        }
    }

    /** One decision applied to every pair an account is part of. Returns rows changed. */
    public static int setVerdictForAllLinksOf(String uuid, String verdict, String by, String note) {
        AuthManager m = manager;
        if (m == null) return 0;
        try {
            dev.bastionauth.core.LinkVerdict v =
                    dev.bastionauth.core.LinkVerdict.valueOf(verdict.trim().toUpperCase(java.util.Locale.ROOT));
            return m.setVerdictForAllLinksOf(java.util.UUID.fromString(uuid), v, by, note);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The registered uuid behind a name, or an empty string. Case-insensitive. */
    public static String uuidOfName(String name) {
        AuthManager m = manager;
        if (m == null) return "";
        try {
            java.util.UUID id = m.uuidOfName(name);
            return id == null ? "" : id.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** How many strong pairs nobody has ruled on yet. */
    public static int unreviewedLinks() {
        AuthManager m = manager;
        if (m == null) return 0;
        try {
            return m.unreviewedLinks();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Whether the two accounts count as one person for a benefit paid by the
     * state — the referral bonus. Unlike {@link #accountsLinked},
     * a TRUSTED verdict does not clear the pair here: trust lets two accounts
     * deal with each other, it does not let one person be paid twice.
     */
    public static boolean accountsLinkedForStateBenefits(java.util.UUID a, java.util.UUID b) {
        AuthManager m = manager;
        if (m == null) return false;
        try {
            return m.accountsLinkedForStateBenefits(a, b, "LIKELY");
        } catch (Throwable t) {
            return false;
        }
    }

    /** A codeword is set on this account and has not been spoken lately — the caller should refuse. */
    public static boolean codewordProtects(java.util.UUID uuid) {
        AuthManager m = manager;
        return m != null && uuid != null && m.codeword().protects(uuid);
    }

    /** Whether the account has a codeword at all (for hints on screens). */
    public static boolean codewordSet(java.util.UUID uuid) {
        AuthManager m = manager;
        return m != null && uuid != null && m.codeword().isSet(uuid);
    }

    /**
     * The account's recent authentication history as flat strings, newest
     * first: {@code "<epochMillis>|<event>|<masked ip>|<detail>"}.
     *
     * <p>Exposed for MaxCore's moderation screens, which show an operator one
     * player profile rather than making them read two mods' commands. The IP
     * is already masked the way the log masks it, so nothing here is more
     * revealing than what the console prints.
     */
    public static java.util.List<String> authHistory(java.util.UUID uuid, int limit) {
        AuthManager m = manager;
        if (m == null || uuid == null) return java.util.List.of();
        try {
            return m.historyLines(uuid, limit);
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    /** Whether the account has a second factor. */
    public static boolean twoFactorEnabled(java.util.UUID uuid) {
        AuthManager m = manager;
        return m != null && uuid != null && m.twoFactor().isEnabled(uuid);
    }

    /** Central gate used by mixins and event handlers. */
    public static boolean isBlocked(ServerPlayerEntity player) {
        AuthManager m = manager;
        return m != null && m.isBlocked(player);
    }

    /**
     * BastionAC bridge, called by the link analysis when a graded account
     * link (LIKELY/CONFIRMED) is confirmed. Carries the signals the grade
     * was built from so the anti-cheat's "Сеть" tab shows WHY the link
     * exists, not just THAT it does. Falls back to the short 4-arg form on
     * older BastionAC builds. Never throws: an absent BastionAC must not
     * affect authentication.
     */
    public static void reportLinkedAccount(String name, String uuid, String withName, String withUuid,
                                           String signals, String grade, String score) {
        try {
            Class<?> cls = Class.forName("dev.bastionac.BastionAC");
            try {
                java.lang.invoke.MethodHandle full = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                        cls, "noteLinkedAccount",
                        java.lang.invoke.MethodType.methodType(void.class,
                                String.class, String.class, String.class, String.class,
                                String.class, String.class, String.class));
                full.invokeExact(name, uuid, withName, withUuid, signals, grade, score);
            } catch (NoSuchMethodException olderBuild) {
                // Older BastionAC: the link without the signal breakdown.
                java.lang.invoke.MethodHandle shortForm = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                        cls, "noteLinkedAccount",
                        java.lang.invoke.MethodType.methodType(void.class,
                                String.class, String.class, String.class, String.class));
                shortForm.invokeExact(name, uuid, withName, withUuid);
            }
        } catch (Throwable t) {
            // BastionAC absent or older — a signal we can live without.
        }
    }

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
                Files.createDirectories(dir);
                Path configFile = dir.resolve("config.json");
                dev.bastionauth.core.AgreementManager.load();
                AuthConfig cfg = AuthConfig.loadOrCreate(configFile, warning -> LOGGER.warn("{}", warning));
                Secrets secrets = Secrets.loadOrCreate(dir.resolve("secret.key"));
                Database db = new Database(dir.resolve("auth.db"));
                manager = new AuthManager(server, configFile, cfg, secrets, db);
                if (server.isOnlineMode()) {
                    LOGGER.warn("Server runs in ONLINE mode; BastionAuth still enforces its own login (defence in depth).");
                }
                LOGGER.info("BastionAuth initialised, {} registered account(s)", db.countUsers());
            } catch (Exception e) {
                // Fail closed: a reachable server without authentication is worse than no server.
                throw new IllegalStateException("BastionAuth failed to initialise — stopping the server "
                        + "instead of running without authentication", e);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AuthManager m = manager;
            manager = null;
            if (m != null) m.shutdown();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            AuthManager m = manager;
            if (m != null) m.onJoin(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            AuthManager m = manager;
            if (m != null) m.onDisconnect(handler.player);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            AuthManager m = manager;
            if (m != null) m.tick();
        });

        // Belt-and-braces on top of the packet mixins.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity p && isBlocked(p)));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (isBlocked(sender)) {
                AuthManager m = manager;
                if (m != null) m.warnChatBlocked(sender);
                return false;
            }
            return true;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                player instanceof ServerPlayerEntity p && isBlocked(p) ? ActionResult.FAIL : ActionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                player instanceof ServerPlayerEntity p && isBlocked(p) ? ActionResult.FAIL : ActionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
                player instanceof ServerPlayerEntity p && isBlocked(p)
                        && !dev.bastionauth.core.PacketFirewall.isAgreementBookUse(p, hand)
                        ? ActionResult.FAIL : ActionResult.PASS);
        // A respawn replaces the player entity: the freeze anchor and the
        // invisibility effect must move to the new one.
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) -> {
                    AuthManager m = manager;
                    if (m != null) m.onRespawn(newPlayer);
                });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                player instanceof ServerPlayerEntity p && isBlocked(p) ? ActionResult.FAIL : ActionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                player instanceof ServerPlayerEntity p && isBlocked(p) ? ActionResult.FAIL : ActionResult.PASS);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                AuthCommands.register(dispatcher));

        LOGGER.info("BastionAuth loaded");
    }
}
