package dev.bastionac.argus;

import dev.bastionac.config.ACConfig;

import java.util.List;

/**
 * The life of a case as pure decisions on a {@link CaseFile} — no server, no
 * threads — so every race the Argus thread can run into is a unit test.
 *
 * <p>The rules that keep staff in charge:
 * <ul>
 *   <li>At most one analysis of a case is ever in flight. A request that comes
 *       in meanwhile (an admin's "investigate now", "re-analyse", a session
 *       scan) is remembered and runs the moment the current one returns —
 *       it is neither dropped nor started a second time beside it.</li>
 *   <li>A staff decision is final. "Cheater", "not a cheater", a ban or
 *       "close" closes the case; an analysis still in flight then only fills in
 *       its numbers (the model needs them to learn from the label). It never
 *       reopens the case, never changes its status and never alerts.</li>
 * </ul>
 */
final class CaseLifecycle {

    /** A clean or empty case closes this long after its deadline. */
    static final long CLOSE_AFTER_DEADLINE_MS = 30 * 60_000L;
    /** Fresh signals still trigger a re-read this long after the deadline. */
    static final long REANALYZE_GRACE_MS = 30 * 60_000L;
    /** A session scan right after a staff decision re-reads the session just decided. */
    static final long DECIDED_QUIET_MS = 60 * 60_000L;

    private CaseLifecycle() {}

    /** What the per-second pass should do with a case. */
    enum Step { NONE, ANALYZE, CLOSE }

    /** What an arriving result means for the case. */
    enum Outcome {
        /** Superseded or unknown job — ignore it. */
        STALE,
        /** The case was closed meanwhile: numbers kept, nothing else. */
        RECORD_ONLY,
        /** A live case: verdict stands, staff may be told. */
        LIVE
    }

    /**
     * Someone asked for an analysis now. Returns {@code true} when the case is
     * free to be queued on the next pass; {@code false} when one is already
     * running — then it is re-run as soon as that one returns.
     */
    static boolean requestNow(CaseFile c, long now) {
        if (c.inFlight) {
            c.rerunRequested = true;
            return false;
        }
        c.dueAt = now;
        if (c.status == CaseFile.Status.ANALYZED) c.status = CaseFile.Status.COLLECTING;
        return true;
    }

    /**
     * Claims the case for one analysis. Returns the job's generation, or
     * {@code -1} when an analysis is already in flight (the request is then
     * folded into a re-run).
     */
    static int begin(CaseFile c) {
        if (c.inFlight) {
            c.rerunRequested = true;
            return -1;
        }
        c.inFlight = true;
        c.generation++;
        c.pendingSignals = false;
        if (c.open()) c.status = CaseFile.Status.QUEUED;
        return c.generation;
    }

    /** The Argus thread picked the job up. */
    static void started(CaseFile c, int generation) {
        if (c.inFlight && c.generation == generation && c.status == CaseFile.Status.QUEUED) {
            c.status = CaseFile.Status.ANALYZING;
        }
    }

    /**
     * A result came back. Releases the case and decides how much of the
     * result may act. The caller copies the numbers in for anything but
     * {@link Outcome#STALE} and calls {@link #settle} for {@link Outcome#LIVE}.
     */
    static Outcome arrive(CaseFile c, int generation) {
        if (!c.inFlight || c.generation != generation) return Outcome.STALE;
        c.inFlight = false;
        if (!c.open()) {
            c.rerunRequested = false;
            return Outcome.RECORD_ONLY;
        }
        return Outcome.LIVE;
    }

    /**
     * After a live result: the case is analysed; a verdict is final at once
     * when it cannot change any more — guilty, or the player is gone and no new
     * telemetry will come — instead of waiting out the deadline. A request that
     * arrived during the analysis is due now.
     */
    static void settle(CaseFile c, boolean playerOnline, long now) {
        c.status = CaseFile.Status.ANALYZED;
        c.lastAnalyzedAt = now;
        if (c.verdict == CaseFile.Verdict.GUILTY || !playerOnline) c.finalPass = true;
        if (c.rerunRequested) {
            c.rerunRequested = false;
            c.dueAt = now;
            c.status = CaseFile.Status.COLLECTING;
        }
    }

    /** One per-second look at an open case that is not being analysed. */
    static Step next(CaseFile c, long now, boolean playerOnline, ACConfig.ArgusCfg cfg) {
        if (!c.open() || c.inFlight) return Step.NONE;
        switch (c.status) {
            case COLLECTING, QUEUED, ANALYZING -> {
                // QUEUED/ANALYZING without a job in flight: the job was lost
                // (restart, a failed task) — treat it as waiting.
                return now >= c.dueAt ? Step.ANALYZE : Step.NONE;
            }
            case ANALYZED -> {
                if (c.pendingSignals && now - c.lastAnalyzedAt >= cfg.reanalyzeMinutes * 60_000L
                        && now <= c.deadlineAt + REANALYZE_GRACE_MS) {
                    return Step.ANALYZE;
                }
                if (!c.finalPass && now >= c.deadlineAt) {
                    c.finalPass = true;
                    // Offline with nothing new: the last analysis already read
                    // everything there is — it simply becomes the final word.
                    if (playerOnline || c.pendingSignals) return Step.ANALYZE;
                }
                if (c.label == CaseFile.Label.NONE && now > c.deadlineAt + CLOSE_AFTER_DEADLINE_MS
                        && (c.verdict == CaseFile.Verdict.CLEAN || c.verdict == CaseFile.Verdict.INSUFFICIENT)) {
                    return Step.CLOSE;
                }
                return Step.NONE;
            }
            default -> {
                return Step.NONE;
            }
        }
    }

    /** A staff decision (label, ban, dismissal) ends the case. */
    static void closeByStaff(CaseFile c, long now) {
        if (c.open()) {
            c.status = CaseFile.Status.CLOSED;
            c.closedAt = now;
        }
        c.finalPass = true;
        c.rerunRequested = false;
    }

    /**
     * Whether a background scan of a finished session should be dropped
     * because staff just decided about this player — the session it read is
     * the very one they judged, and reopening a case for it is what made a
     * closed investigation look like it "started again by itself".
     */
    static boolean recentlyDecided(List<CaseFile> ofPlayer, long now) {
        for (CaseFile c : ofPlayer) {
            if (c.open() || c.closedAt <= 0 || now - c.closedAt > DECIDED_QUIET_MS) continue;
            boolean staff = c.label == CaseFile.Label.CHEATER || c.label == CaseFile.Label.CLEAN
                    || c.action == CaseFile.Action.BANNED || c.action == CaseFile.Action.DISMISSED;
            if (staff) return true;
        }
        return false;
    }
}
