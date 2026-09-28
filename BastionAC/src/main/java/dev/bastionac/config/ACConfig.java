package dev.bastionac.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bastionac.util.SafeFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * BastionAC configuration ({@code config/bastionac/config.json}).
 *
 * <p>Per-check tuning lives under {@code checks.<name>}: {@code enabled},
 * {@code weight} (VL added per confirmed violation), {@code alertVl}
 * (staff alert threshold), {@code mitigateVl} (setback/cancel threshold,
 * {@code -1} disables mitigation for that check) and {@code decayPerSecond}.
 * Unknown keys are ignored; missing keys get defaults and are written back.
 * A corrupted file is backed up and replaced with defaults.
 */
public final class ACConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public General general = new General();
    public Messages messages = new Messages();
    public Punishment punishment = new Punishment();
    public Map<String, CheckCfg> checks = new LinkedHashMap<>();

    public static final class General {
        /** Ops receive in-game alerts by default (each admin can /bac alerts off). */
        public boolean alertsToOps = true;
        /** Minimum seconds between repeated alerts for the same player+check. */
        public int alertCooldownSeconds = 5;
        /** Allow movement setbacks (teleport back to last good position). */
        public boolean setbackEnabled = true;
        /** At most one setback per player per this many ticks (anti tp-fight). */
        public int setbackMinIntervalTicks = 10;
        /**
         * Snap the player back on the FIRST confirmed movement violation
         * instead of waiting for VL to reach mitigateVl. This is the fast,
         * v1-style reaction; the teleport handshake still pauses checks until
         * the client confirms, so it does not cause cascading re-triggers.
         */
        public boolean instantMovementSetback = true;
        /** Multiplier on the Speed check ceiling. Raise it if legit fast
         *  movement (aggressive bunny-hopping) is being flagged; lower it to
         *  tighten. 1.0 is the tuned default; the floor is clamped to 0.9 so a
         *  too-low value can never flag plain sprinting. */
        public double speedToleranceMultiplier = 1.0;
        /**
         * Shortest average gap between movement packets the Timer check accepts.
         * A vanilla client sends one per tick (50ms); this is measured on
         * ARRIVAL, so network bufferbloat delivers packets in bursts and
         * compresses the average. 40ms leaves ~20% of headroom for that jitter
         * while still catching any timer above 1.25x. Lower it to tighten.
         */
        public int timerMinIntervalMs = 40;
        /**
         * Hard ceiling on attacks per second. Anything above it is refused
         * outright (the hit never lands) and feeds VL at a rate proportional to
         * the overshoot, so a blatant clicker reaches kickVl in about a second
         * while a borderline one gets many seconds to prove itself.
         *
         * <p>Reference points: ordinary clicking is 5-8/s, jitter clicking tops
         * out around 13-14/s, butterfly clicking reaches 15-20/s, and the
         * vanilla client itself can never send more than 20/s (one attack per
         * client tick) — so above 20 this check is dead by definition, and the
         * clamp reflects that. 14 draws the line just past what a hand does
         * without a butterfly/drag technique.
         */
        public int maxCps = 14;
        /** Ticks of full exemption after join / respawn / dimension change. */
        public int joinGraceTicks = 60;
        /**
         * Anti-KB: server ticks after a knockback packet during which the player's
         * horizontal displacement is integrated and compared against the minimum the
         * impulse must produce. 4 ticks lets the impulse play out without letting a
         * cheat "pay it back" later with fake movement.
         */
        public int velocityVerifyTicks = 4;
        /**
         * Anti-KB: fraction of the expected knockback displacement below which a
         * transaction counts as suppressed. 0.6 absorbs legitimate counter-movement
         * (the player running into the hit) while still catching any real reduction.
         */
        public double velocityMinRatio = 0.6;
        /**
         * Anti-KB: how many consecutive suppressed knockback transactions confirm a
         * violation. One can be a lag spike landing mid-window; three in a row cannot.
         */
        public int velocityConfirmStreak = 3;
        /**
         * Blink: movement-packet budget refills at one token per 50ms of real time.
         * A burst that overdraws the bucket by more than this many packets is a
         * candidate Blink flush; the IAT variance of that burst is then examined.
         */
        public int blinkBurstOverdraw = 8;
        /**
         * Blink: a burst whose inter-arrival-time standard deviation is below this
         * many milliseconds is physically impossible for real routing (bufferbloat
         * keeps measurable jitter) and confirms a synthetic client-side flush.
         */
        public double blinkMaxIatStdMs = 1.0;
        /**
         * NoSlow: ticks of inertia after item-use starts during which the speed
         * penalty has not fully applied yet. The check stays silent for this long.
         */
        public int noSlowGraceTicks = 5;
        /**
         * NoSlow: fraction of the sprint ceiling a using-item player may not exceed
         * once the grace passes. Vanilla applies a hard 0.2x multiplier; 0.35 leaves
         * room for the residual momentum that bleeds off over the first ticks.
         */
        public double noSlowMaxFactor = 0.35;
        /** Log every confirmed violation to console (alerts are logged regardless). */
        public boolean logViolationsToConsole = true;
        /** Master switch for kicking on {@code checks.<name>.kickVl}. */
        public boolean kickEnabled = true;
        /**
         * Schema revision of this file. Merging alone cannot fix a threshold that
         * has already been written to disk — an existing key always wins — so a
         * bump lets a release re-apply the defaults for the checks it retuned.
         * Never edit by hand: lowering it re-runs the migration.
         */
        public int configVersion = 0;
        /**
         * Logs the raw geometry behind every block placement and every NoSlow
         * sample. Use it to tune a check against what a client actually sends
         * instead of against a guess; it is noisy, so leave it off in production.
         */
        public boolean debugChecks = false;
        /** Enables server-side shadow telemetry only; it never creates a violation. */
        public boolean shadowTelemetryEnabled = true;
        /** Console summaries are opt-in; shadow measurements remain local and policy-neutral. */
        public boolean shadowConsoleReports = false;
        /** Period between compact operational summaries when console reports are enabled (20 ticks = roughly one second). */
        public int shadowReportIntervalTicks = 1200;
        /** Prepared ceiling for expensive movement environment scans in one server tick. */
        public int movementScansPerTickBudget = 2000;
        /** Disabled by default: telemetry must be observed before any coalescing is enabled. */
        public boolean enforceMovementScanBudget = false;
        /** Per-player move-packet count in a tick that warrants a shadow pressure note. */
        public int shadowPerPlayerMoveWarn = 12;
    }

    /** Current schema revision — see {@link General#configVersion}. */
    public static final int CONFIG_VERSION = 10;

    /**
     * Revision at which the retune below applies. Later bumps only top up the
     * ban-exclusion list, so an admin's own thresholds are never reset twice.
     */
    private static final int RETUNE_VERSION = 2;

    /**
     * Checks whose thresholds revision 2 re-applies. These were retuned after the
     * 2.13 heuristics were found to auto-ban legitimate players (an insta-mined
     * block left a mining session armed forever, and every later attack or hotbar
     * scroll was read as Packet Mine).
     */
    private static final java.util.List<String> V2_RETUNED = java.util.List.of(
            "packetmine", "fastbreak", "fastplace", "scaffold", "criticals",
            "blink", "velocity", "noslow", "autoaction", "autoclick");

    /**
     * Customisable anti-cheat text. Strings accept {@code &}-colour codes and
     * placeholders {@code %name%}, {@code %check%}. {@code \n} makes a new line.
     */
    public static final class Messages {
        /** Announce the kick to everyone (generic, without the check name). */
        public boolean announceKickToEveryone = true;
        /** Show the tripped check to the kicked player (helps cheaters — off by default). */
        public boolean revealCheckToPlayer = false;

        public String kickScreen = "&c&lВы были отключены\n\n&7Система защиты обнаружила недопустимую активность.\n&7Если это ошибка — обратитесь к администрации.";
        public String kickBroadcast = "&8[&cBAC&8] &e%name% &7отключён системой защиты &8(&7%check%&8)";
        public String alertPrefix = "&8[&cBAC&8]";
    }

    /**
     * Progressive auto-tempban. When a player collects {@code alertsToBan}
     * staff alerts within {@code alertWindowMinutes}, they are temp-banned via
     * the vanilla ban list. The duration escalates by how many bans they
     * already have within {@code banHistoryHours}, stepping through
     * {@code banStepsHours} (e.g. 1h → 6h → 24h → 1 week).
     */
    /**
     * Only checks with a live track record drive the automatic tempban. Every
     * heuristic and every check introduced in 2.14 alerts staff and mitigates,
     * but never bans on its own: an unproven rule that reaches the ban list
     * costs a real player their session before anyone reads the log. Move a
     * check out of this list once its alerts have been clean for a while.
     */
    /**
     * Checks that never feed the auto-ban (per-check ladder OR chain). v7
     * shrank this list on purpose: nuker, fastbreak, fastplace, scaffold,
     * packetmine, noslow, criticals and blink are precise enough that three
     * alerts of the same one inside the window are a ban on their own — that
     * is the "one module → its own ban" rule. What stays excluded is either
     * effect-sensitive (bigmove, timer, velocity), an observe-only heuristic
     * (groundspoof, noswing, autoclick, autoaction, gcd) or a
     * statistic that needs a human (xray).
     */
    static final java.util.List<String> BAN_EXCLUDED_DEFAULT = java.util.List.of(
            "bigmove", "timer", "groundspoof", "noswing", "autoclick", "autoaction",
            "velocity", "gcd", "xray",
            // v8 arrivals: precise, but unproven on this server. They alert,
            // mitigate and feed the chain from day one; they earn the ban
            // ladder once the logs show they stay quiet on honest players.
            "vehicle", "airplace", "invmove", "brandspoof", "packetorder",
            // v10 arrivals (the Wurst pass) that stay out of the ban for now:
            // aim is a statistic, autototem a timing against a jittery ping,
            // autoplace also fires on Litematica's easy place, jesus has not
            // been watched on real terrain yet. Step and badrotation are not
            // here: a vanilla client cannot send either, at all.
            "aim", "autoplace", "jesus", "autototem");

    /** Observe-only heuristics: v7 forces them back to mitigate/kick = -1, as the README promises. */
    static final java.util.List<String> OBSERVE_ONLY = java.util.List.of(
            "autoclick", "autoaction", "noswing", "groundspoof", "multitarget", "gcd", "xray");

    public static final class Punishment {
        public boolean autoTempbanEnabled = true;
        /** Alerts within the window that trigger a temp-ban. Tightened from 3:
         *  the testers' logs showed a flagging cheater needed far too many
         *  distinct staff alerts before anything bit. */
        public int alertsToBan = 2;
        public int alertWindowMinutes = 50;
        /** How far back prior bans count for escalation. Two weeks: the old
         *  72h window let a banned player's ladder fully reset inside a week. */
        public int banHistoryHours = 336;
        /** Escalation ladder, tightened and extended: 2h → 12h → 48h → 2wk → 30d. */
        public java.util.List<Integer> banStepsHours = new java.util.ArrayList<>(java.util.List.of(2, 12, 48, 336, 720));
        /** Checks whose alerts never count toward the auto-tempban (FP-prone / mod-sensitive). */
        public java.util.List<String> banExcludedChecks = new java.util.ArrayList<>(BAN_EXCLUDED_DEFAULT);
        public boolean broadcastBan = true;
        public String banReason = "&cАвтоблокировка BastionAC за читы. Если это ошибка — напишите администрации.";

        /**
         * Unconditional automatic permanent ban. Reserved exclusively for
         * catastrophic, unambiguous threats (crash exploits, packet flooding,
         * NBT bombs) where waiting for a human means losing the server. Any
         * doubt — temp-ban and page the staff instead.
         */
        public boolean catastrophicPermabanEnabled = true;

        /**
         * Staff (permission level 3+) are never auto-banned — not by the alert
         * ladder, not by a chain, not by the catastrophic filters. They are
         * still kicked and alerted like everyone else. A false positive on
         * the only admin online must not lock the server's keys away.
         */
        public boolean exemptOperators = true;

        /** Chain (EDR) policy — see AttackChainDetector. */
        /** Minutes a chain stays open after its last event. */
        public int chainWindowMinutes = 60;
        /** Distinct checks at alert level that put a player on the watch list (no punishment). */
        public int chainWatchDistinctChecks = 2;
        /** Distinct ban-eligible checks at alert level that ban through the normal ladder. */
        public int chainBanDistinctChecks = 3;
    }

    public static final class CheckCfg {
        public boolean enabled = true;
        public double weight = 1.0;
        public double alertVl = 5.0;
        public double mitigateVl = 8.0;
        public double kickVl = 15.0;
        public double decayPerSecond = 0.5;

        public CheckCfg() {}

        public CheckCfg(double weight, double alertVl, double mitigateVl, double kickVl, double decayPerSecond) {
            this.weight = weight;
            this.alertVl = alertVl;
            this.mitigateVl = mitigateVl;
            this.kickVl = kickVl;
            this.decayPerSecond = decayPerSecond;
        }
    }

    /**
     * Built-in defaults; tuned so a legit player never reaches alertVl.
     * Observe-only checks (mitigateVl = -1) also keep kickVl = -1: they are
     * the most false-positive-prone, so they must never auto-kick.
     */
    private static Map<String, CheckCfg> defaultChecks() {
        Map<String, CheckCfg> m = new LinkedHashMap<>();
        m.put("speed",        new CheckCfg(1.0, 6, 8, 20, 0.75));
        m.put("fly",          new CheckCfg(1.0, 5, 7, 20, 0.75));
        // Wall-climb (Spider): high-confidence — a jump can only gain ~1.25 blocks.
        m.put("spider",       new CheckCfg(3.0, 3, 4, 8, 0.5));
        // decay must stay under the check's max flag rate (~1 per window, and the
        // window restarts on every flag) or VL can never reach alertVl at all.
        m.put("timer",        new CheckCfg(1.0, 6, 9, 20, 0.3));
        m.put("bigmove",      new CheckCfg(5.0, 5, 5, 20, 0.5));
        m.put("groundspoof",  new CheckCfg(1.0, 5, -1, -1, 0.5));
        // NoFall is outcome-based since v9: it compares the descent the server
        // measured itself against whether vanilla ever raised a fall-damage
        // event. That is a fact about what happened, not a guess about how, so
        // it is allowed to act like one.
        m.put("nofall",       new CheckCfg(2.0, 3, 5, 12, 0.4));
        m.put("reach",        new CheckCfg(1.0, 3, 5, 7, 0.5));
        m.put("angle",        new CheckCfg(1.0, 4, 7, 10, 0.75));
        m.put("walls",        new CheckCfg(1.0, 3, 6, 8, 0.5));
        m.put("multitarget",  new CheckCfg(1.0, 4, -1, -1, 0.75));
        m.put("useattack",    new CheckCfg(1.0, 2, 3, 10, 0.5));
        m.put("noswing",      new CheckCfg(1.0, 4, -1, -1, 0.75));
        m.put("cps",          new CheckCfg(1.0, 4, 6, 15, 1.0));
        // Both need two buffered events, and FastBreak only judges breaks vanilla
        // itself accepts (it refuses anything under delta*(ticks+1) >= 0.7), so a
        // merely delayed packet no longer counts as evidence.
        m.put("fastbreak",    new CheckCfg(1.0, 3, 5, 12, 0.5));
        // Nuker: breaking rate and aim. Insta-mined blocks (grass, torches, crops)
        // carry no timing signal at all — the only thing that separates a nuker
        // sweeping them from a player is HOW MANY per second and whether they are
        // even in front of the camera.
        m.put("nuker",        new CheckCfg(1.0, 3, 5, 12, 0.5));
        m.put("fastplace",    new CheckCfg(1.0, 4, 6, 15, 1.0));
        // Rhythm heuristics: strong evidence, but heuristics all the same, so
        // they are observe-only out of the box (alert staff, never auto-act).
        // One flag needs 2 suspicious windows = ~50 clicks, so alertVl 3 means
        // roughly 150 machine-even clicks before staff are told. Decay sits at
        // the floor because the flag rate is low by construction — evidence
        // this expensive to collect must not evaporate between bursts.
        m.put("autoclick",    new CheckCfg(1.0, 3, -1, -1, 0.05));
        m.put("autoaction",   new CheckCfg(1.0, 3, -1, -1, 0.05));
        // Anti-KB: high confidence once the observation window closes — a player
        // who consistently ignores server knockback is running Velocity. Mitigation
        // is a cancel of the offending movement, not a setback (the player may be
        // mid-air from a real earlier knockback).
        m.put("velocity",     new CheckCfg(2.0, 3, 5, 12, 0.5));
        // Packet Mine: a break armed or finished from outside interaction range,
        // with a 3-block margin on top and two buffered events behind it.
        m.put("packetmine",   new CheckCfg(2.0, 4, 6, 12, 0.5));
        // Criticals: a crit whose fall the server's collision scan contradicts.
        // Two buffered hits per flag, so a single lag-batched jump cannot convict.
        m.put("criticals",    new CheckCfg(1.0, 3, 5, 12, 0.5));
        // Blink: a zero-variance packet burst is a hard protocol signal, but a
        // single one can be an OS-level flush, so confirm via a short streak.
        // Mitigation is a setback to the last pre-burst position.
        m.put("blink",        new CheckCfg(2.0, 3, 5, 12, 0.5));
        // NoSlow: scalar speed while using an item. High confidence once the
        // inertia window passes — the 0.2x use-penalty is a hard vanilla rule.
        m.put("noslow",       new CheckCfg(1.0, 3, 5, 12, 0.5));
        // Scaffold: the eye sitting behind the clicked face is geometrically
        // impossible; the look-vector rule needs a streak of four, because the
        // rotation the server has when the interact packet arrives is a tick old.
        m.put("scaffold",      new CheckCfg(2.0, 4, 6, 12, 0.5));
        // GCD (aim grid): a vanilla client quantises rotation deltas to its
        // sensitivity; interpolated aim produces deltas that never divide the
        // grid. High confidence once confirmed over a window, but a heuristic
        // about input hardware, so observe-only by default.
        m.put("gcd",           new CheckCfg(2.0, 3, -1, -1, 0.05));
        // X-ray (statistical mining shape): hidden-ore breaks, pure-ore
        // sessions and diamond/stone ratios. Never a per-packet verdict —
        // every individual break is legitimate by construction — so strictly
        // observe-only: it feeds EDR probes, staff see it in the player
        // card, and only a correlated chain escalates.
        m.put("xray",          new CheckCfg(2.0, 3, -1, -1, 0.05));
        // Vehicle: the ridden object's own physics. Server-side facts only
        // (hull support, terrain, the velocity budget), so a confirmed
        // violation is solid — but vehicles are also the most mod-sensitive
        // thing on a server, so it mitigates long before it kicks.
        m.put("vehicle",       new CheckCfg(2.0, 4, 6, 14, 0.5));
        // AirPlace: a target with no outline shape cannot have been raycast.
        // Protocol-level, and the buffer already absorbs the one case that is
        // not the sender's fault (someone else broke the block mid-flight).
        m.put("airplace",      new CheckCfg(3.0, 3, 4, 10, 0.5));
        // InvMove: vanilla zeroes movement input while a screen is open.
        // Sprint-with-screen is unambiguous; plain walking needs the streak.
        m.put("invmove",       new CheckCfg(1.5, 4, 6, 14, 0.5));
        // BrandSpoof: "vanilla" brand plus modded channels is a contradiction
        // the client itself produced. It fires at most once per session, so
        // the weight is high and the decay is slow.
        m.put("brandspoof",    new CheckCfg(6.0, 5, -1, -1, 0.02));
        // PacketOrder: more than one move packet inside a single client tick.
        // An exact protocol invariant since 1.21.2 — lag delays ticks, it
        // never packs two moves into one.
        m.put("packetorder",   new CheckCfg(2.0, 4, 6, 14, 0.4));
        // --- v10: the Wurst pass (see README «Под Wurst») ---
        // Aim: hits through the exact hitbox centre, flicks there and back.
        // One flag already needs six locked hits or two snap-backs.
        m.put("aim",           new CheckCfg(2.0, 4, 6, 12, 0.1));
        // AutoPlace: four clicks in a row on the exact centre of a face.
        m.put("autoplace",     new CheckCfg(2.0, 4, 6, 12, 0.2));
        // Jesus: onGround over water with nothing solid under it, six packets.
        m.put("jesus",         new CheckCfg(1.5, 4, 5, 12, 0.5));
        // Step: the +0.42/+0.753 packet pair — one occurrence is the module.
        m.put("step",          new CheckCfg(3.0, 3, 6, 12, 0.3));
        // AutoTotem: timing against ping, so staff are told, nothing automatic.
        m.put("autototem",     new CheckCfg(3.0, 3, -1, -1, 0.02));
        // BadRotation: pitch past 90° — a vanilla client clamps it itself.
        m.put("badrotation",   new CheckCfg(5.0, 5, -1, 15, 0.2));
        return m;
    }

    public CheckCfg check(String name) {
        CheckCfg cfg = checks.get(name.toLowerCase(Locale.ROOT));
        return cfg != null ? cfg : MISSING;
    }

    private static final CheckCfg MISSING = new CheckCfg();

    /** In-memory defaults, used before the config file is loaded. */
    public static ACConfig defaults() {
        ACConfig cfg = new ACConfig();
        cfg.fillDefaults();
        cfg.clamp();
        return cfg;
    }

    public static ACConfig loadOrCreate(Path file, Consumer<String> warn) throws IOException {
        ACConfig cfg = null;
        if (Files.exists(file)) {
            try {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ACConfig.class);
            } catch (RuntimeException e) {
                Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                warn.accept("config.json is not valid JSON; backed it up to " + backup.getFileName()
                        + " and applied defaults. Parse error: " + e.getMessage());
            }
        }
        boolean existed = cfg != null;
        int previousVersion = (cfg != null && cfg.general != null) ? cfg.general.configVersion : 0;
        if (cfg == null) cfg = new ACConfig();
        cfg.fillDefaults();
        cfg.clamp();
        if (existed && previousVersion < CONFIG_VERSION) {
            String detail = previousVersion < RETUNE_VERSION
                    ? "thresholds of the retuned checks " + V2_RETUNED + " were reset to defaults, and every"
                    : "new checks were added and";
            warn.accept("config.json migrated to schema v" + CONFIG_VERSION + ": " + detail
                    + " unproven check was excluded from the auto-tempban."
                    + " Your own thresholds were left alone.");
        }
        SafeFiles.write(file, GSON.toJson(cfg) + System.lineSeparator());
        return cfg;
    }

    /**
     * Writes this config (clamped) to {@code file}. Used by the live GUI editor,
     * i.e. once per settings click — atomic so a crash mid-write cannot leave a
     * truncated config.json behind (which would silently reset every threshold).
     */
    public void saveTo(Path file) throws IOException {
        clamp();
        SafeFiles.write(file, GSON.toJson(this) + System.lineSeparator());
    }

    void fillDefaults() {
        if (general == null) general = new General();
        if (messages == null) messages = new Messages();
        if (punishment == null) punishment = new Punishment();
        if (punishment.banStepsHours == null || punishment.banStepsHours.isEmpty()) {
            punishment.banStepsHours = new java.util.ArrayList<>(java.util.List.of(2, 12, 48, 336, 720));
        }
        if (punishment.banExcludedChecks == null) {
            punishment.banExcludedChecks = new java.util.ArrayList<>(BAN_EXCLUDED_DEFAULT);
        }

        Map<String, CheckCfg> defaults = defaultChecks();
        Map<String, CheckCfg> merged = new LinkedHashMap<>(defaults);
        boolean migrate = general.configVersion < CONFIG_VERSION;
        // Version 3 predates the telemetry key, so Gson deserialises it as false.
        // Enable the safe observe-only default once, while preserving an explicit
        // administrator choice made after this version has been written.
        boolean shadowMigration = general.configVersion < 4;
        boolean retune = general.configVersion < RETUNE_VERSION;
        if (checks != null) {
            checks.forEach((k, v) -> {
                if (k == null || v == null) return;
                String key = k.toLowerCase(Locale.ROOT);
                // A stored value normally wins, which is what keeps an admin's
                // tuning. But a retuned check has to be able to reach a server
                // that already has the old number on disk, or shipping a fix for
                // a false-positive changes nothing where it matters. This applies
                // once, at RETUNE_VERSION — later revisions only add new keys and
                // ban exclusions, so tuning applied afterwards is never undone.
                if (retune && V2_RETUNED.contains(key) && defaults.containsKey(key)) return;
                merged.put(key, v);
            });
        }
        checks = merged;

        if (migrate) {
            if (shadowMigration) general.shadowTelemetryEnabled = true;
            // Existing installs keep collecting local shadow data but stop periodic
            // console summaries unless an administrator explicitly opts back in.
            if (general.configVersion < 5) general.shadowConsoleReports = false;
            // Un-ban the checks that have not earned it yet, without touching any
            // exclusion the admin added themselves.
            for (String key : BAN_EXCLUDED_DEFAULT) {
                if (!punishment.banExcludedChecks.contains(key)) {
                    punishment.banExcludedChecks.add(key);
                }
            }
            if (general.configVersion < 10) {
                // v10 adds six checks. Step and badrotation are protocol facts
                // and ban through the normal ladder at once; the other four
                // alert and mitigate until they have been watched here.
                for (String key : java.util.List.of("aim", "autoplace", "jesus", "autototem")) {
                    if (!punishment.banExcludedChecks.contains(key)) punishment.banExcludedChecks.add(key);
                }
            }
            if (general.configVersion < 9) {
                // v9: NoFall went from a packet signature to an outcome audit.
                // Re-arm it on configs where it had been muted as unreliable.
                CheckCfg nf = checks.get("nofall");
                if (nf != null) {
                    nf.weight = 3.0;
                    nf.alertVl = 3.0;
                    nf.mitigateVl = 5.0;
                    nf.kickVl = 12.0;
                }
                punishment.banExcludedChecks.remove("nofall");
            }
            if (general.configVersion < 8) {
                // v8 adds five checks; make sure none of them can ban before
                // they have been observed, even on a config that already
                // carried an admin-edited exclusion list.
                for (String key : java.util.List.of("vehicle", "airplace", "invmove", "brandspoof", "packetorder")) {
                    if (!punishment.banExcludedChecks.contains(key)) punishment.banExcludedChecks.add(key);
                }
            }
            if (general.configVersion < 7) {
                // v7: per-check bans for the precise checks. The old list
                // excluded almost everything, which made the chain the only
                // road to a ban — and the chain banned on kicks. Reset the
                // exclusions to the new default; the observe-only heuristics
                // go back to never kicking (README promise).
                punishment.banExcludedChecks = new java.util.ArrayList<>(BAN_EXCLUDED_DEFAULT);
                for (String key : OBSERVE_ONLY) {
                    CheckCfg c = checks.get(key);
                    if (c != null) {
                        c.mitigateVl = -1;
                        c.kickVl = -1;
                    }
                }
                punishment.exemptOperators = true;
                if (punishment.chainWindowMinutes <= 0) punishment.chainWindowMinutes = 60;
                if (punishment.chainWatchDistinctChecks <= 0) punishment.chainWatchDistinctChecks = 2;
                if (punishment.chainBanDistinctChecks <= 0) punishment.chainBanDistinctChecks = 3;
            }
            general.configVersion = CONFIG_VERSION;
        }
    }

    void clamp() {
        general.alertCooldownSeconds = clampInt(general.alertCooldownSeconds, 1, 300);
        general.setbackMinIntervalTicks = clampInt(general.setbackMinIntervalTicks, 1, 100);
        general.joinGraceTicks = clampInt(general.joinGraceTicks, 20, 1200);
        // A value of 0 means the key was absent from an older config — restore the default.
        if (general.speedToleranceMultiplier <= 0) general.speedToleranceMultiplier = 1.0;
        // Floor at 0.9: the Speed ceiling is now realistic, so a lower multiplier
        // would start flagging plain sprinting.
        general.speedToleranceMultiplier = clampD(general.speedToleranceMultiplier, 0.9, 5.0);
        if (general.timerMinIntervalMs <= 0) general.timerMinIntervalMs = 40;
        // Never above vanilla's 50ms, or every legit player flags forever.
        general.timerMinIntervalMs = clampInt(general.timerMinIntervalMs, 10, 49);
        // 0 means the key was absent from an older config — restore the default.
        if (general.maxCps <= 0) general.maxCps = 14;
        // Floor 10: below that ordinary players are flagged. Ceiling 20: the
        // vanilla client sends at most one attack per tick, so a higher limit
        // silently disables the check instead of relaxing it.
        general.maxCps = clampInt(general.maxCps, 10, 20);
        // Anti-KB tuning. 0 means the key was absent from an older config.
        if (general.velocityVerifyTicks <= 0) general.velocityVerifyTicks = 4;
        general.velocityVerifyTicks = clampInt(general.velocityVerifyTicks, 2, 10);
        if (general.velocityMinRatio <= 0) general.velocityMinRatio = 0.6;
        general.velocityMinRatio = clampD(general.velocityMinRatio, 0.3, 0.95);
        if (general.velocityConfirmStreak <= 0) general.velocityConfirmStreak = 3;
        general.velocityConfirmStreak = clampInt(general.velocityConfirmStreak, 1, 10);
        // Blink / NoSlow tuning. 0 means the key was absent from an older config.
        if (general.blinkBurstOverdraw <= 0) general.blinkBurstOverdraw = 8;
        general.blinkBurstOverdraw = clampInt(general.blinkBurstOverdraw, 3, 40);
        if (general.blinkMaxIatStdMs <= 0) general.blinkMaxIatStdMs = 1.0;
        general.blinkMaxIatStdMs = clampD(general.blinkMaxIatStdMs, 0.1, 10.0);
        if (general.noSlowGraceTicks <= 0) general.noSlowGraceTicks = 5;
        general.noSlowGraceTicks = clampInt(general.noSlowGraceTicks, 2, 20);
        if (general.noSlowMaxFactor <= 0) general.noSlowMaxFactor = 0.35;
        general.noSlowMaxFactor = clampD(general.noSlowMaxFactor, 0.2, 1.0);
        if (general.shadowReportIntervalTicks <= 0) general.shadowReportIntervalTicks = 1200;
        general.shadowReportIntervalTicks = clampInt(general.shadowReportIntervalTicks, 100, 72_000);
        if (general.movementScansPerTickBudget <= 0) general.movementScansPerTickBudget = 2000;
        general.movementScansPerTickBudget = clampInt(general.movementScansPerTickBudget, 50, 20_000);
        if (general.shadowPerPlayerMoveWarn <= 0) general.shadowPerPlayerMoveWarn = 12;
        general.shadowPerPlayerMoveWarn = clampInt(general.shadowPerPlayerMoveWarn, 3, 200);
        punishment.alertsToBan = clampInt(punishment.alertsToBan, 1, 100);
        punishment.alertWindowMinutes = clampInt(punishment.alertWindowMinutes, 1, 1440);
        punishment.banHistoryHours = clampInt(punishment.banHistoryHours, 1, 8760);
        if (punishment.chainWindowMinutes <= 0) punishment.chainWindowMinutes = 60;
        punishment.chainWindowMinutes = clampInt(punishment.chainWindowMinutes, 5, 1440);
        if (punishment.chainWatchDistinctChecks <= 0) punishment.chainWatchDistinctChecks = 2;
        punishment.chainWatchDistinctChecks = clampInt(punishment.chainWatchDistinctChecks, 1, 10);
        if (punishment.chainBanDistinctChecks <= 0) punishment.chainBanDistinctChecks = 3;
        punishment.chainBanDistinctChecks = clampInt(punishment.chainBanDistinctChecks,
                punishment.chainWatchDistinctChecks, 10);
        punishment.banStepsHours.replaceAll(h -> clampInt(h == null ? 1 : h, 1, 8760));
        for (CheckCfg c : checks.values()) {
            c.weight = clampD(c.weight, 0.1, 50);
            c.alertVl = clampD(c.alertVl, 0.5, 1000);
            if (c.mitigateVl > 0) c.mitigateVl = clampD(c.mitigateVl, 0.5, 1000);
            if (c.kickVl > 0) c.kickVl = clampD(c.kickVl, 0.5, 1000);
            c.decayPerSecond = clampD(c.decayPerSecond, 0.05, 100);
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
