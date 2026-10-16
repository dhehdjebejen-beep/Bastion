package dev.bastionac.argus;

import dev.bastionac.BastionAC;
import dev.bastionac.config.ACConfig;
import dev.bastionac.core.AcJournal;
import dev.bastionac.core.BanManager;
import dev.bastionac.telemetry.Telemetry;
import dev.bastionac.telemetry.TelemetryFormat;
import dev.bastionac.telemetry.TelemetryIO;
import dev.bastionac.util.SafeFiles;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Argus — the analytical investigation beside the checks.
 *
 * <p>The checks act in the moment, packet by packet, and the EDR chain ties
 * their flags together. Argus does neither. A signal — an EDR chain, a staff
 * alert, an X-ray probe, a loop of server put-backs, an admin's request, or
 * its own background scan of a finished session — opens a <em>case</em>.
 * The case collects for a while, then the Argus thread reads the player's
 * telemetry for the window around it, measures thirty-odd features of
 * movement, timing, aim and digging, compares them with what this server's
 * own honest players do, and returns a calibrated score with the reasons.
 * The first word comes within minutes; the final one by the deadline, or at
 * once when it cannot change any more (guilty, or the player has left).
 *
 * <p>What happens then depends on the mode: SHADOW (only the case), ASSIST
 * (staff are told, one click to act) or AUTO (a confident verdict bans by
 * itself — "бан Аргуса"). The owner's labels on cases ("cheater" / "not a
 * cheater") retrain the weights and keep cheaters out of the norms.
 *
 * <p>Threads: signals may come from anywhere and land in an inbox; cases
 * change only on the server thread; the analysis itself runs on the Argus
 * thread and posts its result back.
 */
public final class Argus {

    private static volatile boolean ready;
    private static final ConcurrentLinkedQueue<Signal> INBOX = new ConcurrentLinkedQueue<>();
    private static CaseStore store;
    private static volatile Baselines baselines = new Baselines();
    private static volatile Calibration calibration = new Calibration();
    private static ArgusWorker worker;
    private static Path dir;
    private static Path reportsDir;
    private static volatile boolean dirtyModel;
    private static volatile long lastAnalysisMs;
    private static volatile long lastDurationMs;
    /** The latest run of each case, for the live menu (3.2). */
    private static final java.util.Map<String, ArgusProgress> PROGRESS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int analysesDone;
    private static volatile int scansDone;
    /** Players the owner has called cheaters — kept out of every norm. */
    private static final Set<String> CHEATERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Argus() {}

    // ---------------------------------------------------------------- lifecycle

    public static void init(Path configDir, Path gameDir) {
        try {
            dir = configDir.resolve("argus");
            reportsDir = gameDir.resolve("logs").resolve("bastionac").resolve("argus");
            Files.createDirectories(dir);
            Files.createDirectories(reportsDir);
            store = new CaseStore(dir.resolve("cases.json"));
            store.load();
            baselines = Baselines.load(dir.resolve("baselines.json"));
            calibration = Calibration.load(dir.resolve("model.json"));
            for (CaseFile c : store.all()) if (c.label == CaseFile.Label.CHEATER) CHEATERS.add(c.uuid);
            worker = new ArgusWorker();
            Telemetry.setPinnedSupplier(Argus::pinnedFiles);
            ready = true;
            BastionAC.LOGGER.info("Argus ready: {} cases, norms from {} sessions, {} labelled cases, mode {}",
                    store.all().size(), baselines.sessions(), calibration.cheaters + calibration.clean, mode());
            if (baselines.sessions() == 0 && BastionAC.config().argus.analyzeEverySession) seedBaselines();
            // A new prior shipped (or a pre-3.3 model): refit the owner's labels on top of it.
            if (!calibration.onCurrentPrior()) refit();
        } catch (Exception e) {
            BastionAC.LOGGER.error("Argus failed to start; the checks run without it", e);
            ready = false;
        }
    }

    public static void shutdown() {
        if (!ready) return;
        ready = false;
        if (worker != null) worker.shutdown();
        saveAll();
    }

    public static boolean enabled() {
        return ready && BastionAC.config().argus.enabled;
    }

    public static String mode() {
        return BastionAC.config().argus.mode;
    }

    public static void setMode(String m) {
        ACConfig.ArgusCfg c = BastionAC.config().argus;
        c.mode = m.toUpperCase(Locale.ROOT);
        BastionAC.saveConfig();
    }

    // ---------------------------------------------------------------- signals

    public static void signal(ServerPlayerEntity p, Signal.Kind kind, String detail) {
        if (p == null) return;
        signal(p.getUuid(), p.getGameProfile().name(), kind, detail);
    }

    /** Any thread. Never blocks, never throws. */
    public static void signal(UUID uuid, String name, Signal.Kind kind, String detail) {
        if (!ready || uuid == null) return;
        INBOX.add(new Signal(System.currentTimeMillis(), uuid, name, kind, detail));
    }

    // ---------------------------------------------------------------- server tick

    public static void tick(MinecraftServer srv, long tick) {
        if (!ready) return;
        WorldSampler.tick();
        if (tick % 10 == 0) {
            // The live menu: queue positions, then every open case card and list.
            if (worker != null) worker.updateAhead();
            dev.bastionac.gui.ArgusMenu.tickLive();
        }
        if (tick % 20 != 0) return;
        if (advance(srv)) store.save();
        if (tick % (20 * 600) == 0) {
            ACConfig.ArgusCfg cfg = BastionAC.config().argus;
            long now = System.currentTimeMillis();
            int pruned = store.prune(now, cfg.caseRetentionDays);
            if (pruned > 0) store.save();
            PROGRESS.entrySet().removeIf(e -> {
                ArgusProgress.View v = e.getValue().view();
                return v.done() && now - v.finishedAt() > 3_600_000L;
            });
            if (dirtyModel) saveAll();
        }
    }

    /** Takes in the signals and moves every open case on. Server thread. Returns whether anything changed. */
    private static boolean advance(MinecraftServer srv) {
        ACConfig.ArgusCfg cfg = BastionAC.config().argus;
        long now = System.currentTimeMillis();
        Signal s;
        boolean changed = false;
        while ((s = INBOX.poll()) != null) {
            if (!cfg.enabled) continue;
            intake(srv, s, now, cfg);
            changed = true;
        }
        if (!cfg.enabled) return changed;
        for (CaseFile c : store.all()) {
            if (!c.open() || c.inFlight) continue;
            boolean online = srv.getPlayerManager().getPlayer(UUID.fromString(c.uuid)) != null;
            boolean wasFinal = c.finalPass;
            switch (CaseLifecycle.next(c, now, online, cfg)) {
                case ANALYZE -> {
                    queue(srv, c, cfg);
                    changed = true;
                }
                case CLOSE -> {
                    c.status = CaseFile.Status.CLOSED;
                    c.closedAt = now;
                    if (c.awaitsDecision()) c.closeNote = "мало данных при алертах — нужен взгляд персонала";
                    changed = true;
                }
                case NONE -> changed |= wasFinal != c.finalPass;
            }
        }
        return changed;
    }

    private static void intake(MinecraftServer srv, Signal s, long now, ACConfig.ArgusCfg cfg) {
        CaseFile c = store.openFor(s.uuid().toString());
        CaseFile previous = null;
        if (CaseLifecycle.newEpisode(c, now)) {
            // Hours after the case went quiet: a new episode gets its own case
            // and its own window (3.1.2). The old one closes with its verdict.
            previous = c;
            c = null;
        }
        if (c == null && s.kind() == Signal.Kind.SESSION_SCAN
                && CaseLifecycle.recentlyDecided(store.ofPlayer(s.uuid().toString()), now)) {
            return;   // the session staff just judged; not a new episode
        }
        boolean fresh = c == null;
        if (fresh) {
            c = store.create(s.uuid().toString(), s.name(), now);
            c.dueAt = now + cfg.collectMinutes * 60_000L;
            c.deadlineAt = now + cfg.deadlineMinutes * 60_000L;
            if (previous != null) {
                c.previousCaseId = previous.id;
                CaseLifecycle.closeAsEpisode(previous, c.id, now);
            }
        } else if (c.status == CaseFile.Status.ANALYZED && c.closedAt == 0 && now > c.deadlineAt) {
            // A new episode long after the old one was decided: give it its own hour.
            c.deadlineAt = now + cfg.deadlineMinutes * 60_000L;
            c.finalPass = false;
        }
        c.addSignal(s);
        if (s.kind() == Signal.Kind.MANUAL) {
            // A staff request may name the span to read (an offline player's
            // last session, the session behind an old alert); without one the
            // case reads around now, as before.
            SessionLocator.Window w = PENDING_WINDOWS.remove(s.uuid().toString());
            c.windowFrom = w == null ? 0 : w.from();
            c.windowTo = w == null ? 0 : w.to();
            c.windowNote = w == null ? null : w.note();
        } else if (c.windowTo > 0) {
            // Live play again: the case reads the present.
            c.windowFrom = 0;
            c.windowTo = 0;
            c.windowNote = null;
        }
        if (s.kind() == Signal.Kind.CHAIN_BURNED || s.kind() == Signal.Kind.CORRECTION_LOOP) {
            c.dueAt = Math.min(c.dueAt, now + 30_000L);
        }
        if (s.kind() == Signal.Kind.MANUAL || s.kind() == Signal.Kind.SESSION_SCAN) {
            // Asked for now: an already decided case is read again at once, not
            // in a quarter of an hour — and one being read right now is read
            // again the moment it returns, never a second time beside it.
            CaseLifecycle.requestNow(c, now);
        }
        if (fresh) {
            AcJournal.write("argus-case", s.name(), s.uuid().toString(), s.kind().name().toLowerCase(Locale.ROOT), 0,
                    "дело #" + c.id + ": " + s.kind().label + (s.detail() == null ? "" : " — " + s.detail())
                            + (previous == null ? "" : " (прошлое дело #" + previous.id + ": "
                            + previous.verdict.label + ", закрыто)"), null);
        }
        Telemetry.acAction(s.uuid(), TelemetryFormat.ACT_SIGNAL, "Аргус #" + c.id + ": " + s.kind().label
                + (s.detail() == null ? "" : " — " + s.detail()));
    }

    /** Hands a case to the Argus thread; the result comes back on the server thread. */
    private static void queue(MinecraftServer srv, CaseFile c, ACConfig.ArgusCfg cfg) {
        int gen = CaseLifecycle.begin(c);
        if (gen < 0) return;   // already on the Argus thread; folded into a re-run
        UUID uuid = UUID.fromString(c.uuid);
        Telemetry.flushNow(uuid);
        Analysis.Input in = new Analysis.Input();
        in.uuid = c.uuid;
        in.name = c.name;
        long now = System.currentTimeMillis();
        if (c.windowTo > 0) {
            in.from = c.windowFrom;
            in.to = c.windowTo;
        } else {
            in.from = CaseLifecycle.readFrom(c, now, cfg.windowMinutes * 60_000L);
            in.to = now;
        }
        in.worldSample = srv.getPlayerManager().getPlayer(uuid) != null
                && (c.count(Signal.Kind.XRAY_PROBE) > 0 || c.count(Signal.Kind.MANUAL) > 0
                || c.count(Signal.Kind.CHAIN_BURNED) > 0 || c.count(Signal.Kind.SESSION_SCAN) > 0);
        in.signalEvidence = signalEvidence(c);
        String id = c.id;
        ArgusProgress progress = new ArgusProgress(id, gen, now);
        PROGRESS.put(id, progress);
        in.progress = progress;
        worker.submit(ArgusWorker.PRIO_CASE, "дело #" + id + " (" + c.name + ")", progress, () -> {
            MinecraftServer s = BastionAC.server();
            if (s != null) s.execute(() -> {
                CaseFile live = store.byId(id);
                if (live != null) CaseLifecycle.started(live, gen);
            });
            long t0 = System.currentTimeMillis();
            Analysis.Output out;
            try {
                out = Analysis.run(in, baselines, calibration);
            } catch (Throwable t) {
                // Analysis.run catches its own failures; this is the last line so
                // the case is never left marked as being analysed forever.
                out = new Analysis.Output();
                out.error = t.toString();
                out.result = Scorer.score(new dev.bastionac.argus.features.FeatureSet(), baselines, in.uuid,
                        calibration, in.signalEvidence);
            }
            progress.stage(ArgusProgress.Stage.DONE);
            lastDurationMs = System.currentTimeMillis() - t0;
            lastAnalysisMs = System.currentTimeMillis();
            analysesDone++;
            Analysis.Output result = out;
            if (s != null) s.execute(() -> applyResult(id, gen, result));
        });
    }

    /** What the signals are worth before the telemetry says anything (diminishing per kind). */
    static double signalEvidence(CaseFile c) {
        double sum = 0;
        for (Signal.Kind k : Signal.Kind.values()) {
            int n = c.count(k);
            if (n <= 0 || k.prior <= 0) continue;
            sum += k.prior * Math.min(3.0, 1 + Math.log(n));
        }
        return Math.min(2.5, sum);
    }

    // ---------------------------------------------------------------- results

    private static void applyResult(String id, int gen, Analysis.Output out) {
        CaseFile c = store.byId(id);
        if (c == null) return;
        CaseLifecycle.Outcome outcome = CaseLifecycle.arrive(c, gen);
        if (outcome == CaseLifecycle.Outcome.STALE) return;
        ACConfig.ArgusCfg cfg = BastionAC.config().argus;
        Scorer.Result r = out.result;
        CaseFile.Verdict before = c.verdict;
        long now = System.currentTimeMillis();
        c.analyses++;
        c.lastAnalyzedAt = now;
        c.score = r.score();
        c.evidence = r.evidence();
        c.signalEvidence = r.signalEvidence();
        c.dataMinutes = out.minutes;
        c.worldSampled = out.sampled;
        c.lastError = out.error;
        c.sessionFiles = new ArrayList<>(out.files);
        c.categories.clear();
        r.categories().forEach((k, v) -> c.categories.put(k.label, v));
        c.evidenceByKey = r.evidenceByKey();
        c.features.clear();
        for (Scorer.Row row : r.rows()) {
            CaseFile.FeatureRow f = new CaseFile.FeatureRow();
            f.key = row.spec().key();
            f.value = row.value();
            f.support = row.support();
            f.ref = row.ref();
            f.scale = row.scale();
            f.z = row.z();
            f.evidence = row.evidence();
            f.source = row.source();
            c.features.add(f);
        }
        c.findings = Report.findings(r, out);
        c.verdict = verdictOf(r, cfg);
        writeReport(c, out, r);
        AcJournal.write("argus-verdict", c.name, c.uuid, "argus", 0, String.format(Locale.ROOT,
                "дело #%s: %s, оценка %.3f — %s", c.id, c.verdict.label, c.score,
                c.findings.isEmpty() ? "" : c.findings.get(0)), null);
        tellWaitingStaff(c);
        if (outcome == CaseLifecycle.Outcome.RECORD_ONLY) {
            // Staff decided while this analysis ran. Their word stands: the
            // numbers are kept (a labelled case is what the model learns from),
            // the case stays closed and nobody is alerted again.
            store.save();
            if (c.label == CaseFile.Label.CHEATER || c.label == CaseFile.Label.CLEAN) refit();
            return;
        }
        MinecraftServer srv = BastionAC.server();
        boolean online = srv != null && srv.getPlayerManager().getPlayer(UUID.fromString(c.uuid)) != null;
        CaseLifecycle.settle(c, online, now, solidGround(r, c, cfg));
        act(c, r, before, cfg);
        tellAlertsCleared(c, cfg);
        store.save();
    }

    /**
     * A case the alerts opened and whose final word is "clean" or "too little
     * data": staff hear it once (3.3.2). Before, only a suspicious or guilty
     * verdict said anything — an alert Argus looked at and cleared looked
     * exactly like one it never looked at.
     */
    private static void tellAlertsCleared(CaseFile c, ACConfig.ArgusCfg cfg) {
        if (!cfg.notifyClearedAlertCases || c.clearedNotified || !c.finalPass || "SHADOW".equals(mode())) return;
        if (c.verdict != CaseFile.Verdict.CLEAN && c.verdict != CaseFile.Verdict.INSUFFICIENT) return;
        String checks = alertChecks(c);
        if (checks.isEmpty()) return;
        c.clearedNotified = true;
        String outcome = c.verdict == CaseFile.Verdict.CLEAN
                ? "&aчисто &7— алерты (" + checks + ") телеметрией не подтвердились"
                : "&7мало данных для вывода при алертах (" + checks + ") &e— посмотри сам";
        dev.bastionac.core.Alerts.sendArgusNote(c.name, c.id, outcome);
    }

    /** "fly ×3, highjump" — the checks whose alerts fed this case. */
    static String alertChecks(CaseFile c) {
        java.util.Map<String, Integer> n = new java.util.LinkedHashMap<>();
        for (CaseFile.SignalEntry e : c.signals) {
            if (!Signal.Kind.ALERT.name().equals(e.kind) || e.detail == null) continue;
            int sp = e.detail.indexOf(' ');
            n.merge(sp > 0 ? e.detail.substring(0, sp) : e.detail, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        n.forEach((k, v) -> parts.add(v > 1 ? k + " ×" + v : k));
        return String.join(", ", parts);
    }

    /** Whoever pressed "investigate" hears the outcome, clean or not — "итог придёт сюда". */
    private static void tellWaitingStaff(CaseFile c) {
        if (c.waitingStaff == null || c.waitingStaff.isEmpty()) return;
        MinecraftServer srv = BastionAC.server();
        if (srv != null) {
            String line = String.format(Locale.ROOT, "&5[Аргус] &7Дело &f#%s &7(&f%s&7): %s%s &8(%.0f%%)%s",
                    c.id, c.name, c.verdict.colour, c.verdict.label, c.score * 100,
                    c.findings.isEmpty() ? "" : " &7— " + c.findings.get(0));
            for (String who : c.waitingStaff) {
                ServerPlayerEntity staff = srv.getPlayerManager().getPlayer(who);
                if (staff != null) staff.sendMessage(dev.bastionac.core.TextFmt.literal(line), false);
            }
        }
        c.waitingStaff.clear();
    }

    static CaseFile.Verdict verdictOf(Scorer.Result r, ACConfig.ArgusCfg cfg) {
        if (!r.enoughData() && r.signalEvidence() < 1.0) return CaseFile.Verdict.INSUFFICIENT;
        if (r.score() >= cfg.guiltyScore && (r.strong() >= 2 || r.hardHit())) return CaseFile.Verdict.GUILTY;
        if (r.score() >= cfg.suspectScore) return CaseFile.Verdict.SUSPECT;
        return r.enoughData() ? CaseFile.Verdict.CLEAN : CaseFile.Verdict.INSUFFICIENT;
    }

    /** The mode's consequence of a verdict. */
    private static void act(CaseFile c, Scorer.Result r, CaseFile.Verdict before, ACConfig.ArgusCfg cfg) {
        if (c.label == CaseFile.Label.CLEAN) return;   // the owner already cleared this one
        boolean worse = c.verdict.ordinal() > before.ordinal();
        if (c.verdict != CaseFile.Verdict.SUSPECT && c.verdict != CaseFile.Verdict.GUILTY) return;
        String m = mode();
        if ("AUTO".equals(m) && c.verdict == CaseFile.Verdict.GUILTY && c.action != CaseFile.Action.BANNED
                && autoBanAllowed(c, r, cfg)) {
            ban(c.id, "Аргус (авто)");
            return;
        }
        if (!"SHADOW".equals(m) && (worse || c.action == CaseFile.Action.NONE)) {
            c.action = CaseFile.Action.NOTIFIED;
            c.actionAt = System.currentTimeMillis();
            dev.bastionac.core.Alerts.sendArgus(c.name, UUID.fromString(c.uuid), c.id, c.verdict.label,
                    c.verdict == CaseFile.Verdict.GUILTY, c.score, c.findings);
        }
    }

    /**
     * A verdict that may stand without more data: a physically impossible fact,
     * or at least {@code autoBanMinMinutes} of telemetry behind it.
     */
    static boolean solidGround(Scorer.Result r, CaseFile c, ACConfig.ArgusCfg cfg) {
        return r.hardHit() || c.dataMinutes >= cfg.autoBanMinMinutes;
    }

    private static boolean autoBanAllowed(CaseFile c, Scorer.Result r, ACConfig.ArgusCfg cfg) {
        if (r.score() < cfg.autoBanScore) return false;
        // With a two-minute collection the first verdict can rest on very
        // little; a ban waits for enough telemetry unless a fact is impossible.
        // Staff are told meanwhile, and the deadline pass reads it again.
        if (!solidGround(r, c, cfg)) return false;
        if (r.score() <= calibration.maxCleanScore + 0.01) return false;
        if (baselines.sessions() < cfg.minBaselineSessions && !r.hardHit()) return false;
        MinecraftServer srv = BastionAC.server();
        return srv != null && !BanManager.isExemptOperator(srv, UUID.fromString(c.uuid), c.name);
    }

    private static void writeReport(CaseFile c, Analysis.Output out, Scorer.Result r) {
        if (reportsDir == null) return;
        try {
            Path day = reportsDir.resolve(LocalDate.now().toString());
            Files.createDirectories(day);
            Path file = day.resolve("case-" + c.id + "-" + c.name.replaceAll("[^A-Za-z0-9_]", "_") + ".txt");
            c.reportFile = reportsDir.getParent().getParent().getParent().relativize(file).toString();
            String text = Report.full(c, out, r, calibration, baselines);
            SafeFiles.writeAsync(file, () -> text, t -> BastionAC.LOGGER.warn("Argus: report not written: {}", t.toString()));
        } catch (Exception e) {
            BastionAC.LOGGER.warn("Argus: report not written: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- sessions → norms

    /** Disconnect: the case speeds up, and the finished session is scanned for the norms. */
    public static void onDisconnect(UUID uuid, String name, String sessionFile) {
        if (!ready) return;
        CaseFile c = store.openFor(uuid.toString());
        if (c != null && c.status == CaseFile.Status.COLLECTING) {
            c.playerLeft = true;
            c.dueAt = Math.min(c.dueAt, System.currentTimeMillis() + 30_000L);
        }
        if (sessionFile == null || !BastionAC.config().argus.analyzeEverySession) return;
        scan(uuid.toString(), name, TelemetryIO.fileOf(sessionFile));
    }

    private static void scan(String uuid, String name, Path file) {
        worker.submit(ArgusWorker.PRIO_SCAN, "фоновый разбор " + name, () -> {
            TelemetryIO.drain(10_000);
            Analysis.Input in = new Analysis.Input();
            in.uuid = uuid;
            in.name = name;
            in.file = file;
            Analysis.Output out = Analysis.run(in, baselines, calibration);
            scansDone++;
            if (out.minutes < 3 || out.error != null) return;
            Scorer.Result r = out.result;
            ACConfig.ArgusCfg cfg = BastionAC.config().argus;
            // A session full of flags is no picture of normal play, whatever its score.
            double flagsPerHour = out.flags / Math.max(0.05, out.minutes / 60.0);
            boolean clean = !CHEATERS.contains(uuid) && r.score() < cfg.suspectScore && flagsPerHour <= 40;
            if (clean) {
                baselines.addSession(uuid, Analysis.baselineValues(out.features));
                dirtyModel = true;
            } else if (r.score() >= cfg.suspectScore) {
                String top = Report.findings(r, out).get(0);
                signal(UUID.fromString(uuid), name, Signal.Kind.SESSION_SCAN,
                        String.format(Locale.ROOT, "оценка %.2f: %s", r.score(), top));
            }
        });
    }

    /** First start with telemetry already on disk: learn the norms from it in the background. */
    private static void seedBaselines() {
        List<Path> files = TelemetryIO.allFiles();
        int n = 0;
        for (Path f : files) {
            if (n++ >= 150) break;
            String fn = f.getFileName().toString();
            int i = fn.indexOf("__");
            if (i <= 0) continue;
            String uuid = fn.substring(0, i);
            String name = fn.substring(i + 2, fn.lastIndexOf("__"));
            scan(uuid, name, f);
        }
    }

    // ---------------------------------------------------------------- staff actions

    /** An admin asks for an investigation now. Returns the case id. */
    /** The outcome of a staff request: the case it opened, or why there is none; and what is being read. */
    public record Investigation(String caseId, String error, String note) {}

    /** Spans a staff request asked for, handed from {@link #investigate} to the intake of its signal. */
    private static final java.util.Map<String, SessionLocator.Window> PENDING_WINDOWS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Investigate a player whether or not they are online (3.3.2):
     * {@code atMs <= 0} — online: the last hour, as always; offline: their last
     * recorded session. {@code atMs > 0} — the session that was running at
     * that moment (an alert in the history). Before, every request read the
     * hour before now, so a player who had left had nothing to read.
     */
    public static Investigation investigate(UUID uuid, String name, String by, long atMs) {
        if (!ready) return new Investigation(null, "Аргус не запущен", null);
        MinecraftServer srv = BastionAC.server();
        boolean online = srv != null && srv.getPlayerManager().getPlayer(uuid) != null;
        if (online && atMs <= 0) {
            String id = manual(uuid, name, by);
            return new Investigation(id, id == null ? "Аргус выключен в конфиге" : null, "последний час, игрок в сети");
        }
        Telemetry.flushNow(uuid);
        SessionLocator.Window w = SessionLocator.find(uuid.toString(), atMs);
        if (w == null) {
            return new Investigation(null, "нет телеметрии " + (atMs > 0 ? "за это время" : "этого игрока")
                    + " (хранится " + BastionAC.config().telemetry.retentionDays + " дн.)", null);
        }
        if (online && w.to() >= System.currentTimeMillis() - SessionLocator.MARGIN_MS) {
            // The alert is from the session being played right now: the live case.
            String id = manual(uuid, name, by);
            return new Investigation(id, id == null ? "Аргус выключен в конфиге" : null, "текущая сессия");
        }
        PENDING_WINDOWS.put(uuid.toString(), w);
        try {
            String id = manual(uuid, name, by, "запросил " + by + ": " + w.note());
            return new Investigation(id, id == null ? "Аргус выключен в конфиге" : null, w.note());
        } finally {
            PENDING_WINDOWS.remove(uuid.toString());
        }
    }

    public static String manual(UUID uuid, String name, String by) {
        return manual(uuid, name, by, "запросил " + by);
    }

    private static String manual(UUID uuid, String name, String by, String detail) {
        if (!ready) return null;
        signal(uuid, name, Signal.Kind.MANUAL, detail);
        MinecraftServer srv = BastionAC.server();
        // Take the signal in and queue the case at once. (This used to call the
        // whole tick with tick=0, which also pruned and rewrote every file.)
        if (srv != null && advance(srv)) store.save();
        CaseFile c = store.openFor(uuid.toString());
        if (c == null) return null;
        if (by != null) {
            if (c.waitingStaff == null) c.waitingStaff = java.util.concurrent.ConcurrentHashMap.newKeySet();
            c.waitingStaff.add(by);
        }
        return c.id;
    }

    public static boolean label(String id, CaseFile.Label label, String by, String note) {
        CaseFile c = store == null ? null : store.byId(id);
        if (c == null) return false;
        c.label = label;
        c.labelBy = by;
        c.labelAt = System.currentTimeMillis();
        c.labelNote = note;
        if (label == CaseFile.Label.CHEATER) {
            CHEATERS.add(c.uuid);
            baselines.forget(c.uuid);
        } else if (label == CaseFile.Label.CLEAN) {
            boolean other = store.ofPlayer(c.uuid).stream().anyMatch(x -> x != c && x.label == CaseFile.Label.CHEATER);
            if (!other) CHEATERS.remove(c.uuid);
        }
        if (label == CaseFile.Label.CHEATER || label == CaseFile.Label.CLEAN) {
            // The owner's call is the verdict. Before, only "not a cheater"
            // closed the case: one marked "cheater" stayed open and Argus went
            // on re-reading, re-judging and re-alerting as if nobody had decided.
            CaseLifecycle.closeByStaff(c, c.labelAt);
            // Labelled before Argus ever read it: read it once anyway, for the
            // model only — a label without numbers teaches nothing.
            MinecraftServer srv = BastionAC.server();
            if (c.analyses == 0 && !c.inFlight && srv != null) queue(srv, c, BastionAC.config().argus);
        }
        AcJournal.write("argus-label", c.name, c.uuid, "argus", 0, "дело #" + c.id + ": " + label.label + " (" + by + ")", null);
        store.save();
        refit();
        return true;
    }

    /** Retrains the weights from every labelled case (Argus thread). */
    public static void refit() {
        if (!ready) return;
        List<Calibration.Sample> samples = new ArrayList<>();
        for (CaseFile c : store.all()) {
            if (c.analyses == 0) continue;
            if (c.label == CaseFile.Label.CHEATER) samples.add(new Calibration.Sample(c.evidenceByKey, c.signalEvidence, true));
            if (c.label == CaseFile.Label.CLEAN) samples.add(new Calibration.Sample(c.evidenceByKey, c.signalEvidence, false));
        }
        double suspect = BastionAC.config().argus.suspectScore;
        worker.submit(ArgusWorker.PRIO_LABELS, "переобучение модели", () -> {
            calibration.fit(samples, suspect);
            dirtyModel = true;
            MinecraftServer srv = BastionAC.server();
            if (srv != null) srv.execute(Argus::saveAll);
        });
    }

    /** The staff button (ASSIST) or the AUTO verdict: "бан Аргуса". */
    public static boolean ban(String id, String by) {
        CaseFile c = store == null ? null : store.byId(id);
        if (c == null) return false;
        String reason = c.findings.isEmpty() ? c.verdict.label : c.findings.get(0);
        BanManager.argusBan(UUID.fromString(c.uuid), c.name, c.id, reason, by);
        c.action = CaseFile.Action.BANNED;
        c.actionBy = by;
        c.actionAt = System.currentTimeMillis();
        // A ban settles the case; the session scan that follows the kick must
        // not open it again (see CaseLifecycle.recentlyDecided).
        CaseLifecycle.closeByStaff(c, c.actionAt);
        store.save();
        return true;
    }

    public static boolean dismiss(String id, String by) {
        CaseFile c = store == null ? null : store.byId(id);
        if (c == null) return false;
        c.action = CaseFile.Action.DISMISSED;
        c.actionBy = by;
        c.actionAt = System.currentTimeMillis();
        CaseLifecycle.closeByStaff(c, c.actionAt);
        store.save();
        return true;
    }

    /**
     * Re-analyse now (staff button). On an open case this is "investigate now";
     * one already being analysed is re-read right after, not twice at once. A
     * closed case only gets fresh numbers — it is not reopened (the button used
     * to reopen it, which is how a case closed by staff came back to life).
     */
    public static boolean reanalyze(String id, String by) {
        CaseFile c = store == null ? null : store.byId(id);
        MinecraftServer srv = BastionAC.server();
        if (c == null || srv == null) return false;
        if (by != null) {
            if (c.waitingStaff == null) c.waitingStaff = java.util.concurrent.ConcurrentHashMap.newKeySet();
            c.waitingStaff.add(by);
        }
        if (c.inFlight) {
            c.rerunRequested = c.open();
            return true;
        }
        if (c.open()) CaseLifecycle.requestNow(c, System.currentTimeMillis());
        queue(srv, c, BastionAC.config().argus);
        store.save();
        return true;
    }

    // ---------------------------------------------------------------- queries

    public static List<CaseFile> cases() {
        return store == null ? List.of() : store.all();
    }

    public static CaseFile get(String id) {
        return store == null ? null : store.byId(id);
    }

    public static List<CaseFile> casesOf(UUID uuid) {
        return store == null ? List.of() : store.ofPlayer(uuid.toString());
    }

    public static Calibration calibration() {
        return calibration;
    }

    public static Baselines baselines() {
        return baselines;
    }

    /** Session files that must not be deleted yet: open cases and cases labelled "cheater". */
    public static Set<String> pinnedFiles() {
        Set<String> out = new HashSet<>();
        if (store == null) return out;
        for (CaseFile c : store.all()) {
            if (c.open() || c.label == CaseFile.Label.CHEATER) out.addAll(c.sessionFiles);
        }
        return out;
    }

    /** One status block for the panel and the command. */
    /** How far the latest analysis of a case has got (or how its last one went); null if none this session. */
    public static ArgusProgress.View progress(String caseId) {
        ArgusProgress p = caseId == null ? null : PROGRESS.get(caseId);
        return p == null ? null : p.view();
    }

    /** How long the latest analysis took (ms), for a rough "usually takes" in the menu. */
    public static long lastDurationMs() {
        return lastDurationMs;
    }

    /** What the Argus thread is doing right now (null = idle). */
    public static String currentJob() {
        return worker == null ? null : worker.current();
    }

    public static List<String> status() {
        List<String> out = new ArrayList<>();
        if (!ready) {
            out.add("&cАргус не запущен");
            return out;
        }
        int open = 0, suspect = 0;
        for (CaseFile c : store.all()) {
            if (c.open()) open++;
            if (c.open() && (c.verdict == CaseFile.Verdict.SUSPECT || c.verdict == CaseFile.Verdict.GUILTY)) suspect++;
        }
        out.add("&7Режим: &f" + modeLabel(mode()));
        out.add("&7Открытых дел: &f" + open + " &8(подозрительных " + suspect + ")");
        out.add("&7Нормы: &f" + baselines.sessions() + " &7сессий");
        out.add("&7Модель: &f" + Calibration.Prior.get().version()
                + " &7+ меток владельца: &f" + calibration.cheaters + " &7читеров, &f" + calibration.clean + " &7чистых");
        out.add("&7Разборов: &f" + analysesDone + "&7, фоновых: &f" + scansDone
                + (lastAnalysisMs > 0 ? " &8(последний " + lastDurationMs + " мс)" : ""));
        String cur = worker == null ? null : worker.current();
        out.add("&7Сейчас: &f" + (cur == null ? "ожидание" : cur) + " &8(в очереди " + (worker == null ? 0 : worker.pending()) + ")");
        out.add("&7Телеметрия: &f" + (TelemetryIO.totalBytes() / (1024 * 1024)) + " МБ &7в " + TelemetryIO.fileCount() + " файлах");
        return out;
    }

    public static String modeLabel(String m) {
        return switch (m) {
            case "SHADOW" -> "тень (только дела)";
            case "AUTO" -> "авто (сам банит)";
            default -> "помощник (сообщает персоналу)";
        };
    }

    private static void saveAll() {
        if (dir == null) return;
        dirtyModel = false;
        String b = baselines.toJson();
        String m = calibration.toJson();
        SafeFiles.writeAsync(dir.resolve("baselines.json"), () -> b, t -> BastionAC.LOGGER.warn("Argus: norms not saved: {}", t.toString()));
        SafeFiles.writeAsync(dir.resolve("model.json"), () -> m, t -> BastionAC.LOGGER.warn("Argus: model not saved: {}", t.toString()));
        if (store != null) store.save();
    }
}
