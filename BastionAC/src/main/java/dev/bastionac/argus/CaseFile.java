package dev.bastionac.argus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One investigation: who, why it was opened, what the analysis found, what
 * the owner decided. Plain public fields — it is stored as JSON as it is.
 */
public final class CaseFile {

    public enum Status { COLLECTING, QUEUED, ANALYZING, ANALYZED, CLOSED }

    public enum Verdict {
        NONE("ещё не разобрано", "&7"),
        INSUFFICIENT("мало данных", "&7"),
        CLEAN("чисто", "&a"),
        SUSPECT("подозрительно", "&e"),
        GUILTY("виновен", "&c");

        public final String label;
        public final String colour;

        Verdict(String label, String colour) {
            this.label = label;
            this.colour = colour;
        }
    }

    /** The owner's own call — the ground truth Argus calibrates on. */
    public enum Label {
        NONE("без решения"), CHEATER("читер"), CLEAN("не читер"), UNSURE("неясно");

        public final String label;

        Label(String label) {
            this.label = label;
        }
    }

    public enum Action { NONE, NOTIFIED, BANNED, DISMISSED }

    public String id;
    public String uuid;
    public String name;
    public long openedAt;
    /** First analysis not before this. */
    public long dueAt;
    /** Final verdict not after this. */
    public long deadlineAt;
    public long lastSignalAt;
    public long lastAnalyzedAt;
    public long closedAt;
    public Status status = Status.COLLECTING;
    /** New signals since the last analysis. */
    public boolean pendingSignals;
    public boolean playerLeft;

    public List<SignalEntry> signals = new ArrayList<>();
    public Map<String, Integer> signalCounts = new LinkedHashMap<>();

    public int analyses;
    public Verdict verdict = Verdict.NONE;
    /** 0..1, the calibrated probability the player cheats. */
    public double score;
    /** Sum of the evidence that went into the score. */
    public double evidence;
    public Map<String, Double> categories = new LinkedHashMap<>();
    /** Human-readable findings, strongest first. */
    public List<String> findings = new ArrayList<>();
    public List<FeatureRow> features = new ArrayList<>();
    /** Evidence per feature before the model's weights — what calibration learns from. */
    public Map<String, Double> evidenceByKey = new LinkedHashMap<>();
    /** Evidence the triggering signals added. */
    public double signalEvidence;
    /** The deadline pass (the last word inside the hour) has been made. */
    public boolean finalPass;
    public List<String> sessionFiles = new ArrayList<>();
    public double dataMinutes;
    public boolean worldSampled;
    public String lastError;

    public Label label = Label.NONE;
    public String labelBy;
    public long labelAt;
    public String labelNote;

    public Action action = Action.NONE;
    public String actionBy;
    public long actionAt;
    public String actionNote;

    public String reportFile;

    /** Why the case was closed when nobody decided it — e.g. a new episode took over (3.1.2). */
    public String closeNote;
    /** The player's previous case, when this one was opened as a new episode after it. */
    public String previousCaseId;

    /**
     * An explicit span to read instead of "around the case" — a past session
     * staff asked about, or the last session of a player who is offline
     * (3.3.2). Zero = the usual window. Live play clears it.
     */
    public long windowFrom, windowTo;
    /** What that span is, for the staff ("сессия 15.10 21:04–22:10"). */
    public String windowNote;
    /** Staff were told that the alerts behind this case did not hold up (once). */
    public boolean clearedNotified;

    /** Bumped for every analysis handed to the Argus thread; a result carries the one it was started with. */
    public int generation;
    /** An analysis of this case is on the Argus thread right now (not stored: a restart loses the job). */
    public transient boolean inFlight;
    /** Someone asked for another analysis while one was running. */
    public transient boolean rerunRequested;
    /** Staff who asked for this case and want its result, whatever it is (session only). */
    public transient java.util.Set<String> waitingStaff;

    public static final class SignalEntry {
        public long at;
        public String kind;
        public String detail;
    }

    /** One feature as the scorer saw it — kept for the report and for calibration. */
    public static final class FeatureRow {
        public String key;
        public double value;
        public double support;
        /** The reference it was compared with (baseline or prior median). */
        public double ref;
        public double scale;
        public double z;
        public double evidence;
        /** "baseline", "prior", "self" … */
        public String source;
    }

    public boolean open() {
        return status != Status.CLOSED;
    }

    /**
     * Closed without anyone deciding, yet its verdict still asks for a look:
     * "suspicious" or "guilty" with no label, no ban and no dismissal. Such a
     * case stays in the default list — closing it for a new episode must not
     * make an undecided verdict disappear from the staff's view.
     */
    public boolean awaitsDecision() {
        if (open() || label != Label.NONE || action == Action.BANNED || action == Action.DISMISSED) return false;
        if (verdict == Verdict.SUSPECT || verdict == Verdict.GUILTY) return true;
        // Alerts fired but the telemetry was too thin to judge either way: a
        // person has to look — closing it silently is how it went unnoticed (3.3.2).
        return verdict == Verdict.INSUFFICIENT && count(Signal.Kind.ALERT) > 0;
    }

    public void addSignal(Signal s) {
        SignalEntry e = new SignalEntry();
        e.at = s.at();
        e.kind = s.kind().name();
        e.detail = s.detail();
        signals.add(e);
        while (signals.size() > 60) signals.remove(0);
        signalCounts.merge(s.kind().name(), 1, Integer::sum);
        lastSignalAt = s.at();
        pendingSignals = true;
    }

    public int count(Signal.Kind kind) {
        return signalCounts.getOrDefault(kind.name(), 0);
    }
}
