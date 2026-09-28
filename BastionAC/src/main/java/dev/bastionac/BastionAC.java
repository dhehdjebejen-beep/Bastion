package dev.bastionac;

import dev.bastionac.command.ACCommands;
import dev.bastionac.config.ACConfig;
import dev.bastionac.core.Alerts;
import dev.bastionac.core.BanManager;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.HistoryManager;
import dev.bastionac.core.HitboxHistory;
import dev.bastionac.core.PlayerData;
import dev.bastionac.core.ShadowTelemetry;
import dev.bastionac.core.TextFmt;
import dev.bastionac.util.SafeFiles;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * BastionAC — server-side anti-cheat engine (the 2.x rewrite).
 *
 * <p>Core principles: process packets exactly once (server thread), confirm
 * violations through buffers + decaying violation levels instead of single
 * events, know every legitimate movement cause (teleports, knockback,
 * explosions, slime, bubble columns, vehicles, elytra, riptide), and prefer
 * mitigation (setback/cancel) plus staff alerts over any automatic punishment.
 */
public final class BastionAC implements DedicatedServerModInitializer {
    public static final String MOD_ID = "bastionac";
    public static final Logger LOGGER = LoggerFactory.getLogger("BastionAC");

    /**
     * Read from the mod metadata (which Gradle fills from gradle.properties) so
     * the number can never drift from the jar it ships in. It did: this used to
     * be typed by hand in two places and still said 2.0 at version 2.6.1.
     */
    public static final String VERSION = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    private static volatile MinecraftServer server;
    private static volatile ACConfig config = ACConfig.defaults();
    private static volatile Path configFile;
    private static volatile MethodHandle authIsBlocked;
    /** Reflective BastionAuth bridge for the client brand string: String clientBrand(ServerPlayerEntity). */
    private static volatile MethodHandle authClientBrand;
    private static volatile boolean authBridgeDegraded;
    private static volatile long lastAuthBridgeHealthLogMs;
    /** Reflective BastionAuth→AC bridge for confirmed account links: void reportLinkedAccount(String, String, String, String). */
    private static volatile MethodHandle authReportLink;

    private static final ConcurrentHashMap<UUID, PlayerData> DATA = new ConcurrentHashMap<>();

    private static volatile long tick;
    private static volatile long lagGraceUntil;
    private static long lastTickNanos;
    private static volatile long lastTickDurationNanos;

    // ------------------------------------------------------------------
    // Static engine API (used by checks and mixins)
    // ------------------------------------------------------------------

    public static boolean enabled() {
        return server != null;
    }

    /** The live server, for components that need it outside the check flow. */
    public static MinecraftServer server() {
        return server;
    }

    /** Server-execute hook for the Minecraft-free chain-detector layer. */
    public static dev.bastionac.core.AttackChainDetector.MinecraftServerLike serverLike() {
        MinecraftServer srv = server;
        if (srv == null) return null;
        return srv::execute;
    }

    public static long serverTick() {
        return tick;
    }

    /** True shortly after a server-side lag spike — checks pause globally. */
    public static boolean isServerLagging(long atTick) {
        return atTick < lagGraceUntil;
    }

    public static ACConfig config() {
        return config;
    }

    public static PlayerData data(ServerPlayerEntity player) {
        return DATA.get(player.getUuid());
    }

    /**
     * A mod is about to move this player itself and says so — MaxCore's duel
     * arena, on the way in and on the way out. For {@code ticks} a single-packet
     * jump is re-anchored on the server's position instead of being flagged.
     *
     * <p>Why BigMove needs it when an ordinary teleport does not: the arena
     * moves a player in the middle of other things happening to them — the
     * knockout blow that ends a round is still being processed, the kit is
     * being swapped — and the owner kept seeing «рывок-телепорт» on exactly
     * those two moments while every plain /home stayed silent. The grace is
     * short, only the caller can open it, and it re-anchors on where the
     * SERVER put the player, never on what the client claims.
     */
    public static void expectServerMove(ServerPlayerEntity player, int ticks) {
        if (player == null) return;
        PlayerData d = DATA.get(player.getUuid());
        if (d == null) return;
        d.serverMoveGraceUntil = Math.max(d.serverMoveGraceUntil, tick + Math.max(1, Math.min(ticks, 200)));
    }

    /** The client's brand string via BastionAuth, or null when unavailable. */
    public static String clientBrand(ServerPlayerEntity player) {
        MethodHandle handle = authClientBrand;
        if (handle == null) return null;
        try {
            return (String) handle.invokeExact(player);
        } catch (Throwable t) {
            return null;
        }
    }

    /** BastionAuth integration: players frozen before login are not analysed. */
    public static boolean isAuthFrozen(ServerPlayerEntity player) {
        MethodHandle handle = authIsBlocked;
        if (handle == null) return false;
        try {
            return (boolean) handle.invokeExact(player);
        } catch (Throwable t) {
            reportAuthBridgeFailure("invoke " + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * BastionAuth→AC bridge receiver: a graded account-link detection lands
     * in the AC network registry so the "Сеть" tab shows it next to the
     * subnet sightings. Full form: carries the signal breakdown, the grade
     * and the composite score — the UI displays exactly what the link was
     * built from. Called on the auth worker; everything downstream is
     * thread-safe.
     */
    public static void noteLinkedAccount(String name, String uuid, String withName, String withUuid,
                                         String signals, String grade, String score) {
        dev.bastionac.core.NetworkRegistry.noteLinkedAccount(
                name, uuid, withName, withUuid, signals, grade, score);
    }

    /**
     * BastionAuth→AC bridge: a graded account-link detection (name, uuid,
     * candidate name/uuid) lands in the AC network registry so the "Сеть"
     * tab shows it next to the subnet sightings. Best-effort: an absent
     * bridge never breaks the caller.
     */
    public static void reportLinkedAccount(String name, String uuid, String withName, String withUuid) {
        MethodHandle handle = authReportLink;
        if (handle == null) return;
        try {
            handle.invokeExact(name, uuid, withName, withUuid);
        } catch (Throwable t) {
            reportAuthBridgeFailure("link-invoke " + t.getClass().getSimpleName());
        }
    }

    /** Called from the packet hook when the server sends knockback/explosions. */
    public static void grantVelocity(ServerPlayerEntity player, Vec3d velocity) {
        PlayerData d = DATA.get(player.getUuid());
        if (d == null) return;
        double horiz = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double budget = horiz * 1.6 + Math.abs(velocity.y) * 0.6 + 0.1;
        if (budget > d.velocityBudget) d.velocityBudget = budget;
        // Scale the grace with the actual knockback instead of a flat 16 ticks:
        // a strong launch needs the full window to play out, a micro-nudge barely
        // any. Combined with the ground check now staying live (limit widened by
        // the budget), a trickle of micro-knockback can no longer buy a free
        // exemption window.
        int graceTicks = 5 + Math.min(11, (int) Math.ceil(horiz * 6.0));
        d.velocityGraceUntil = Math.max(d.velocityGraceUntil, tick + graceTicks);

        // Anti-KB: arm a verification transaction for meaningful knockback. The
        // expected displacement is the closed-form sum of the decaying geometric
        // series v * (1 - drag^n) / (1 - drag) with the vanilla air drag 0.98,
        // over the verify window. We anchor on the SERVER position (authoritative,
        // not client-spoofable) and require only a fraction of the expected travel,
        // so legitimate counter-movement and lag never convict — only a client
        // that genuinely suppresses the impulse falls short. Micro-nudges below
        // the threshold are ignored: their displacement is under one block and
        // would drown in movement noise.
        ACConfig cfg = config;
        if (cfg.check(CheckType.VELOCITY.key).enabled && horiz >= 0.4
                && !player.isCreative() && !player.isSpectator() && !player.hasVehicle()) {
            int n = cfg.general.velocityVerifyTicks;
            double expected = dev.bastionac.core.MathUtil.knockbackDisplacement(horiz, n, 0.98);
            double minExpected = expected * cfg.general.velocityMinRatio;
            d.beginKbVerify(player.getX(), player.getZ(),
                    velocity.x / horiz, velocity.z / horiz, minExpected, tick + n);
        }
    }

    /**
     * Feeds a movement sample into an armed Anti-KB transaction. Called from the
     * movement check each position packet. Runs the verdict when the window
     * closes; a suppressed knockback accumulates a fail streak, and only a
     * sustained streak flags — a single lag spike landing mid-window never does.
     */
    public static void tickKbVerify(ServerPlayerEntity player, PlayerData d, double x, double z, long tick) {
        if (!d.kbPending) return;
        if (tick < d.kbDeadlineTick) return;
        double dx = x - d.kbAnchorX;
        double dz = z - d.kbAnchorZ;
        double moved = Math.sqrt(dx * dx + dz * dz);
        ACConfig cfg = config;
        d.kbPending = false;
        if (moved + 1.0e-6 < d.kbExpectedMin) {
            // Before counting a failure, rule out the impulse having nowhere to
            // go. A player thrown into a wall, into water or lava, or held by a
            // cobweb/ladder legitimately does not travel — cornering someone is
            // ordinary PvP, and the displacement model (0.98 air drag) simply
            // does not describe it. kbBlocked is set by the movement check for
            // the media; the wall is probed here against real collision shapes,
            // server-side, so a client cannot fake its way out of the test.
            if (d.kbBlocked || wallInPath(player, d)) {
                d.clearKbVerify();
                return;
            }
            d.kbFailStreak++;
            if (d.kbFailStreak >= cfg.general.velocityConfirmStreak) {
                d.kbFailStreak = 0;
                flag(player, d, CheckType.VELOCITY, String.format(
                        "отброс %.2f бл вместо мин. %.2f (%d подряд)",
                        moved, d.kbExpectedMin, cfg.general.velocityConfirmStreak));
            }
        } else {
            // A clean transaction decays the streak rather than clearing it, so
            // alternating lag/cheat windows still converge on a verdict.
            d.kbFailStreak = Math.max(0, d.kbFailStreak - 1);
        }
    }

    /**
     * True when a solid obstruction sits in the path the impulse should have
     * carried the player along. The player's own collision box is swept from its
     * current place toward the knockback direction by the distance the check
     * expected, so any wall, corner or doorway that ate the launch is found.
     */
    private static boolean wallInPath(ServerPlayerEntity player, PlayerData d) {
        double dist = Math.max(0.5, d.kbExpectedMin);
        try {
            return !player.getEntityWorld().isSpaceEmpty(player,
                    player.getBoundingBox().offset(d.kbDirX * dist, 0.0, d.kbDirZ * dist));
        } catch (RuntimeException e) {
            return true; // unloaded chunk edge — never convict on missing data
        }
    }

    /**
     * Registers a confirmed violation: adds VL, logs, alerts staff over the
     * threshold. @return true when the configured mitigation threshold is
     * reached (caller should setback/cancel).
     */
    public static boolean flag(ServerPlayerEntity player, PlayerData d, CheckType type, String details) {
        return flag(player, d, type, details, 1.0);
    }

    /**
     * @param weightMultiplier scales the VL this violation is worth. Lets a
     *        check grade severity — a CPS of 25 is not the same evidence as a
     *        CPS of 15, and waiting the same number of flags for both would
     *        either be too slow for the blatant case or too harsh for the
     *        borderline one.
     */
    public static boolean flag(ServerPlayerEntity player, PlayerData d, CheckType type,
                               String details, double weightMultiplier) {
        ACConfig cfg = config;
        ACConfig.CheckCfg check = cfg.check(type.key);
        if (!check.enabled) return false;
        // Already scheduled for a kick — stop accumulating VL and re-scheduling.
        if (d.punished) return true;

        // ARMED chain: the player's correlated pattern demands stricter
        // thresholds. Applied to the alert threshold only (the staff-facing
        // signal), never to the ban ladder — the chain consequences are
        // handled by the chain layer itself.
        double alertVl = check.alertVl;
        if (dev.bastionac.core.AttackChainDetector.isWatched(player.getUuid())) {
            alertVl *= dev.bastionac.core.AttackChainDetector.severityMultiplier(player.getUuid());
        }
        // Staff and frozen (pre-login) connections are never analysed for
        // punishment; the alert/kick path below still logs the violation.
        boolean staff = BanManager.isExemptOperator(player);

        double vl = d.vl.add(type, check.weight * Math.max(0.0, weightMultiplier));
        if (cfg.general.logViolationsToConsole) {
            LOGGER.info("[VL] {} {} -> {} ({})", d.name, type.key, String.format("%.1f", vl), details);
        }
        // EDR chain feed: a borderline flag below the alert threshold is a
        // probe; reaching the alert threshold is an exploit event. Individually
        // either may be noise — the chain detector is what makes the pattern.
        if (vl >= alertVl) {
            dev.bastionac.core.AttackChainDetector.exploit(
                    new dev.bastionac.core.ChainPlayerAdapter(player), type.key, details);
        } else if (check.weight * Math.max(0.0, weightMultiplier) > 0) {
            dev.bastionac.core.AttackChainDetector.probe(
                    new dev.bastionac.core.ChainPlayerAdapter(player), type.key, details);
        }
        // Freeze the ring-buffer context on the first alert of this check, so
        // the retrospective review sees the packets that led up to it. Later
        // alerts of the same episode extend nothing — the dump is the moment.
        if (vl >= alertVl
                && d.vl.tryAlert(type, System.currentTimeMillis(), 60_000L)) {
            dev.bastionac.core.PlayerContextBuffer.snapshot(player,
                    type.key + " " + String.format(java.util.Locale.ROOT, "%.1f", vl) + " — " + details);
        }
        MinecraftServer srv = server;
        if (srv != null && vl >= alertVl
                && d.vl.tryAlert(type, System.currentTimeMillis(), cfg.general.alertCooldownSeconds * 1000L)) {
            Alerts.send(srv, player, type, vl, details);
            HistoryManager.recordAlert(player, type, vl, details, ShadowTelemetry.evidence(player, d));
            // Evasion watch: a staff alert landing arms the panic-pause pattern.
            dev.bastionac.core.EvasionTracker.onAlert(player);
            // An alert counts toward the progressive auto-tempban.
            if (BanManager.onAlert(player, type)) {
                d.punished = true; // banned — stop piling on this tick
                return true;
            }
        }

        if (cfg.general.kickEnabled && check.kickVl > 0 && vl >= check.kickVl && srv != null) {
            d.punished = true; // one-shot: guarantees a single kick + announcement
            srv.execute(() -> punish(srv, player, type));
            return true;
        }
        if (staff && vl >= alertVl) {
            // Nothing else to escalate for staff; keep the mitigation answer honest.
            return check.mitigateVl > 0 && vl >= check.mitigateVl;
        }

        return check.mitigateVl > 0 && vl >= check.mitigateVl;
    }

    /** Runs on the server thread: announces the kick and disconnects the player.
     *  Called at most once per player thanks to the {@code punished} one-shot. */
    private static void punish(MinecraftServer srv, ServerPlayerEntity player, CheckType type) {
        if (player.networkHandler == null) return;
        ACConfig.Messages m = config.messages;
        String name = player.getGameProfile().name();
        PlayerData d = DATA.get(player.getUuid());
        if (d != null) d.kickedByAc = true;
        LOGGER.warn("[KICK] {} kicked by check {}", name, type.key);
        HistoryManager.recordPunishment(HistoryManager.Kind.KICK, player.getUuid(), name, type.key, 0, "авто-кик по VL");
        if (m.announceKickToEveryone) {
            srv.getPlayerManager().broadcast(
                    TextFmt.applied(m.kickBroadcast, Map.of("name", name, "check", type.key)), false);
        }
        String screen = m.revealCheckToPlayer ? m.kickScreen + "\n&8(" + type.key + ")" : m.kickScreen;
        player.networkHandler.disconnect(TextFmt.applied(screen, Map.of("name", name)));
    }

    /** Pushes the punishment policy into the chain detector (config load / reload / GUI edit). */
    public static void applyChainPolicy() {
        ACConfig.Punishment p = config.punishment;
        dev.bastionac.core.AttackChainDetector.configure(p.chainWindowMinutes, p.chainWatchDistinctChecks,
                p.chainBanDistinctChecks, p.banExcludedChecks);
    }

    public static void reloadConfig(Consumer<String> feedback) {
        try {
            config = ACConfig.loadOrCreate(configFile, warning -> LOGGER.warn("{}", warning));
            applyChainPolicy();
            feedback.accept("§a[BAC] Конфигурация перезагружена");
        } catch (Exception e) {
            LOGGER.error("Config reload failed", e);
            feedback.accept("§c[BAC] Ошибка перезагрузки: " + e.getMessage());
        }
    }

    /**
     * Persists the current in-memory config to disk. Because checks read
     * {@link #config()} live every tick, mutating a field already takes effect
     * instantly; this just makes the change survive a restart.
     */
    public static void saveConfig() {
        Path f = configFile;
        ACConfig cfg = config;
        if (f == null || cfg == null) return;
        try {
            cfg.saveTo(f);
            applyChainPolicy();
        } catch (Exception e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    /** Resets a player's accumulated violation levels (admin action). */
    public static boolean clearViolations(ServerPlayerEntity player) {
        PlayerData d = DATA.get(player.getUuid());
        if (d == null) return false;
        d.vl.clearAll();
        d.punished = false;
        return true;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTING.register(srv -> {
            try {
                Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
                Files.createDirectories(dir);
                configFile = dir.resolve("config.json");
                config = ACConfig.loadOrCreate(configFile, warning -> LOGGER.warn("{}", warning));
            } catch (Exception e) {
                LOGGER.error("Failed to load config, using defaults", e);
                config = ACConfig.defaults();
            }
            resolveAuthIntegration();
            applyChainPolicy();
            try {
                Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
                BanManager.init(srv, dir);
                HistoryManager.init(dir);
                dev.bastionac.core.PlayerContextBuffer.init(FabricLoader.getInstance().getGameDir());
            } catch (Exception e) {
                LOGGER.error("Failed to init BanManager/HistoryManager", e);
            }
            server = srv;
            LOGGER.info("BastionAC active: {} checks, alerts {}, setback {}, auto-tempban {}",
                    CheckType.values().length,
                    config.general.alertsToOps ? "on" : "off",
                    config.general.setbackEnabled ? "on" : "off",
                    config.punishment.autoTempbanEnabled ? "on" : "off");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            server = null;
            BanManager.shutdown();
            HistoryManager.saveNow(); // blocking on purpose — the process is going away
            SafeFiles.flush();        // drain anything the I/O thread still holds
            DATA.clear();
        });

        // Authoritative damage veto: guarantees a flagged combat hit deals no
        // damage even if the packet cancel is bypassed.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            // NoFall evidence: remember that vanilla actually tried to charge
            // this player for a fall. The check compares the descent it
            // measured itself against whether this ever fired — an outcome
            // test, so it does not care how the client faked its landing.
            if (entity instanceof ServerPlayerEntity victim
                    && source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_FALL)) {
                PlayerData vd = DATA.get(victim.getUuid());
                if (vd != null) vd.lastFallDamageTick = tick;
            }
            if (source.getAttacker() instanceof ServerPlayerEntity attacker) {
                PlayerData d = DATA.get(attacker.getUuid());
                // Same-tick match for melee (damage is applied synchronously in the
                // attack packet handler), with a 1-tick slack so a hit whose damage
                // resolves on the next tick is still vetoed rather than slipping.
                if (d != null) {
                    long since = tick - d.attackVetoTick;
                    if (since >= 0 && since <= 1) return false;
                }
            }
            return true;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayerEntity player = handler.player;
            PlayerData d = new PlayerData(player.getUuid(), player.getGameProfile().name());
            d.joinGraceUntil = tick + config.general.joinGraceTicks;
            d.lastWorld = player.getEntityWorld();
            d.resetMovement(player.getX(), player.getY(), player.getZ());
            DATA.put(player.getUuid(), d);
            dev.bastionac.core.PlayerContextBuffer.attach(player);
            dev.bastionac.core.ReconTracker.attach(player);
            dev.bastionac.core.EvasionTracker.attach(player);
            dev.bastionac.core.XrayTracker.attach(player, d);
            dev.bastionac.core.NetworkRegistry.noteName(player.getUuid(), player.getGameProfile().name());
            dev.bastionac.core.NetworkRegistry.noteJoin(player);
            // Staff signal (never an action): the joiner's subnet matches a
            // recent ban's subnet. Checked after the join completes.
            srv.execute(() -> dev.bastionac.core.NetworkRegistry.checkJoinOnBannedIp(player));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            ServerPlayerEntity player = handler.player;
            PlayerData leaving = DATA.get(player.getUuid());
            DATA.remove(player.getUuid());
            Alerts.forget(player.getUuid());
            HitboxHistory.remove(player.getId());
            dev.bastionac.core.PlayerContextBuffer.forget(player.getUuid());
            dev.bastionac.checks.PacketGuard.forget(player.getUuid());
            dev.bastionac.core.ReconTracker.forget(player.getUuid());
            dev.bastionac.core.EvasionTracker.forget(player.getUuid());
            dev.bastionac.core.AttackRate.forget(player.getUuid());
            // Context signal only (never a ban input): the player closed the
            // connection themselves in the same tick a kick was scheduled but
            // before it ran. Our own kick is not evasion — that mistake turned
            // every kick into a week-long ban.
            if (leaving != null && leaving.punished && !leaving.kickedByAc) {
                dev.bastionac.core.AttackChainDetector.evasion(
                        new dev.bastionac.core.ChainPlayerAdapter(player),
                        "left-before-kick", "closed the connection while a kick was scheduled");
            }
            // The chain itself deliberately survives the rejoin — see
            // AttackChainDetector's javadoc. It is pruned by time, not by
            // disconnect, so a relog cannot reset the score.
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            PlayerData d = DATA.get(newPlayer.getUuid());
            if (d == null) return;
            d.respawnGraceUntil = tick + config.general.joinGraceTicks;
            d.lastWorld = newPlayer.getEntityWorld();
            d.teleportPending = false;
            d.clearMining(); // a break armed before dying can never be closed
            d.resetMovement(newPlayer.getX(), newPlayer.getY(), newPlayer.getZ());
            d.resetMovePackets();
        });

        // Dimension travel (nether/End portals, End exit) repositions the
        // player without a requestTeleport, so movement checks would otherwise
        // see a huge single-packet jump. Reset and grant a solid grace here —
        // this is what killed the "BigMove on End exit" false positive.
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            PlayerData d = DATA.get(player.getUuid());
            if (d == null) return;
            d.respawnGraceUntil = tick + 100;
            d.lastWorld = destination;
            d.teleportPending = false;
            d.clearMining();
            d.resetMovement(player.getX(), player.getY(), player.getZ());
            d.resetMovePackets();
        });

        // Begin before C2S packet handlers execute in this tick. This only resets
        // counters for the shadow telemetry; the anti-cheat tick cadence remains
        // unchanged and all enforcement stays in its existing paths.
        ServerTickEvents.START_SERVER_TICK.register(srv ->
                ShadowTelemetry.beginTick(tick + 1, lastTickDurationNanos));

        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            tick++;
            long nano = System.nanoTime();
            lastTickDurationNanos = lastTickNanos == 0 ? 0 : nano - lastTickNanos;
            if (lastTickDurationNanos > 80_000_000L) {
                // The server itself lagged; client packets will burst — pause.
                lagGraceUntil = tick + 40;
            }
            lastTickNanos = nano;

            HitboxHistory.snapshot(srv);
            HistoryManager.tick(tick);

            // Staff-presence feed for the evasion tracker: every admin join
            // or approach arms the "collapse" watch on busy players. Cheap —
            // the nearby-variant only runs when admins are online at all.
            if (tick % 20 == 0) {
                List<ServerPlayerEntity> staff = new java.util.ArrayList<>();
                for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                    if (dev.bastionac.core.Alerts.isAdmin(p) && !isAuthFrozen(p)) staff.add(p);
                }
                if (!staff.isEmpty()) {
                    for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                        if (dev.bastionac.core.Alerts.isAdmin(p)) continue;
                        for (ServerPlayerEntity admin : staff) {
                            dev.bastionac.core.EvasionTracker.onStaffNearby(p, admin);
                        }
                        dev.bastionac.core.EvasionTracker.evaluate(p);
                    }
                }
            }

            if (tick % 1200 == 0) {
                BanManager.sweep(System.currentTimeMillis());
            }

            // Totem pops, read off the server's own use statistic every tick:
            // AutoTotem is judged by how soon after one the off hand refills.
            for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                PlayerData pd = DATA.get(p.getUuid());
                if (pd != null) dev.bastionac.checks.ClientChecks.tickTotem(p, pd);
            }

            // Brand/channel consistency: one verdict per session, a few
            // seconds in, once the client has declared what it can receive.
            if (tick % 20 == 0) {
                for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                    PlayerData pd = DATA.get(p.getUuid());
                    if (pd == null || pd.brandChecked) continue;
                    if (pd.brand == null) pd.brand = clientBrand(p);
                    dev.bastionac.checks.ClientChecks.checkBrand(p, pd);
                }
            }

            if (tick % 20 == 0) {
                ACConfig cfg = config;
                for (PlayerData d : DATA.values()) {
                    for (CheckType type : CheckType.values()) {
                        d.vl.decay(cfg.check(type.key).decayPerSecond, 1.0, type);
                    }
                }
            }

            // Alert windows of players who left (or simply went quiet) are pruned
            // by age, never on disconnect: the window has to survive a rejoin or
            // a cheater one alert short of the ban threshold just relogs.
            // Chain maps follow the same policy now (AttackChainDetector.sweep).
            if (tick % 1200 == 0) {
                BanManager.sweep(System.currentTimeMillis());
                dev.bastionac.core.AttackChainDetector.sweep();
            }

            ACConfig.General general = config.general;
            if (general.shadowTelemetryEnabled && general.shadowConsoleReports
                    && tick % general.shadowReportIntervalTicks == 0) {
                LOGGER.info("[SHADOW] {}", ShadowTelemetry.summary());
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ACCommands.register(dispatcher));

        LOGGER.info("BastionAC {} loaded", VERSION);
    }

    private static void resolveAuthIntegration() {
        if (!FabricLoader.getInstance().isModLoaded("bastionauth")) {
            authIsBlocked = null;
            authReportLink = null;
            authClientBrand = null;
            authBridgeDegraded = false;
            return;
        }
        try {
            Class<?> cls = Class.forName("dev.bastionauth.BastionAuth");
            authIsBlocked = null;
            authReportLink = null;
            authIsBlocked = MethodHandles.publicLookup().findStatic(cls, "isBlocked",
                    MethodType.methodType(boolean.class, ServerPlayerEntity.class));
            // Optional: the client brand, captured by BastionAuth during the
            // configuration phase (before a player object exists, which is why
            // the anti-cheat cannot read it itself). Absent on older builds.
            try {
                authClientBrand = MethodHandles.publicLookup().findStatic(cls, "clientBrand",
                        MethodType.methodType(String.class, ServerPlayerEntity.class));
            } catch (ReflectiveOperationException ignored) {
                authClientBrand = null;
            }
            // Optional bridge: BastionAuth exposes reportLinkedAccount so its
            // graded link detections reach the AC network registry (the
            // "Сеть" tab). Absent in older auth builds — degrade silently.
            try {
                authReportLink = MethodHandles.publicLookup().findStatic(cls, "reportLinkedAccount",
                        MethodType.methodType(void.class, String.class, String.class, String.class, String.class));
            } catch (ReflectiveOperationException ignored) {
                authReportLink = null;
            }
            authBridgeDegraded = false;
            LOGGER.info("BastionAuth integration active: frozen players are exempt from checks");
        } catch (ReflectiveOperationException e) {
            authIsBlocked = null;
            authReportLink = null;
            reportAuthBridgeFailure("resolve " + e.getClass().getSimpleName());
        }
    }

    /** Emits a bounded operational health signal; it never changes player policy. */
    private static void reportAuthBridgeFailure(String reason) {
        authBridgeDegraded = true;
        ShadowTelemetry.recordAuthBridgeFailure();
        long now = System.currentTimeMillis();
        if (now - lastAuthBridgeHealthLogMs >= 60_000L) {
            lastAuthBridgeHealthLogMs = now;
            LOGGER.warn("[HEALTH] BastionAuth bridge degraded: {}; pre-login exemption cannot be verified", reason);
        }
    }
}
