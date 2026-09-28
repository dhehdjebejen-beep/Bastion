package dev.bastionauth.core;

import com.mojang.authlib.GameProfile;
import dev.bastionauth.BastionAuth;
import dev.bastionauth.config.AuthConfig;
import dev.bastionauth.crypto.PasswordHasher;
import dev.bastionauth.crypto.Secrets;
import dev.bastionauth.db.Database;
import dev.bastionauth.db.UserRecord;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Heart of the mod: connection gating, the register/login state machine,
 * brute-force limits and IP-bound session resume.
 *
 * <p>Threading model: game state is only touched on the server thread; all
 * Argon2 hashing and SQLite access happens on a small bounded worker pool so
 * expensive operations can never stall the tick loop. Results hop back to the
 * server thread via {@link MinecraftServer#execute}.
 */
public final class AuthManager {
    private static final Set<String> BUILTIN_COMMAND_WHITELIST =
            Set.of("register", "reg", "login", "l", "agreement", "соглашение", "2fa");
    /** A lock this far in the future is the citizen's own panic lock, not a brute-force timeout. */
    private static final long PANIC_LOCK_MS = 3650L * 24 * 60 * 60 * 1000;
    private static final String DEFAULT_NAME_REGEX = "^[A-Za-z0-9_]{3,16}$";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    public record Session(String ipHmac, long expiresAt) {}

    private record PendingIp(String ip, long atMillis) {}

    private final MinecraftServer server;
    private final Path configFile;
    private final Secrets secrets;
    private final Database db;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final ThreadPoolExecutor executor;
    private final TwoFactor twoFactor;
    private final Codeword codeword;

    private volatile AuthConfig cfg;
    private volatile Pattern namePattern;
    private volatile RateLimiters.SlidingWindow joinThrottle;
    private volatile RateLimiters.SlidingWindow ipFailWindow;
    private final RateLimiters.TempBlocks ipBlocks = new RateLimiters.TempBlocks();

    private final ConcurrentHashMap<UUID, PlayerAuthState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> nameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PendingIp> pendingIps = new ConcurrentHashMap<>();
    /** Players whose plugin-channel list is due to be captured (uuid -> server tick). */
    private final ConcurrentHashMap<UUID, Long> channelCaptureDue = new ConcurrentHashMap<>();
    /** Last "packet dropped" debug line per player, so the log is not flooded by a spamming client. */
    private final ConcurrentHashMap<UUID, Long> lastDropLogNanos = new ConcurrentHashMap<>();
    /** Accounts known to have accepted the CURRENT agreement revision. */
    private final java.util.Set<UUID> agreementOk = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private long tickCounter;

    public AuthManager(MinecraftServer server, Path configFile, AuthConfig cfg, Secrets secrets, Database db) throws SQLException {
        this.server = server;
        this.configFile = configFile;
        this.cfg = cfg;
        this.secrets = secrets;
        this.db = db;
        this.hasher = new PasswordHasher(secrets, cfg.hashing.usePepper,
                new PasswordHasher.Params(cfg.hashing.memoryKib, cfg.hashing.iterations, cfg.hashing.parallelism));
        this.policy = new PasswordPolicy(cfg.passwordRules, PasswordPolicy.loadBundledCommonPasswords());
        this.twoFactor = new TwoFactor(db, secrets, cfg.twoFactor.window, cfg.twoFactor.backupCodes);
        this.codeword = new Codeword(db, hasher, cfg.codeword.unlockMinutes);
        this.namePattern = compileNameRegex(cfg.general.usernameRegex);
        rebuildLimiters(cfg);
        this.nameCache.putAll(db.allNames());
        db.pruneAudit(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000);

        // Restore resumable sessions saved at the previous shutdown, so players
        // stay logged in across a restart (still IP-bound and TTL-limited).
        long nowMs = System.currentTimeMillis();
        db.pruneSessions(nowMs);
        for (Database.SessionRow row : db.loadSessions()) {
            try {
                sessions.put(UUID.fromString(row.uuid()), new Session(row.ipHmac(), row.expiresAt()));
            } catch (IllegalArgumentException ignored) {
            }
        }

        AtomicInteger threadNo = new AtomicInteger();
        int threads = cfg.hashing.maxConcurrentHashes;
        this.executor = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(cfg.hashing.maxQueuedOperations),
                r -> {
                    Thread t = new Thread(r, "BastionAuth-Worker-" + threadNo.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    private static Pattern compileNameRegex(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            BastionAuth.LOGGER.warn("Invalid usernameRegex '{}', falling back to default", regex);
            return Pattern.compile(DEFAULT_NAME_REGEX);
        }
    }

    private void rebuildLimiters(AuthConfig cfg) {
        this.joinThrottle = new RateLimiters.SlidingWindow(60_000L, cfg.bruteForce.maxJoinsPerIpPerMinute);
        this.ipFailWindow = new RateLimiters.SlidingWindow(cfg.bruteForce.ipFailureWindowMinutes * 60_000L, cfg.bruteForce.ipMaxFailures);
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Persist a clean snapshot of resumable sessions before closing: every
        // player still authenticated at shutdown, plus sessions of players who
        // left earlier. On next start they can resume from the same IP within TTL.
        AuthConfig cfg = this.cfg;
        if (cfg.session.enabled) {
            db.clearSessions();
            long expiry = System.currentTimeMillis() + cfg.session.ttlMinutes * 60_000L;
            for (PlayerAuthState st : states.values()) {
                if (st.authenticated) db.saveSession(st.uuid.toString(), secrets.ipHmac(st.ip), expiry);
            }
            sessions.forEach((uuid, s) -> db.saveSession(uuid.toString(), s.ipHmac(), s.expiresAt()));
        }
        db.close();
    }

    // ------------------------------------------------------------------
    // Queries used by mixins and events
    // ------------------------------------------------------------------

    /**
     * True while the player must stay frozen (not yet authenticated). A
     * player without a state has not been through {@link #onJoin} yet —
     * vanilla is still inside {@code onPlayerConnect}, sending the command
     * tree and processing the first packets — and is frozen too. Answering
     * "not blocked" for that window handed an impostor under an operator's
     * name the operator's full command tree before any password.
     */
    public boolean isBlocked(ServerPlayerEntity player) {
        PlayerAuthState st = states.get(player.getUuid());
        return st == null || !st.authenticated;
    }

    public boolean isBlockItemPickup() {
        return cfg.protection.blockItemPickup;
    }

    public boolean isCommandWhitelisted(String fullCommand) {
        String cmd = fullCommand.startsWith("/") ? fullCommand.substring(1) : fullCommand;
        int space = cmd.indexOf(' ');
        String root = (space >= 0 ? cmd.substring(0, space) : cmd).toLowerCase(Locale.ROOT);
        if (BUILTIN_COMMAND_WHITELIST.contains(root)) return true;
        for (String extra : cfg.protection.extraCommandWhitelist) {
            if (root.equalsIgnoreCase(extra)) return true;
        }
        return false;
    }

    public void warnCommandBlocked(ServerPlayerEntity player) {
        rateLimitedWarn(player, "command.blocked");
    }

    public void warnChatBlocked(ServerPlayerEntity player) {
        rateLimitedWarn(player, "chat.blocked");
    }

    private void rateLimitedWarn(ServerPlayerEntity player, String key) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null) return;
        long now = System.nanoTime();
        if (now - st.lastWarnNanos < 1_000_000_000L && st.lastWarnNanos != 0) return;
        st.lastWarnNanos = now;
        ChatGate.say(player, text(key), false);
    }

    /**
     * Called from the movement-packet mixin (netty thread). Cancels client
     * drift by scheduling a single snap-back teleport on the server thread.
     */
    public void onMovePacket(ServerPlayerEntity player) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null || st.authenticated) return;
        if (!st.snapPending.compareAndSet(false, true)) return;
        server.execute(() -> {
            st.snapPending.set(false);
            if (st.authenticated || states.get(st.uuid) != st) return;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
            if (p == null) return;
            double dx = p.getX() - st.x;
            double dy = p.getY() - st.y;
            double dz = p.getZ() - st.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > 256 * 256) {
                // The server itself moved the player (portal, /tp) — re-anchor there.
                st.anchor(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
            } else if (distSq > 0.0225) {
                p.networkHandler.requestTeleport(st.x, st.y, st.z, st.yaw, st.pitch);
            }
        });
    }

    // ------------------------------------------------------------------
    // Connection gate (PlayerManager#checkCanJoin mixin, server thread)
    // ------------------------------------------------------------------

    public Text checkCanJoin(SocketAddress address, GameProfile profile) {
        AuthConfig cfg = this.cfg;
        String name = profile.name();
        String ip = ipOf(address);
        long now = System.currentTimeMillis();

        if (name == null || !namePattern.matcher(name).matches()) {
            return text("join.denied.badName");
        }
        String registeredExact = nameCache.get(name.toLowerCase(Locale.ROOT));
        if (registeredExact != null && cfg.general.enforceExactNameCase && !registeredExact.equals(name)) {
            return text("join.denied.caseMismatch", registeredExact);
        }
        // The name is already online. From the SAME address this is the same
        // person whose link dropped: their old socket has not timed out yet
        // (vanilla notices after ~30 s of silence) and refusing them meant
        // "already on the server" on every retry, each retry also spending a
        // join-throttle token. Let vanilla replace the ghost instead (it kicks
        // the old connection and waits for it to go). From a DIFFERENT
        // address the refusal stays: that is the impostor trying to kick a
        // live player, authenticated or still typing their password.
        for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
            if (!online.getGameProfile().name().equalsIgnoreCase(name)) continue;
            PlayerAuthState st = states.get(online.getUuid());
            boolean sameAddress = st != null && st.ip.equals(ip);
            if (!sameAddress) return text("join.denied.online");
            BastionAuth.LOGGER.info("{} reconnected from {} while the old connection lingers — replacing it",
                    name, Secrets.maskIp(ip));
        }
        // Vanilla asks twice per join (login-phase verify, then again when the
        // configuration phase hands over). The second ask for the same
        // profile from the same address within a few seconds is the same
        // join and must not cost a second throttle token. The identity checks
        // above run first on purpose: a refusal costs nothing, so a player
        // hammering "connect" is not throttled on top of being refused.
        PendingIp prior = pendingIps.get(profile.id());
        boolean sameJoin = prior != null && prior.ip().equals(ip) && now - prior.atMillis() < 10_000L;
        if (!sameJoin && !joinThrottle.tryAcquire(ip, now)) {
            BastionAuth.LOGGER.info("Join throttled for {} from {}", name, Secrets.maskIp(ip));
            return text("join.denied.throttle");
        }
        if (cfg.bruteForce.blockJoinsFromBlockedIp && ipBlocks.isBlocked(secrets.ipHmac(ip), now)) {
            return text("join.denied.ipBlocked");
        }
        pendingIps.put(profile.id(), new PendingIp(ip, now));
        return null;
    }

    private static String ipOf(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return "unknown";
    }

    // ------------------------------------------------------------------
    // Join / disconnect lifecycle (server thread)
    // ------------------------------------------------------------------

    public void onJoin(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        PendingIp pending = pendingIps.remove(uuid);
        String ip = pending != null ? pending.ip() : "unknown";
        String name = player.getGameProfile().name();

        PlayerAuthState st = new PlayerAuthState(uuid, name, ip);
        // The connection, not the entity: a respawn replaces the entity while
        // the connection stays, and the disconnect handler must still
        // recognise its own state afterwards.
        st.owner = player.networkHandler;
        st.anchor(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        states.put(uuid, st);

        // What the handshake and login phases parked for this connection:
        // the typed server address and the launcher-claimed UUID.
        try {
            DeviceFingerprint.claimPreLogin(
                    ((dev.bastionauth.mixin.ServerCommonNetworkHandlerAccessor) player.networkHandler).bastionauth$connection(),
                    uuid);
        } catch (Throwable ignored) {
        }

        // Device fingerprint + account-link analysis: runs on the worker so
        // the join tick never pays for it. Findings go to the audit log as
        // LINKED_ACCOUNT events — a signal for staff, never an auto-punish.
        recordFingerprintAsync(player, uuid, name, ip);

        boolean registered = nameCache.containsKey(name.toLowerCase(Locale.ROOT));
        long now = System.currentTimeMillis();

        AuthConfig cfg = this.cfg;
        st.agreementDone = hasAcceptedAgreement(player);
        boolean opNoResume = cfg.session.disableForOps && isOperator(player);
        // An IP session is weaker than a password and far weaker than a
        // second factor: behind a carrier NAT the address is shared with
        // strangers. So an account that asked for 2FA never resumes by IP
        // (same rule as operators), and a resumed session may not skip a new
        // revision of the agreement either.
        boolean twoFaNoResume = twoFactor.isEnabled(uuid);
        if (cfg.session.enabled && registered && isBindableIp(ip) && !opNoResume && !twoFaNoResume
                && st.agreementDone) {
            Session session = sessions.get(uuid);
            if (session != null) {
                if (session.expiresAt() > now && constantTimeEquals(session.ipHmac(), secrets.ipHmac(ip))) {
                    sessions.remove(uuid);
                    st.authenticated = true;
                    ChatGate.say(player, text("session.restored"), false);
                    ChatGate.release(player, this::heldHeader);
                    submitAudit("SESSION_RESUME", uuid, name, Secrets.maskIp(ip), null);
                    BastionAuth.LOGGER.info("{} resumed session from {}", name, Secrets.maskIp(ip));
                    return;
                }
                sessions.remove(uuid);
            }
        }

        beginAuthPhase(player, st, registered);
        if (!st.agreementDone) {
            submitAudit("AGREEMENT_REQUIRED", uuid, name, Secrets.maskIp(ip), AgreementManager.VERSION);
            promptAgreement(player);
        }
        submitAudit("JOIN_UNAUTH", uuid, name, Secrets.maskIp(ip), null);
    }

    /**
     * Snapshot of the player's fingerprint material on the server thread, then
     * hash, store and link-analyse on the worker. The composite device hash
     * (brand + client options + subnet) is stored per sighting; candidates
     * graded ≥ LIKELY are audited for staff review.
     */
    private void recordFingerprintAsync(ServerPlayerEntity player, UUID uuid, String name, String ip) {
        try {
            String brand = DeviceFingerprint.brandOf(player);
            String language;
            int viewDistance;
            String mainArm;
            try {
                var opts = player.getClientOptions();
                language = opts.language();
                viewDistance = opts.viewDistance();
                mainArm = String.valueOf(opts.mainArm());
            } catch (Exception e) {
                language = "?";
                viewDistance = -1;
                mainArm = "?";
            }
            String subnet = DeviceFingerprint.subnetOf(ip);
            // Bind the parked brand payload (from the configuration phase) to
            // this player's uuid now that it exists — BEFORE the hash, which
            // folds the brand in.
            DeviceFingerprint.claimPendingBrand(player.networkHandler, uuid);
            String hash = DeviceFingerprint.compute(player, secrets);
            if (hash == null) return;
            String profile = DeviceFingerprint.computeProfile(player, secrets);
            DeviceFingerprint.PreLogin pre = DeviceFingerprint.preLoginOf(uuid);
            String clientUuid = DeviceFingerprint.informativeClientUuid(uuid, pre);
            String host = pre == null || pre.host() == null || pre.host().isEmpty() ? null : pre.host();
            String ipHmac = secrets.ipHmac(ip);
            final String fLanguage = language;
            final int fViewDistance = viewDistance;
            final String fMainArm = mainArm;
            final String fBrand = brand;
            final String fSubnet = subnet;
            final String fHash = hash;
            final AccountLinker.Material material = new AccountLinker.Material(
                    hash, profile, clientUuid, host, null, true, ipHmac, null);
            executor.execute(() -> {
                try {
                    PlayerAuthState st = states.get(uuid);
                    if (st != null && !db.hashesOfUuid(uuid.toString()).contains(fHash)) st.newDevice = true;
                    db.recordFingerprint(uuid.toString(), fHash, fBrand, fLanguage, fViewDistance, fMainArm, fSubnet,
                            System.currentTimeMillis());
                    db.updateFingerprintExtras(uuid.toString(), fHash, material.profileHash(),
                            material.clientUuid(), material.hostUsed(), null, null);
                    analyseAndReport(uuid, name, material, ip);
                } catch (Exception e) {
                    BastionAuth.LOGGER.warn("Fingerprint analysis failed for {}: {}", name, e.toString());
                }
            });
        } catch (Exception e) {
            BastionAuth.LOGGER.warn("Fingerprint snapshot failed for {}: {}", name, e.toString());
        }
    }

    /**
     * Runs the composite link analysis with whatever signals are available
     * and audits the result. On join: device + IP + name + timing. After a
     * successful login: the same plus the password fingerprint — the
     * deepening pass, not a replacement.
     */
    private void analyseAndReport(UUID uuid, String name, AccountLinker.Material material, String ip) {
        try {
            AuthConfig cfg = this.cfg;
            AccountLinker linker = new AccountLinker(AccountLinker.Weights.defaults(),
                    cfg.linking.timingWindowHours * 3_600_000L);
            AccountLinker.Grade reportFrom;
            try {
                reportFrom = AccountLinker.Grade.valueOf(cfg.linking.reportGrade.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                reportFrom = AccountLinker.Grade.LIKELY;
            }
            Database.LinkRow me = null;
            for (Database.LinkRow row : db.linkRowsForUuids(java.util.List.of(uuid.toString()))) {
                me = row;
            }
            long myCreatedAt = me == null ? 0 : me.createdAt();
            long now = System.currentTimeMillis();
            for (AccountLinker.Candidate c : linker.analyse(uuid.toString(), name, material, now, db, myCreatedAt)) {
                AccountLinker.Grade g = linker.grade(c.score);
                // The permanent ledger: never pruned, best grade ever kept.
                db.upsertLink(uuid.toString(), name, c.uuid, c.username, c.score, g.name(),
                        AccountLinker.describeSignals(c), now);
                db.audit(now, "LINKED_ACCOUNT", uuid.toString(), name,
                        Secrets.maskIp(ip),
                        "grade=" + g + " with=" + c.username + " score=" + c.score
                                + " signals=" + AccountLinker.describe(c, g));
                if (g.ordinal() >= reportFrom.ordinal() && g != AccountLinker.Grade.NONE) {
                    BastionAuth.LOGGER.warn("LinkedAccountDetected: {} ↔ {} ({} — score {})",
                            name, c.username, g, c.score);
                    // Feed the anti-cheat's network registry: the link belongs
                    // on the "Сеть" tab next to the subnet sightings, with the
                    // actual signals it was built from (so the UI shows WHY,
                    // not just THAT).
                    BastionAuth.reportLinkedAccount(name, uuid.toString(), c.username, c.uuid,
                            AccountLinker.describeSignals(c), g.name(), String.valueOf(c.score));
                }
            }
        } catch (Exception e) {
            BastionAuth.LOGGER.warn("Link analysis failed for {}: {}", name, e.toString());
        }
    }

    /** Freeze visuals + prompts. Also used by /logout and admin unregister. */
    private void beginAuthPhase(ServerPlayerEntity player, PlayerAuthState st, boolean registered) {
        AuthConfig cfg = this.cfg;
        // Nothing about the account is shown before the password — including
        // what it is carrying. Pressing E used to display the real inventory to
        // anybody who typed the name. See InventoryMask.
        InventoryMask.apply(player);
        st.maskStep = 0;
        // Next tick, which is as soon as this can run again: vanilla's own
        // initial sync lands between the two, so this is the width of the
        // window in which the real inventory is on the client — one tick, with
        // no screen open to render it.
        st.maskDueTick = tickCounter + 1;
        // Only apply our own invisibility if the player isn't already invisible —
        // otherwise finishAuth would strip a legitimate potion effect on login.
        //
        // Status effects survive a disconnect, so a leftover of OUR OWN effect
        // from a previous session used to look like a legitimate potion: the
        // guard skipped it, invisApplied stayed false, finishAuth never removed
        // it, and the player walked around invisible until it timed out. Our own
        // effect is recognisable and gets reclaimed instead.
        if (cfg.protection.invisibleWhileUnauthenticated) {
            StatusEffectInstance existing = player.getStatusEffect(StatusEffects.INVISIBILITY);
            boolean leftoverOfOurs = existing != null && isOurInvisibility(existing);
            if (existing == null || leftoverOfOurs) {
                if (leftoverOfOurs) player.removeStatusEffect(StatusEffects.INVISIBILITY);
                // Long enough to cover the agreement clock as well as the login one.
                int duration = (cfg.general.loginTimeoutSeconds
                        + (st.agreementDone ? 0 : cfg.general.agreementTimeoutSeconds) + 90) * 20;
                player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.INVISIBILITY, duration, 0, true, false, false));
                st.invisApplied = true;
            }
        }
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 160, 10));
        ChatGate.packet(player, new TitleS2CPacket(text("join.title")));
        ChatGate.packet(player, new SubtitleS2CPacket(
                text(registered ? "join.subtitle.login" : "join.subtitle.register")));
        if (st.agreementDone) {
            ChatGate.say(player, text(registered ? "reminder.login" : "reminder.register",
                    cfg.general.loginTimeoutSeconds), false);
        }
    }

    /**
     * The exact shape we apply: ambient, no particles, no icon. A potion of
     * invisibility always shows particles, so this never mistakes one for ours.
     */
    private static boolean isOurInvisibility(StatusEffectInstance effect) {
        return effect.getAmplifier() == 0
                && effect.isAmbient()
                && !effect.shouldShowParticles()
                && !effect.shouldShowIcon();
    }

    public void onDisconnect(ServerPlayerEntity player) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null) return;
        // Identity check, not a blind remove: in offline mode two connections
        // can carry the same UUID (name collision after a kick, or a quick
        // reconnect whose old disconnect is delivered late). If the state in
        // the map belongs to a NEWER player object, removing by key would
        // destroy the live player's state — and with it the agreement mark —
        // so only the connection that created the state may remove it.
        if (st.owner != null && st.owner != player.networkHandler) return;
        if (!states.remove(player.getUuid(), st)) return;
        ChatGate.forget(st.uuid);
        DeviceFingerprint.forget(st.uuid);
        channelCaptureDue.remove(st.uuid);
        lastDropLogNanos.remove(st.uuid);
        agreementOk.remove(st.uuid);
        codeword.forget(st.uuid);
        // The unconfirmed 2FA seed is kept (15-minute TTL): a player who
        // relogs to reach their phone should not have to start over.
        if (st.authenticated && cfg.session.enabled) {
            sessions.put(st.uuid, new Session(secrets.ipHmac(st.ip),
                    System.currentTimeMillis() + cfg.session.ttlMinutes * 60_000L));
        }
    }

    /**
     * After a respawn the entity is new: the freeze anchor moves there and
     * the visuals (invisibility does not survive death) are applied again.
     * Server thread, from {@code ServerPlayerEvents.AFTER_RESPAWN}.
     */
    public void onRespawn(ServerPlayerEntity player) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null || st.authenticated) return;
        st.anchor(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        st.invisApplied = false;
        beginAuthPhase(player, st, nameCache.containsKey(st.name.toLowerCase(Locale.ROOT)));
    }

    // ------------------------------------------------------------------
    // Tick: login timeout, reminders, periodic cleanup (server thread)
    // ------------------------------------------------------------------

    public void tick() {
        tickCounter++;
        if (tickCounter % 20 == 0) ChatGate.releaseStragglers(server, this::heldHeader);
        if (!channelCaptureDue.isEmpty()) {
            for (java.util.Map.Entry<UUID, Long> e : channelCaptureDue.entrySet()) {
                if (tickCounter >= e.getValue()) {
                    channelCaptureDue.remove(e.getKey());
                    captureChannels(e.getKey());
                }
            }
        }
        // The inventory mask, on its short schedule. See PlayerAuthState for
        // why it is a schedule and not a one-shot or a periodic task.
        for (PlayerAuthState masked : states.values()) {
            if (masked.authenticated || masked.maskDueTick == 0 || tickCounter < masked.maskDueTick) continue;
            ServerPlayerEntity mp = server.getPlayerManager().getPlayer(masked.uuid);
            if (mp != null) InventoryMask.apply(mp);
            masked.maskStep++;
            masked.maskDueTick = switch (masked.maskStep) {
                case 1 -> tickCounter + 8;      // after vanilla's own sync
                case 2 -> tickCounter + 30;     // after the agreement books land
                default -> 0;                   // settled; nothing else resyncs
            };
        }

        if (tickCounter % 20 != 0) return;
        AuthConfig cfg = this.cfg;
        long nowNanos = System.nanoTime();

        for (PlayerAuthState st : states.values()) {
            if (st.authenticated) continue;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
            if (p == null) continue;
            long elapsedSec = (nowNanos - st.joinNanos) / 1_000_000_000L;
            // Two clocks: reading and accepting the agreement has its own,
            // longer one; the login clock starts when the agreement is on
            // file (acceptAgreement resets joinNanos).
            int limit = st.awaitingCode ? cfg.twoFactor.codeTimeoutSeconds
                    : st.agreementDone ? cfg.general.loginTimeoutSeconds : cfg.general.agreementTimeoutSeconds;
            if (elapsedSec >= limit) {
                disconnect(p, text("timeout.kick"));
                submitAudit(st.agreementDone ? "LOGIN_TIMEOUT" : "AGREEMENT_TIMEOUT",
                        st.uuid, st.name, Secrets.maskIp(st.ip), null);
                continue;
            }
            if (nowNanos - st.lastReminderNanos >= 10_000_000_000L) {
                st.lastReminderNanos = nowNanos;
                long left = limit - elapsedSec;
                if (st.awaitingCode) {
                    ChatGate.say(p, text("reminder.code", left), true);
                } else if (!st.agreementDone) {
                    ChatGate.say(p, text("reminder.agreement", Math.max(1, (left + 59) / 60)), true);
                } else {
                    boolean registered = nameCache.containsKey(st.name.toLowerCase(Locale.ROOT));
                    ChatGate.say(p, text(registered ? "reminder.login" : "reminder.register", left), true);
                }
            }
        }

        if (tickCounter % 1200 == 0) {
            long nowMs = System.currentTimeMillis();
            joinThrottle.cleanup(nowMs);
            ipFailWindow.cleanup(nowMs);
            ipBlocks.cleanup(nowMs);
            sessions.entrySet().removeIf(e -> e.getValue().expiresAt() <= nowMs);
            pendingIps.entrySet().removeIf(e -> nowMs - e.getValue().atMillis() > 120_000);
            // ToS §8.4 TTL: device fingerprints are anti-abuse material —
            // two-year retention horizon, pruned hourly in batches.
            executor.execute(() -> db.pruneFingerprints(nowMs - 730L * 24 * 60 * 60 * 1000));
        }
    }

    // ------------------------------------------------------------------
    // Player commands (server thread entry, heavy work on executor)
    // ------------------------------------------------------------------

    public void tryRegister(ServerPlayerEntity player, String password, String confirm) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null || st.authenticated) {
            send(player, "login.already");
            return;
        }
        AuthConfig cfg = this.cfg;
        if (!cfg.general.allowRegistration) {
            send(player, "register.disabled");
            return;
        }
        // Section 2 of the agreement: acceptance comes BEFORE registration.
        if (!hasAcceptedAgreement(player)) {
            send(player, "agreement.required");
            promptAgreement(player);
            return;
        }
        String lower = st.name.toLowerCase(Locale.ROOT);
        if (nameCache.containsKey(lower)) {
            send(player, "register.already");
            return;
        }
        if (!password.equals(confirm)) {
            send(player, "register.mismatch");
            return;
        }
        PasswordPolicy.Violation violation = policy.validate(st.name, password);
        if (violation != null) {
            send(player, violation.messageKey(), violation.args());
            return;
        }
        if (!st.busy.compareAndSet(false, true)) {
            send(player, "inProgress");
            return;
        }
        String ipHmac = secrets.ipHmac(st.ip);
        String ipMasked = Secrets.maskIp(st.ip);
        submit(player, st, () -> {
            long now = System.currentTimeMillis();
            try {
                if (db.getByNameLower(lower) != null || db.getByUuid(st.uuid.toString()) != null) {
                    sendLater(player, st, "register.already");
                    return;
                }
                if (db.countByRegIp(ipHmac) >= cfg.bruteForce.maxAccountsPerIp) {
                    db.audit(now, "REGISTER_IP_LIMIT", st.uuid.toString(), st.name, ipMasked, null);
                    sendLater(player, st, "register.ipLimit");
                    return;
                }
                String phc = hasher.hash(password);
                db.insertUser(st.uuid.toString(), st.name, lower, phc, now, ipHmac);
                db.updatePwFp(st.uuid.toString(), hasher.fingerprint(password));
                db.recordLoginSuccess(st.uuid.toString(), now, ipHmac, ipMasked);
                db.audit(now, "REGISTER", st.uuid.toString(), st.name, ipMasked, null);
                server.execute(() -> {
                    nameCache.put(lower, st.name);
                    finishAuth(player, st, text("register.success"), null);
                });
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    sendLater(player, st, "register.nameTaken");
                } else {
                    BastionAuth.LOGGER.error("Registration failed for {}", st.name, e);
                    sendLater(player, st, "busy");
                }
            } finally {
                st.busy.set(false);
            }
        });
    }

    public void tryLogin(ServerPlayerEntity player, String password) {
        tryLogin(player, password, null);
    }

    /**
     * {@code code} is the second factor typed together with the password
     * ({@code /login pw 123456}); null means "ask for it afterwards" when the
     * account has one.
     */
    public void tryLogin(ServerPlayerEntity player, String password, String code) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null || st.authenticated) {
            send(player, "login.already");
            return;
        }
        if (st.awaitingCode) {
            send(player, "twofa.needCode");
            return;
        }
        String lower = st.name.toLowerCase(Locale.ROOT);
        if (!nameCache.containsKey(lower)) {
            send(player, "login.notRegistered");
            return;
        }
        // A new revision has to be read by everyone, not only by new players.
        if (!hasAcceptedAgreement(player)) {
            send(player, "agreement.required");
            promptAgreement(player);
            return;
        }
        AuthConfig cfg = this.cfg;
        String ipHmac = secrets.ipHmac(st.ip);
        String ipMasked = Secrets.maskIp(st.ip);
        if (ipBlocks.isBlocked(ipHmac, System.currentTimeMillis())) {
            send(player, "login.ipBlocked");
            return;
        }
        if (!st.busy.compareAndSet(false, true)) {
            send(player, "inProgress");
            return;
        }
        submit(player, st, () -> {
            try {
                long now = System.currentTimeMillis();
                UserRecord user = db.getByUuid(st.uuid.toString());
                if (user == null) user = db.getByNameLower(lower);
                if (user == null) {
                    sendLater(player, st, "login.notRegistered");
                    return;
                }
                if (user.lockedUntil() > now) {
                    long minutesLeft = Math.max(1, (user.lockedUntil() - now + 59_999) / 60_000);
                    db.audit(now, "LOGIN_LOCKED", user.uuid(), st.name, ipMasked, null);
                    if (user.lockedUntil() - now > PANIC_LOCK_MS / 2) sendLater(player, st, "login.panicLocked");
                    else sendLater(player, st, "login.locked", minutesLeft);
                    return;
                }
                PasswordHasher.VerifyResult result = hasher.verify(user.phc(), password);
                if (result == PasswordHasher.VerifyResult.NO_MATCH) {
                    handleWrongPassword(player, st, user, ipHmac, ipMasked, now);
                    return;
                }
                if (result == PasswordHasher.VerifyResult.MATCH_NEEDS_REHASH) {
                    try {
                        db.updatePhc(user.uuid(), hasher.hash(password));
                    } catch (SQLException e) {
                        BastionAuth.LOGGER.warn("Rehash failed for {}", st.name, e);
                    }
                }
                final UserRecord fUser = user;
                final String fPassword = password;
                // The rest of the login — bookkeeping, link analysis, unfreeze —
                // runs now, or once the second factor is right.
                Runnable complete = () -> completeLogin(player, st, fUser, fPassword, ipHmac, ipMasked);
                if (twoFactor.isEnabled(st.uuid)) {
                    if (code == null) {
                        db.audit(now, "LOGIN_PW_OK", user.uuid(), st.name, ipMasked, "awaiting code");
                        st.onCodeOk = complete;
                        st.awaitingCode = true;
                        st.joinNanos = System.nanoTime();
                        st.lastReminderNanos = st.joinNanos;
                        sendLater(player, st, "twofa.needCode");
                        return;
                    }
                    TwoFactor.Check check = twoFactor.check(st.uuid, code);
                    if (check == TwoFactor.Check.WRONG) {
                        handleWrongCode(player, st, user, ipMasked, now);
                        return;
                    }
                    if (check == TwoFactor.Check.BACKUP_USED) {
                        db.audit(now, "2FA_BACKUP_USED", user.uuid(), st.name, ipMasked, null);
                        sendLater(player, st, "twofa.backupUsed", twoFactor.backupCodesLeft(st.uuid));
                    }
                }
                complete.run();
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Login failed for {}", st.name, e);
                sendLater(player, st, "busy");
            } finally {
                st.busy.set(false);
            }
        });
    }

    /** Worker thread: everything a verified login does once nothing more is owed. */
    private void completeLogin(ServerPlayerEntity player, PlayerAuthState st, UserRecord user, String password,
                               String ipHmac, String ipMasked) {
        try {
            long now = System.currentTimeMillis();
            long prevLoginAt = user.lastLoginAt();
            String prevIpMasked = user.lastIpMasked();
            db.recordLoginSuccess(user.uuid(), now, ipHmac, ipMasked);
            // Deepening link pass: the password is now verified, so its
            // deterministic fingerprint can join the composite scoring.
            // Also refreshes a fingerprint written before this column
            // existed (pre-1.2 accounts get theirs on first login).
            String pwFp = hasher.fingerprint(password);
            db.updatePwFp(user.uuid(), pwFp);
            db.audit(now, "LOGIN_OK", user.uuid(), st.name, ipMasked, st.newDevice ? "new device" : null);
            if (st.newDevice && prevLoginAt > 0) db.audit(now, "NEW_DEVICE", user.uuid(), st.name, ipMasked, null);
            final AccountLinker.Material base = materialOf(st.uuid, ipHmac, pwFp);
            if (base != null) {
                executor.execute(() -> analyseAndReport(st.uuid, st.name, base, st.ip));
            }
            final boolean noteDevice = st.newDevice && prevLoginAt > 0;
            st.newDevice = false; // once per connection, not on every re-login
            server.execute(() -> {
                Text lastSeen = prevLoginAt > 0
                        ? text("login.lastSeen", TIME_FORMAT.format(Instant.ofEpochMilli(prevLoginAt)),
                                prevIpMasked == null ? "?" : prevIpMasked)
                        : null;
                finishAuth(player, st, text("login.success"), lastSeen);
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
                if (p == null || states.get(st.uuid) != st) return;
                if (noteDevice) ChatGate.say(p, text(twoFactor.isEnabled(st.uuid) ? "device.new" : "device.newNo2fa"), false);
                if (cfg.twoFactor.remindOpsWithout2fa && isOperator(p) && !twoFactor.isEnabled(st.uuid)) {
                    ChatGate.say(p, text("twofa.opReminder"), false);
                }
            });
        } catch (SQLException e) {
            BastionAuth.LOGGER.error("Login completion failed for {}", st.name, e);
            sendLater(player, st, "busy");
        }
    }

    /** {@code /2fa <код>} while the password has been accepted and the code is owed. */
    public void tryCode(ServerPlayerEntity player, String code) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null || st.authenticated) {
            send(player, "login.already");
            return;
        }
        if (!st.awaitingCode || st.onCodeOk == null) {
            send(player, "twofa.notAwaiting");
            return;
        }
        if (!st.busy.compareAndSet(false, true)) {
            send(player, "inProgress");
            return;
        }
        String ipMasked = Secrets.maskIp(st.ip);
        submit(player, st, () -> {
            try {
                long now = System.currentTimeMillis();
                UserRecord user = db.getByUuid(st.uuid.toString());
                if (user == null) {
                    sendLater(player, st, "login.notRegistered");
                    return;
                }
                TwoFactor.Check check = twoFactor.check(st.uuid, code);
                if (check == TwoFactor.Check.WRONG) {
                    handleWrongCode(player, st, user, ipMasked, now);
                    return;
                }
                if (check == TwoFactor.Check.BACKUP_USED) {
                    db.audit(now, "2FA_BACKUP_USED", user.uuid(), st.name, ipMasked, null);
                    sendLater(player, st, "twofa.backupUsed", twoFactor.backupCodesLeft(st.uuid));
                }
                Runnable complete = st.onCodeOk;
                st.onCodeOk = null;
                st.awaitingCode = false;
                // NOT_ENABLED means the factor vanished meanwhile (admin reset): the password was enough.
                if (complete != null) complete.run();
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Code check failed for {}", st.name, e);
                sendLater(player, st, "busy");
            } finally {
                st.busy.set(false);
            }
        });
    }

    /** Worker thread: a wrong second factor counts like a wrong password. */
    private void handleWrongCode(ServerPlayerEntity player, PlayerAuthState st, UserRecord user,
                                 String ipMasked, long now) {
        AuthConfig cfg = this.cfg;
        boolean lockedNow = false;
        try {
            int fails = db.addFailure(user.uuid());
            if (fails >= cfg.bruteForce.maxFailedPerAccount) {
                db.lockAccount(user.uuid(), now + cfg.bruteForce.accountLockMinutes * 60_000L);
                lockedNow = true;
            }
            db.audit(now, "2FA_FAIL", user.uuid(), st.name, ipMasked, "fails=" + fails + (lockedNow ? ",locked" : ""));
        } catch (SQLException e) {
            BastionAuth.LOGGER.warn("Failure bookkeeping failed for {}", st.name, e);
        }
        boolean fLocked = lockedNow;
        server.execute(() -> {
            if (states.get(st.uuid) != st) return;
            st.wrongCodes++;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
            if (p == null) return;
            if (fLocked) {
                disconnect(p, text("login.locked", cfg.bruteForce.accountLockMinutes));
            } else if (st.wrongCodes >= cfg.twoFactor.maxTries) {
                disconnect(p, text("twofa.tooMany"));
            } else {
                send(p, "twofa.wrong", cfg.twoFactor.maxTries - st.wrongCodes);
            }
        });
    }

    /** Worker-thread continuation of a failed password check. */
    private void handleWrongPassword(ServerPlayerEntity player, PlayerAuthState st, UserRecord user,
                                     String ipHmac, String ipMasked, long now) throws SQLException {
        AuthConfig cfg = this.cfg;
        int fails = db.addFailure(user.uuid());
        boolean lockedNow = false;
        if (fails >= cfg.bruteForce.maxFailedPerAccount) {
            db.lockAccount(user.uuid(), now + cfg.bruteForce.accountLockMinutes * 60_000L);
            lockedNow = true;
        }
        int windowCount = ipFailWindow.recordAndCount(ipHmac, now);
        boolean ipBlockedNow = false;
        if (windowCount >= cfg.bruteForce.ipMaxFailures) {
            ipBlocks.block(ipHmac, now + cfg.bruteForce.ipBlockMinutes * 60_000L);
            ipFailWindow.reset(ipHmac);
            ipBlockedNow = true;
        }
        db.audit(now, "LOGIN_FAIL", user.uuid(), st.name, ipMasked,
                "fails=" + fails + (lockedNow ? ",locked" : "") + (ipBlockedNow ? ",ipBlocked" : ""));
        boolean fLocked = lockedNow;
        boolean fIpBlocked = ipBlockedNow;
        server.execute(() -> {
            if (states.get(st.uuid) != st) return;
            st.wrongThisConnection++;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
            if (p == null) return;
            if (fLocked) {
                disconnect(p, text("login.locked", cfg.bruteForce.accountLockMinutes));
            } else if (fIpBlocked) {
                disconnect(p, text("login.ipBlocked"));
            } else if (st.wrongThisConnection >= cfg.general.maxLoginTriesPerConnection) {
                disconnect(p, text("login.tooManyTries"));
            } else {
                send(p, "login.wrong", cfg.general.maxLoginTriesPerConnection - st.wrongThisConnection);
            }
        });
    }

    public void tryChangePassword(ServerPlayerEntity player, String oldPassword, String newPassword, String confirm) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null || !st.authenticated) {
            send(player, "mustLogin");
            return;
        }
        if (!newPassword.equals(confirm)) {
            send(player, "register.mismatch");
            return;
        }
        PasswordPolicy.Violation violation = policy.validate(st.name, newPassword);
        if (violation != null) {
            send(player, violation.messageKey(), violation.args());
            return;
        }
        if (codeword.protects(st.uuid)) {
            send(player, "codeword.required");
            return;
        }
        if (!st.busy.compareAndSet(false, true)) {
            send(player, "inProgress");
            return;
        }
        String ipMasked = Secrets.maskIp(st.ip);
        submit(player, st, () -> {
            try {
                long now = System.currentTimeMillis();
                UserRecord user = db.getByUuid(st.uuid.toString());
                if (user == null) {
                    sendLater(player, st, "login.notRegistered");
                    return;
                }
                if (hasher.verify(user.phc(), oldPassword) == PasswordHasher.VerifyResult.NO_MATCH) {
                    db.audit(now, "CHANGEPW_FAIL", user.uuid(), st.name, ipMasked, null);
                    sendLater(player, st, "changepw.wrongOld");
                    return;
                }
                db.updatePhc(user.uuid(), hasher.hash(newPassword));
                db.updatePwFp(user.uuid(), hasher.fingerprint(newPassword));
                db.audit(now, "CHANGEPW", user.uuid(), st.name, ipMasked, null);
                sendLater(player, st, "changepw.success");
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Password change failed for {}", st.name, e);
                sendLater(player, st, "busy");
            } finally {
                st.busy.set(false);
            }
        });
    }

    public void logout(ServerPlayerEntity player) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null || !st.authenticated) {
            send(player, "mustLogin");
            return;
        }
        sessions.remove(st.uuid);
        st.authenticated = false;
        st.wrongThisConnection = 0;
        st.wrongCodes = 0;
        st.awaitingCode = false;
        st.onCodeOk = null;
        codeword.forget(st.uuid);
        st.joinNanos = System.nanoTime();
        st.lastReminderNanos = st.joinNanos;
        st.anchor(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        send(player, "logout.done");
        beginAuthPhase(player, st, true);
        submitAudit("LOGOUT", st.uuid, st.name, Secrets.maskIp(st.ip), null);
    }

    // ------------------------------------------------------------------
    // Admin commands
    // ------------------------------------------------------------------

    public void adminUnregister(ServerCommandSource source, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        submitAdmin(source, () -> {
            boolean removed;
            String uuid = null;
            try {
                UserRecord user = db.getByNameLower(lower);
                if (user != null) uuid = user.uuid();
                removed = db.deleteByNameLower(lower);
                if (removed && uuid != null) {
                    // The offline UUID is the name, so a re-registration under
                    // the same name is the same UUID: everything keyed by it
                    // must go too, or the new account inherits the old second
                    // factor (with a seed nobody has any more), the old
                    // session and the old agreement acceptance. disable() also
                    // clears the in-memory "has 2FA" set, which a raw
                    // deleteTotp would leave stale.
                    try {
                        twoFactor.disable(UUID.fromString(uuid));
                    } catch (IllegalArgumentException ignored) {
                    }
                    db.deleteSession(uuid);
                    db.deleteAgreement(uuid);
                }
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Unregister failed for {}", name, e);
                replyLater(source, "busy");
                return;
            }
            db.audit(System.currentTimeMillis(), "ADMIN_UNREGISTER", uuid, name, null, removed ? "ok" : "not_found");
            final String fUuid = uuid;
            server.execute(() -> {
                if (!removed) {
                    reply(source, "admin.notFound", name);
                    return;
                }
                nameCache.remove(lower);
                if (fUuid != null) {
                    try {
                        UUID id = UUID.fromString(fUuid);
                        sessions.remove(id);
                        agreementOk.remove(id);
                        twoFactor.forgetPending(id);
                        codeword.forget(id);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                ServerPlayerEntity online = findOnlineByLower(lower);
                if (online != null) {
                    sessions.remove(online.getUuid());
                    PlayerAuthState st = states.get(online.getUuid());
                    if (st != null) {
                        st.authenticated = false;
                        st.wrongThisConnection = 0;
                        st.busy.set(false);
                        st.joinNanos = System.nanoTime();
                        st.lastReminderNanos = st.joinNanos;
                        st.anchor(online.getX(), online.getY(), online.getZ(), online.getYaw(), online.getPitch());
                        beginAuthPhase(online, st, false);
                    }
                }
                reply(source, "admin.unregistered", name);
            });
        });
    }

    public void adminUnlock(ServerCommandSource source, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        submitAdmin(source, () -> {
            try {
                UserRecord user = db.getByNameLower(lower);
                if (user == null) {
                    replyLater(source, "admin.notFound", name);
                    return;
                }
                db.unlock(user.uuid());
                db.audit(System.currentTimeMillis(), "ADMIN_UNLOCK", user.uuid(), name, null, null);
                replyLater(source, "admin.unlocked", user.username());
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Unlock failed for {}", name, e);
                replyLater(source, "busy");
            }
        });
    }

    public void adminTwoFactorReset(ServerCommandSource source, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        submitAdmin(source, () -> {
            try {
                UserRecord user = db.getByNameLower(lower);
                if (user == null) {
                    replyLater(source, "admin.notFound", name);
                    return;
                }
                boolean had = twoFactor.disable(UUID.fromString(user.uuid()));
                db.audit(System.currentTimeMillis(), "ADMIN_2FA_RESET", user.uuid(), name, null, had ? "removed" : "none");
                replyLater(source, had ? "admin.twofaReset" : "admin.twofaNone", user.username());
            } catch (Exception e) {
                BastionAuth.LOGGER.error("2FA reset failed for {}", name, e);
                replyLater(source, "busy");
            }
        });
    }

    public void adminStatus(ServerCommandSource source, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        submitAdmin(source, () -> {
            try {
                UserRecord user = db.getByNameLower(lower);
                if (user == null) {
                    replyLater(source, "admin.notFound", name);
                    return;
                }
                long now = System.currentTimeMillis();
                String created = TIME_FORMAT.format(Instant.ofEpochMilli(user.createdAt()));
                String lastLogin = user.lastLoginAt() > 0 ? TIME_FORMAT.format(Instant.ofEpochMilli(user.lastLoginAt())) : "—";
                String lastIp = user.lastIpMasked() == null ? "?" : user.lastIpMasked();
                String locked = user.lockedUntil() > now
                        ? "до " + TIME_FORMAT.format(Instant.ofEpochMilli(user.lockedUntil()))
                        : "нет";
                replyLater(source, "admin.status", user.username(), created, lastLogin, lastIp,
                        user.failedAttempts(), locked);
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Status failed for {}", name, e);
                replyLater(source, "busy");
            }
        });
    }

    /**
     * Whether the twin analysis considers these two accounts the same person
     * at {@code minGrade} or above. Read-only, off the permanent ledger, so
     * it answers even for accounts that are offline right now.
     *
     * <p>Exposed for MaxCore's economy: funnelling alt-farmed rewards into one
     * account is the oldest exploit in any server economy, and this is the
     * signal that already knows about it.
     */
    public boolean accountsLinked(UUID a, UUID b, String minGrade) {
        if (a == null || b == null || a.equals(b)) return false;
        AccountLinker.Grade min;
        try {
            min = AccountLinker.Grade.valueOf(minGrade == null ? "LIKELY" : minGrade.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            min = AccountLinker.Grade.LIKELY;
        }
        Database.LinkLedgerRow row = db.linkBetween(a.toString(), b.toString());
        return row != null && AccountLinker.linkedByLedger(row.verdict(), row.bestGrade(), min);
    }

    /** Linked for a state benefit: TRUSTED does not clear the pair here. See AccountLinker. */
    public boolean accountsLinkedForStateBenefits(UUID a, UUID b, String minGrade) {
        if (a == null || b == null || a.equals(b)) return false;
        AccountLinker.Grade min;
        try {
            min = AccountLinker.Grade.valueOf(minGrade == null ? "LIKELY" : minGrade.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            min = AccountLinker.Grade.LIKELY;
        }
        Database.LinkLedgerRow row = db.linkBetween(a.toString(), b.toString());
        return row != null && AccountLinker.linkedForStateBenefits(row.verdict(), row.bestGrade(), min);
    }

    // ------------------------------------------------------------------
    // 1.8: the link register — staff decisions about account pairs
    // ------------------------------------------------------------------

    /** The standing decision about a pair; {@link LinkVerdict#NEW} when there is none. */
    public LinkVerdict linkVerdict(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return LinkVerdict.NEW;
        Database.LinkLedgerRow row = db.linkBetween(a.toString(), b.toString());
        return row == null ? LinkVerdict.NEW : LinkVerdict.of(row.verdict());
    }

    /** Every pair this account is part of, strongest first. */
    public java.util.List<Database.LinkLedgerRow> linksOf(UUID uuid) {
        return uuid == null ? java.util.List.of() : db.linksOf(uuid.toString());
    }

    /** The whole ledger, unreviewed pairs first. */
    public java.util.List<Database.LinkLedgerRow> allLinks(int limit) {
        return db.allLinks(limit);
    }

    /** How many strong pairs nobody has ruled on yet. */
    public int unreviewedLinks() {
        return db.unreviewedLinkCount();
    }

    /** The registered uuid behind a name, or null. Case-insensitive. */
    public UUID uuidOfName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            UserRecord user = db.getByNameLower(name.toLowerCase(Locale.ROOT));
            return user == null ? null : UUID.fromString(user.uuid());
        } catch (Exception e) {
            return null;
        }
    }

    /** The registered name behind a uuid, or null. */
    public String nameOfUuid(UUID uuid) {
        if (uuid == null) return null;
        for (Database.LinkRow row : db.linkRowsForUuids(java.util.List.of(uuid.toString()))) {
            return row.username();
        }
        return null;
    }

    /**
     * Files a verdict on a pair, creating the row when the analysis never made
     * one. Runs on the caller's thread: SQLite here is synchronized and a
     * staff click is not a hot path, and the caller wants the answer to render
     * the result.
     */
    public boolean setLinkVerdict(UUID a, UUID b, LinkVerdict verdict, String by, String note) {
        if (a == null || b == null || a.equals(b) || verdict == null) return false;
        long now = System.currentTimeMillis();
        String sa = a.toString(), sb = b.toString();
        boolean ok = db.setLinkVerdict(sa, sb, verdict.name(), by, note, now);
        if (!ok) {
            String na = nameOfUuid(a), nb = nameOfUuid(b);
            ok = db.declareLink(sa, na == null ? sa.substring(0, 8) : na,
                    sb, nb == null ? sb.substring(0, 8) : nb, verdict.name(), by, note, now);
        }
        if (ok) {
            db.audit(now, "LINK_VERDICT", sa, nameOfUuid(a), null,
                    "verdict=" + verdict.name() + " with=" + sb + " by=" + by
                            + (note == null || note.isBlank() ? "" : " note=" + note));
            BastionAuth.LOGGER.info("LinkVerdict {}: {} ↔ {} by {}", verdict.name(), sa, sb, by);
        }
        return ok;
    }

    /** Applies one verdict to every pair an account is part of. Returns rows changed. */
    public int setVerdictForAllLinksOf(UUID uuid, LinkVerdict verdict, String by, String note) {
        if (uuid == null || verdict == null) return 0;
        long now = System.currentTimeMillis();
        int n = db.setVerdictForAllLinksOf(uuid.toString(), verdict.name(), by, note, now);
        if (n > 0) {
            db.audit(now, "LINK_VERDICT", uuid.toString(), nameOfUuid(uuid), null,
                    "verdict=" + verdict.name() + " bulk=" + n + " by=" + by);
            BastionAuth.LOGGER.info("LinkVerdict {} applied to all {} link(s) of {} by {}",
                    verdict.name(), n, uuid, by);
        }
        return n;
    }

    // ------------------------------------------------------------------
    // User agreement (pre-registration acceptance, section 2)
    // ------------------------------------------------------------------

    /** Whether this account has accepted the revision currently in force. */
    public boolean hasAcceptedAgreement(ServerPlayerEntity player) {
        if (!AgreementManager.available()) return true;   // no text shipped: never lock anyone out
        UUID id = player.getUuid();
        if (agreementOk.contains(id)) return true;
        String stored = db.acceptedAgreement(id.toString());
        boolean ok = AgreementManager.VERSION.equals(stored);
        if (ok) agreementOk.add(id);
        return ok;
    }

    /**
     * Hands over the books and explains what to do next. The books are given
     * only when the player does not already hold the current volumes: this
     * runs on every refused /login too, and re-issuing the set each time
     * closes the book the player is reading.
     */
    public void promptAgreement(ServerPlayerEntity player) {
        // The volumes are drawn into the first hotbar slots of the masked view;
        // nothing is put in the real inventory. See InventoryMask for why.
        int count = AgreementManager.available() ? AgreementManager.volumes().size() : 0;
        if (count > 0) {
            InventoryMask.apply(player);
            // Volume I goes straight into the hand — unless a volume already is,
            // since this runs again on every refused /login and must not yank
            // the reader back from volume III.
            var inv = player.getInventory();
            if (inv.getSelectedSlot() >= Math.min(count, 9)) {
                inv.setSelectedSlot(0);
                player.networkHandler.sendPacket(
                        new net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket(0));
            }
        }
        ChatGate.say(player, text("agreement.title"), false);
        if (count > 0) {
            ChatGate.say(player, text("agreement.booksHotbar", count), false);
        } else {
            ChatGate.say(player, text("agreement.uiFallback"), false);
        }
        ChatGate.say(player, text("agreement.ready"), false);
        // One click instead of a command typed under a countdown.
        ChatGate.say(player, text("agreement.button").copy().styled(s -> s
                .withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/agreement accept"))
                .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(text("agreement.buttonHover")))), false);
    }

    /** Records the acceptance. Returns false when it was already on file. */
    public boolean acceptAgreement(ServerPlayerEntity player) {
        if (hasAcceptedAgreement(player)) return false;
        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        db.acceptAgreement(id.toString(), AgreementManager.VERSION, now);
        agreementOk.add(id);
        submitAudit("AGREEMENT_ACCEPT", id, player.getGameProfile().name(),
                Secrets.maskIp(ipOfPlayer(id)), AgreementManager.VERSION);
        BastionAuth.LOGGER.info("Agreement {} accepted by {}", AgreementManager.REVISION,
                player.getGameProfile().name());
        // The login clock starts now: the time spent reading is not held
        // against the password.
        PlayerAuthState st = states.get(id);
        if (st != null && !st.authenticated) {
            st.agreementDone = true;
            st.joinNanos = System.nanoTime();
            st.lastReminderNanos = st.joinNanos;
            // The volumes were only ever in the view; with the agreement on
            // file they leave it, and the hotbar goes blank until login.
            InventoryMask.apply(player);
        }
        return true;
    }

    /**
     * Whether this connection's inventory view should carry the agreement
     * volumes: frozen, and the current revision not yet accepted. Read from the
     * netty thread by the packet firewall — plain volatile reads.
     */
    public boolean showsAgreementBooks(UUID id) {
        PlayerAuthState st = id == null ? null : states.get(id);
        return st != null && !st.authenticated && !st.agreementDone && AgreementManager.available();
    }

    /**
     * Asks for the inventory mask to be re-sent shortly.
     *
     * <p>Called from the packet firewall when a frozen client does something
     * that makes vanilla resynchronise the inventory. Thread-safe by being a
     * plain field write: the tick loop reads it next tick.
     */
    public void remaskSoon(UUID id) {
        PlayerAuthState st = states.get(id);
        if (st == null || st.authenticated) return;
        st.maskStep = 1;                       // one more assertion, then settle
        st.maskDueTick = tickCounter + 2;
    }

    /** The line after "accepted": what to type next, and how long there is for it. */
    public void sendNextStepAfterAgreement(ServerPlayerEntity player) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null || st.authenticated) return;
        boolean registered = nameCache.containsKey(st.name.toLowerCase(Locale.ROOT));
        ChatGate.say(player, text(registered ? "agreement.acceptedNext" : "reminder.register",
                cfg.general.loginTimeoutSeconds), false);
    }

    private String ipOfPlayer(UUID id) {
        PlayerAuthState st = states.get(id);
        return st == null ? "unknown" : st.ip;
    }

    /** The pairs nobody has ruled on yet, strongest first. */
    public void adminLinkQueue(ServerCommandSource source) {
        submitAdmin(source, () -> {
            java.util.List<Database.LinkLedgerRow> all = db.allLinks(200);
            java.util.List<Database.LinkLedgerRow> open = new java.util.ArrayList<>();
            for (Database.LinkLedgerRow r : all) {
                if (LinkVerdict.of(r.verdict()) == LinkVerdict.NEW) open.add(r);
            }
            server.execute(() -> {
                if (open.isEmpty()) {
                    reply(source, "admin.links.queueEmpty");
                    return;
                }
                reply(source, "admin.links.queue", open.size());
                int shown = 0;
                for (Database.LinkLedgerRow r : open) {
                    if (shown++ >= 20) break;
                    reply(source, "admin.links.queueLine",
                            r.nameA() == null ? "?" : r.nameA(), r.nameB() == null ? "?" : r.nameB(),
                            r.bestGrade(), r.bestScore(), r.bestSignals() == null ? "-" : r.bestSignals());
                }
            });
        });
    }

    /**
     * Files a decision about one named pair. Both accounts must be registered:
     * the ledger is keyed by uuid, and inventing one for a name nobody has ever
     * used would create a row that can never match a real player.
     */
    public void adminSetLinkVerdict(ServerCommandSource source, String nameA, String nameB,
                                    LinkVerdict verdict, String note) {
        String by = source.getName();
        submitAdmin(source, () -> {
            try {
                UserRecord a = db.getByNameLower(nameA.toLowerCase(Locale.ROOT));
                if (a == null) {
                    replyLater(source, "admin.notFound", nameA);
                    return;
                }
                UserRecord b = db.getByNameLower(nameB.toLowerCase(Locale.ROOT));
                if (b == null) {
                    replyLater(source, "admin.notFound", nameB);
                    return;
                }
                if (a.uuid().equals(b.uuid())) {
                    replyLater(source, "admin.links.sameAccount");
                    return;
                }
                boolean ok = setLinkVerdict(UUID.fromString(a.uuid()), UUID.fromString(b.uuid()),
                        verdict, by, note);
                if (ok) {
                    replyLater(source, "admin.links.set", a.username(), b.username(),
                            verdict.colour() + verdict.label());
                    if (verdict == LinkVerdict.TRUSTED) replyLater(source, "admin.links.trustedNote");
                } else {
                    replyLater(source, "admin.links.setFailed");
                }
            } catch (SQLException e) {
                replyLater(source, "admin.dbError");
            }
        });
    }

    /** One decision applied to every pair an account is part of — the "these are all mine" shortcut. */
    public void adminSetAllLinkVerdicts(ServerCommandSource source, String name, LinkVerdict verdict, String note) {
        String by = source.getName();
        submitAdmin(source, () -> {
            try {
                UserRecord user = db.getByNameLower(name.toLowerCase(Locale.ROOT));
                if (user == null) {
                    replyLater(source, "admin.notFound", name);
                    return;
                }
                int n = setVerdictForAllLinksOf(UUID.fromString(user.uuid()), verdict, by, note);
                replyLater(source, "admin.links.setAll", verdict.colour() + verdict.label(), user.username(), n);
            } catch (SQLException e) {
                replyLater(source, "admin.dbError");
            }
        });
    }

    public void adminLinks(ServerCommandSource source, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        submitAdmin(source, () -> {
            try {
                UserRecord user = db.getByNameLower(lower);
                if (user == null) {
                    replyLater(source, "admin.notFound", name);
                    return;
                }
                java.util.List<Database.LinkLedgerRow> links = db.linksOf(user.uuid());
                java.util.List<Database.SightingRow> sightings = db.sightingsOfUuid(user.uuid());
                server.execute(() -> {
                    if (links.isEmpty()) {
                        reply(source, "admin.links.none", user.username());
                    } else {
                        reply(source, "admin.links.header", user.username(), links.size());
                        for (Database.LinkLedgerRow l : links) {
                            String other = user.uuid().equals(l.uuidA()) ? l.nameB() : l.nameA();
                            reply(source, "admin.links.line", other == null ? "?" : other, l.bestGrade(),
                                    l.bestScore(), l.bestSignals() == null ? "-" : l.bestSignals(), l.times(),
                                    TIME_FORMAT.format(Instant.ofEpochMilli(l.lastSeen())));
                            LinkVerdict v = LinkVerdict.of(l.verdict());
                            reply(source, "admin.links.verdict", v.colour() + v.label(),
                                    l.verdictBy() == null || l.verdictBy().isBlank() ? "—" : l.verdictBy(),
                                    l.verdictNote() == null || l.verdictNote().isBlank() ? "—" : l.verdictNote());
                        }
                    }
                    if (!sightings.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        int n = 0;
                        for (Database.SightingRow srow : sightings) {
                            if (n++ >= 5) break;
                            if (sb.length() > 0) sb.append("&8, &7");
                            sb.append(srow.subnet() == null ? "?" : srow.subnet());
                            if (srow.clientUuid() != null) sb.append(" &8launcher-uuid");
                            if (srow.hostUsed() != null) sb.append(" &8@").append(srow.hostUsed());
                            if (srow.channels() != null) sb.append(" &8mods:").append(srow.channels().split(",").length);
                        }
                        reply(source, "admin.links.device", sb.toString());
                    }
                });
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Links failed for {}", name, e);
                replyLater(source, "busy");
            }
        });
    }

    public void adminSessionsClear(ServerCommandSource source) {
        int n = sessions.size();
        sessions.clear();
        submitAdmin(source, db::clearSessions);
        reply(source, "admin.sessionsCleared", n);
    }

    public void adminReload(ServerCommandSource source) {
        submitAdmin(source, () -> {
            try {
                AuthConfig fresh = AuthConfig.loadOrCreate(configFile, w -> BastionAuth.LOGGER.warn("{}", w));
                server.execute(() -> {
                    this.cfg = fresh;
                    hasher.reconfigure(fresh.hashing.usePepper, new PasswordHasher.Params(
                            fresh.hashing.memoryKib, fresh.hashing.iterations, fresh.hashing.parallelism));
                    policy.reconfigure(fresh.passwordRules);
                    namePattern = compileNameRegex(fresh.general.usernameRegex);
                    rebuildLimiters(fresh);
                    reply(source, "admin.reloaded");
                    BastionAuth.LOGGER.info("Configuration reloaded");
                });
            } catch (Exception e) {
                BastionAuth.LOGGER.error("Config reload failed", e);
                server.execute(() -> reply(source, "admin.reloadFailed", String.valueOf(e.getMessage())));
            }
        });
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Marks the player authenticated and lifts the freeze. Server thread only. */
    private void finishAuth(ServerPlayerEntity player, PlayerAuthState st, Text message, Text extraLine) {
        if (states.get(st.uuid) != st) return; // player reconnected meanwhile
        st.authenticated = true;
        sessions.remove(st.uuid);
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
        if (p == null) return;
        if (st.invisApplied) {
            p.removeStatusEffect(StatusEffects.INVISIBILITY);
            st.invisApplied = false;
        }
        p.networkHandler.sendPacket(new ClearTitleS2CPacket(true));
        // The inventory was blanked on the client while they were frozen; the
        // account is now proven, so the real one goes back on screen.
        InventoryMask.reveal(p);
        ChatGate.say(p, message, false);
        if (extraLine != null) ChatGate.say(p, extraLine, false);
        // What the server said to this player while they were on the login
        // screen — transfers, statements, replies, chat — now that it is theirs.
        ChatGate.release(p, this::heldHeader);
        // Permissions were reported as NONE while frozen (PermissionFreezeMixin);
        // the command tree the client holds must be rebuilt with the real ones.
        try {
            server.getCommandManager().sendCommandTree(p);
        } catch (Exception ignored) {
        }
        // The plugin-channel list (client mods) arrives a few seconds into
        // the play phase; capture it once it has settled and deepen the link
        // analysis with it.
        channelCaptureDue.put(st.uuid, tickCounter + cfg.linking.channelsDelaySeconds * 20L);
        BastionAuth.LOGGER.info("{} authenticated", st.name);
    }

    /** The joiner's link material as known right now (device hash must exist). */
    private AccountLinker.Material materialOf(UUID uuid, String ipHmac, String pwFp) {
        String deviceHash = DeviceFingerprint.cached(uuid);
        if (deviceHash == null) return null;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
        String profile = p == null ? null : DeviceFingerprint.computeProfile(p, secrets);
        DeviceFingerprint.PreLogin pre = DeviceFingerprint.preLoginOf(uuid);
        String clientUuid = DeviceFingerprint.informativeClientUuid(uuid, pre);
        String host = pre == null || pre.host() == null || pre.host().isEmpty() ? null : pre.host();
        String channels = p == null ? null : DeviceFingerprint.channelsOf(p);
        String channelsHash = channels == null ? null : secrets.deviceHmac("channels|" + channels);
        return new AccountLinker.Material(deviceHash, profile, clientUuid, host, channelsHash,
                DeviceFingerprint.channelsAreBaseline(channels), ipHmac, pwFp);
    }

    /** Server-thread: captures the settled channel list, stores it and re-runs the analysis. */
    private void captureChannels(UUID uuid) {
        PlayerAuthState st = states.get(uuid);
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
        if (st == null || p == null || !st.authenticated) return;
        String deviceHash = DeviceFingerprint.cached(uuid);
        if (deviceHash == null) return;
        String channels = DeviceFingerprint.channelsOf(p);
        if (channels == null) return;
        String channelsHash = secrets.deviceHmac("channels|" + channels);
        AccountLinker.Material m = materialOf(uuid, secrets.ipHmac(st.ip), db.pwFpOf(uuid.toString()));
        if (m == null) return;
        final String fChannels = channels;
        final String ip = st.ip;
        final String name = st.name;
        try {
            executor.execute(() -> {
                db.updateFingerprintExtras(uuid.toString(), deviceHash, null, null, null, channelsHash, fChannels);
                analyseAndReport(uuid, name, m, ip);
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** Netty-thread tap from the packet firewall: one debug line per player per 10 s. */
    public void notePacketDropped(ServerPlayerEntity player, net.minecraft.network.packet.Packet<?> packet) {
        long now = System.nanoTime();
        Long last = lastDropLogNanos.get(player.getUuid());
        if (last != null && now - last < 10_000_000_000L) return;
        lastDropLogNanos.put(player.getUuid(), now);
        BastionAuth.LOGGER.debug("Dropped {} from unauthenticated {}",
                packet.getClass().getSimpleName(), player.getGameProfile().name());
    }

    public TwoFactor twoFactor() {
        return twoFactor;
    }

    public Codeword codeword() {
        return codeword;
    }

    public boolean isAuthenticated(UUID uuid) {
        PlayerAuthState st = states.get(uuid);
        return st != null && st.authenticated;
    }

    // ------------------------------------------------------------------
    // Second factor: setup, disable, backup codes (authenticated players)
    // ------------------------------------------------------------------

    private PlayerAuthState authenticatedState(ServerPlayerEntity player) {
        PlayerAuthState st = states.get(player.getUuid());
        if (st == null || !st.authenticated) {
            send(player, "mustLogin");
            return null;
        }
        return st;
    }

    public void twoFaStatus(ServerPlayerEntity player) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        if (twoFactor.isEnabled(st.uuid)) {
            submit(player, st, () -> {
                int left = twoFactor.backupCodesLeft(st.uuid);
                long since = twoFactor.enabledAt(st.uuid);
                sendLater(player, st, "twofa.status.on", since > 0 ? TIME_FORMAT.format(Instant.ofEpochMilli(since)) : "?", left);
            });
        } else {
            send(player, "twofa.status.off");
        }
    }

    /** Step one: a seed, shown as text and as a QR map; nothing is stored until confirmed. */
    public void twoFaBegin(ServerPlayerEntity player) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        if (!cfg.twoFactor.enabled) {
            send(player, "twofa.disabledByConfig");
            return;
        }
        if (twoFactor.isEnabled(st.uuid)) {
            send(player, "twofa.alreadyOn");
            return;
        }
        TwoFactor.Setup setup = twoFactor.begin(st.uuid);
        String uri = dev.bastionauth.crypto.Totp.otpauth(cfg.twoFactor.issuer, st.name, setup.base32());
        boolean map = QrMap.give(player, uri, cfg.message("twofa.mapTitle"));
        send(player, "twofa.setup.head");
        send(player, "twofa.setup.secret", dev.bastionauth.crypto.Totp.grouped(setup.base32()));
        send(player, map ? "twofa.setup.map" : "twofa.setup.noMap");
        send(player, "twofa.setup.confirm");
        submitAudit("2FA_SETUP_BEGIN", st.uuid, st.name, Secrets.maskIp(st.ip), null);
    }

    /** Step two: the app has the seed — prove it with one code, receive the backup codes. */
    public void twoFaConfirm(ServerPlayerEntity player, String code) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        if (twoFactor.pendingOf(st.uuid) == null) {
            send(player, "twofa.noPending");
            return;
        }
        if (!st.busy.compareAndSet(false, true)) {
            send(player, "inProgress");
            return;
        }
        submit(player, st, () -> {
            try {
                java.util.List<String> codes = twoFactor.confirm(st.uuid, code);
                if (codes == null) {
                    sendLater(player, st, "twofa.confirmWrong");
                    return;
                }
                db.audit(System.currentTimeMillis(), "2FA_ENABLED", st.uuid.toString(), st.name, Secrets.maskIp(st.ip), null);
                final java.util.List<String> fCodes = codes;
                server.execute(() -> {
                    if (states.get(st.uuid) != st) return;
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
                    if (p == null) return;
                    send(p, "twofa.enabled");
                    showBackupCodes(p, fCodes);
                });
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("2FA confirm failed for {}", st.name, e);
                sendLater(player, st, "busy");
            } finally {
                st.busy.set(false);
            }
        });
    }

    /** Backup codes in chat once, and in a book that can be put in a chest. */
    private void showBackupCodes(ServerPlayerEntity p, java.util.List<String> codes) {
        send(p, "twofa.backup.head");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < codes.size(); i++) {
            line.append("&f").append(codes.get(i)).append(i % 2 == 1 ? "\n" : "   ");
        }
        for (String l : line.toString().split("\\n")) {
            if (!l.isBlank()) ChatGate.say(p, Text.literal(AuthConfig.colorize("&7  " + l)), false);
        }
        send(p, "twofa.backup.tail");
        try {
            StringBuilder page = new StringBuilder("Резервные коды 2FA\n\n");
            for (String c : codes) page.append(c).append("\n");
            page.append("\nКаждый работает один раз.\nНе показывайте никому.");
            ItemStack book = new ItemStack(net.minecraft.item.Items.WRITTEN_BOOK);
            book.set(net.minecraft.component.DataComponentTypes.WRITTEN_BOOK_CONTENT,
                    new net.minecraft.component.type.WrittenBookContentComponent(
                            net.minecraft.text.RawFilteredPair.of("Резервные коды"), "BastionAuth", 0,
                            java.util.List.of(net.minecraft.text.RawFilteredPair.of(Text.literal(page.toString()))), true));
            if (!p.getInventory().insertStack(book)) p.dropItem(book, false);
        } catch (Throwable t) {
            BastionAuth.LOGGER.warn("Backup-code book failed: {}", t.toString());
        }
    }

    /** Switching the factor off needs a live code (or a backup code) — and the codeword, when set. */
    public void twoFaDisable(ServerPlayerEntity player, String code) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        if (!twoFactor.isEnabled(st.uuid)) {
            send(player, "twofa.status.off");
            return;
        }
        if (codeword.protects(st.uuid)) {
            send(player, "codeword.required");
            return;
        }
        if (!st.busy.compareAndSet(false, true)) {
            send(player, "inProgress");
            return;
        }
        submit(player, st, () -> {
            try {
                TwoFactor.Check check = twoFactor.check(st.uuid, code);
                if (check == TwoFactor.Check.WRONG) {
                    db.audit(System.currentTimeMillis(), "2FA_DISABLE_FAIL", st.uuid.toString(), st.name, Secrets.maskIp(st.ip), null);
                    sendLater(player, st, "twofa.confirmWrong");
                    return;
                }
                twoFactor.disable(st.uuid);
                db.audit(System.currentTimeMillis(), "2FA_DISABLED", st.uuid.toString(), st.name, Secrets.maskIp(st.ip), null);
                sendLater(player, st, "twofa.disabled");
            } finally {
                st.busy.set(false);
            }
        });
    }

    /** New backup codes against a live code; the old set is void. */
    public void twoFaNewBackupCodes(ServerPlayerEntity player, String code) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        if (!twoFactor.isEnabled(st.uuid)) {
            send(player, "twofa.status.off");
            return;
        }
        if (!st.busy.compareAndSet(false, true)) {
            send(player, "inProgress");
            return;
        }
        submit(player, st, () -> {
            try {
                if (twoFactor.check(st.uuid, code) != TwoFactor.Check.OK) {
                    sendLater(player, st, "twofa.confirmWrong");
                    return;
                }
                java.util.List<String> codes = twoFactor.regenerateBackup(st.uuid);
                db.audit(System.currentTimeMillis(), "2FA_BACKUP_REGEN", st.uuid.toString(), st.name, Secrets.maskIp(st.ip), null);
                server.execute(() -> {
                    if (states.get(st.uuid) != st) return;
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
                    if (p != null && codes != null) showBackupCodes(p, codes);
                });
            } finally {
                st.busy.set(false);
            }
        });
    }

    // ------------------------------------------------------------------
    // Codeword
    // ------------------------------------------------------------------

    public void codewordStatus(ServerPlayerEntity player) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        if (!codeword.isSet(st.uuid)) {
            send(player, "codeword.status.off");
        } else if (codeword.isUnlocked(st.uuid)) {
            send(player, "codeword.status.open", codeword.unlockedSecondsLeft(st.uuid));
        } else {
            send(player, "codeword.status.locked", cfg.codeword.unlockMinutes);
        }
    }

    public void codewordSet(ServerPlayerEntity player, String word) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        if (!cfg.codeword.enabled) {
            send(player, "codeword.disabledByConfig");
            return;
        }
        if (codeword.protects(st.uuid)) {
            // Changing the word is one of the things the word protects.
            send(player, "codeword.required");
            return;
        }
        String problem = Codeword.shapeProblem(word, cfg.codeword.minLength, cfg.codeword.maxLength, st.name);
        if (problem != null) {
            send(player, problem, cfg.codeword.minLength, cfg.codeword.maxLength);
            return;
        }
        if (!st.busy.compareAndSet(false, true)) {
            send(player, "inProgress");
            return;
        }
        submit(player, st, () -> {
            try {
                codeword.set(st.uuid, word);
                db.audit(System.currentTimeMillis(), "CODEWORD_SET", st.uuid.toString(), st.name, Secrets.maskIp(st.ip), null);
                sendLater(player, st, "codeword.set", cfg.codeword.unlockMinutes);
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Codeword set failed for {}", st.name, e);
                sendLater(player, st, "busy");
            } finally {
                st.busy.set(false);
            }
        });
    }

    /** Speaking the word opens the window; {@code /слово снять} needs the word too. */
    public void codewordSpeak(ServerPlayerEntity player, String word, boolean remove) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        if (!codeword.isSet(st.uuid)) {
            send(player, "codeword.status.off");
            return;
        }
        if (!st.busy.compareAndSet(false, true)) {
            send(player, "inProgress");
            return;
        }
        submit(player, st, () -> {
            try {
                long now = System.currentTimeMillis();
                if (!codeword.verify(st.uuid, word)) {
                    db.audit(now, "CODEWORD_FAIL", st.uuid.toString(), st.name, Secrets.maskIp(st.ip), null);
                    sendLater(player, st, "codeword.wrong");
                    return;
                }
                if (remove) {
                    codeword.remove(st.uuid);
                    db.audit(now, "CODEWORD_REMOVED", st.uuid.toString(), st.name, Secrets.maskIp(st.ip), null);
                    sendLater(player, st, "codeword.removed");
                } else {
                    db.audit(now, "CODEWORD_OK", st.uuid.toString(), st.name, Secrets.maskIp(st.ip), null);
                    sendLater(player, st, "codeword.unlocked", cfg.codeword.unlockMinutes);
                }
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Codeword op failed for {}", st.name, e);
                sendLater(player, st, "busy");
            } finally {
                st.busy.set(false);
            }
        });
    }

    // ------------------------------------------------------------------
    // The account's own trail and the panic lock
    // ------------------------------------------------------------------

    public void showHistory(ServerPlayerEntity player) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        submit(player, st, () -> {
            java.util.List<Database.AuditRow> rows = db.auditOf(st.uuid.toString(), 40);
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (Database.AuditRow r : rows) {
                String what = switch (r.event()) {
                    case "LOGIN_OK" -> "вход по паролю" + ("new device".equals(r.detail()) ? " (новое устройство)" : "");
                    case "SESSION_RESUME" -> "вход по сессии";
                    case "LOGIN_FAIL" -> "неверный пароль";
                    case "2FA_FAIL" -> "неверный код 2FA";
                    case "2FA_ENABLED" -> "2FA включена";
                    case "2FA_DISABLED" -> "2FA выключена";
                    case "2FA_BACKUP_USED" -> "использован резервный код";
                    case "CHANGEPW" -> "смена пароля";
                    case "CHANGEPW_FAIL" -> "смена пароля: неверный старый";
                    case "CODEWORD_SET" -> "кодовое слово задано";
                    case "CODEWORD_REMOVED" -> "кодовое слово снято";
                    case "CODEWORD_FAIL" -> "неверное кодовое слово";
                    case "LOGOUT" -> "выход";
                    case "PANIC_LOCK" -> "аккаунт заблокирован вами";
                    case "ADMIN_UNLOCK" -> "разблокирован администрацией";
                    case "REGISTER" -> "регистрация";
                    default -> null;
                };
                if (what == null) continue;
                lines.add("&8" + TIME_FORMAT.format(Instant.ofEpochMilli(r.at())) + " &7" + what
                        + (r.ipMasked() == null ? "" : " &8· " + r.ipMasked()));
                if (lines.size() >= 12) break;
            }
            server.execute(() -> {
                if (states.get(st.uuid) != st) return;
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
                if (p == null) return;
                send(p, "history.head");
                for (String l : lines) ChatGate.say(p, Text.literal(AuthConfig.colorize(l)), false);
                if (lines.isEmpty()) send(p, "history.empty");
            });
        });
    }

    /** The citizen locks their own door; only an operator opens it again. */
    public void panicLock(ServerPlayerEntity player) {
        PlayerAuthState st = authenticatedState(player);
        if (st == null) return;
        submit(player, st, () -> {
            try {
                db.lockAccount(st.uuid.toString(), System.currentTimeMillis() + PANIC_LOCK_MS);
                db.audit(System.currentTimeMillis(), "PANIC_LOCK", st.uuid.toString(), st.name, Secrets.maskIp(st.ip), null);
                sessions.remove(st.uuid);
                db.deleteSession(st.uuid.toString());
                server.execute(() -> {
                    // Mark the state unauthenticated BEFORE the kick: the
                    // disconnect handler stores an IP session for every
                    // authenticated player, which used to re-open the door
                    // the lock had just closed.
                    if (states.get(st.uuid) == st) st.authenticated = false;
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
                    if (p != null) disconnect(p, text("lock.done"));
                });
            } catch (SQLException e) {
                BastionAuth.LOGGER.error("Panic lock failed for {}", st.name, e);
                sendLater(player, st, "busy");
            }
        });
    }

    /** Flat audit lines for another mod's moderation screen. See {@link BastionAuth#authHistory}. */
    public java.util.List<String> historyLines(UUID uuid, int limit) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Database.AuditRow row : db.auditOf(uuid.toString(), Math.max(1, Math.min(limit, 100)))) {
            out.add(row.at() + "|" + row.event() + "|"
                    + (row.ipMasked() == null ? "" : row.ipMasked()) + "|"
                    + (row.detail() == null ? "" : row.detail()));
        }
        return out;
    }

    private boolean isOperator(ServerPlayerEntity player) {
        try {
            return server.getPlayerManager().isOperator(player.getPlayerConfigEntry());
        } catch (Exception e) {
            return false;
        }
    }

    private ServerPlayerEntity findOnlineByLower(String lower) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.getGameProfile().name().toLowerCase(Locale.ROOT).equals(lower)) return p;
        }
        return null;
    }

    private void disconnect(ServerPlayerEntity player, Text reason) {
        player.networkHandler.disconnect(reason);
    }

    private void submit(ServerPlayerEntity player, PlayerAuthState st, Runnable job) {
        try {
            executor.execute(job);
        } catch (RejectedExecutionException e) {
            st.busy.set(false);
            send(player, "busy");
        }
    }

    private void submitAdmin(ServerCommandSource source, Runnable job) {
        try {
            executor.execute(job);
        } catch (RejectedExecutionException e) {
            reply(source, "busy");
        }
    }

    private void submitAudit(String event, UUID uuid, String name, String ipMasked, String detail) {
        try {
            executor.execute(() -> db.audit(System.currentTimeMillis(), event,
                    uuid == null ? null : uuid.toString(), name, ipMasked, detail));
        } catch (RejectedExecutionException ignored) {
            // Audit is best-effort under load.
        }
    }

    private void send(ServerPlayerEntity player, String key, Object... args) {
        ChatGate.say(player, text(key, args), false);
    }

    /** Sends a message on the server thread if the same connection is still present. */
    private void sendLater(ServerPlayerEntity player, PlayerAuthState st, String key, Object... args) {
        server.execute(() -> {
            if (states.get(st.uuid) != st) return;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(st.uuid);
            if (p != null) send(p, key, args);
        });
    }

    private void reply(ServerCommandSource source, String key, Object... args) {
        ServerPlayerEntity p = source.getPlayer();
        if (p != null) ChatGate.speak(p, () -> source.sendFeedback(() -> text(key, args), false));
        else source.sendFeedback(() -> text(key, args), false);
    }

    /** The line above what a player missed while on the login screen. */
    private List<Text> heldHeader(int[] counts) {
        List<Text> lines = new ArrayList<>();
        lines.add(text("gate.held", counts[0]));
        if (counts[1] > 0) lines.add(text("gate.heldDropped", counts[1]));
        return lines;
    }

    private void replyLater(ServerCommandSource source, String key, Object... args) {
        server.execute(() -> reply(source, key, args));
    }

    public Text text(String key, Object... args) {
        return Text.literal(AuthConfig.colorize(cfg.message(key, args)));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Loopback / unknown addresses carry no per-player identity: behind a proxy
     * without IP forwarding every player shows up as {@code 127.0.0.1}, which
     * would let anyone resume anyone else's session. Never auto-resume for those.
     */
    private static boolean isBindableIp(String ip) {
        return ip != null && !"unknown".equals(ip)
                && !"127.0.0.1".equals(ip) && !"::1".equals(ip)
                && !"0:0:0:0:0:0:0:1".equals(ip);
    }

    private static boolean isUniqueViolation(SQLException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("UNIQUE");
    }
}
