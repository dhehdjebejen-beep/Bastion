package dev.bastionauth.core;

import dev.bastionauth.BastionAuth;
import dev.bastionauth.db.Database;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Account-link analysis — a composite, multi-signal correlation model. A
 * single matching signal (same device hash, same IP, same behaviour)
 * proves nothing on its own; a <em>composite</em> of several independent
 * signals does. Each signal earns points, the sum grades the link:
 *
 * <ul>
 *   <li><b>DeviceHash equal</b> — brand + client options + subnet all matched
 *       through an HMAC: 60 points. Options are stable per installation, so a
 *       shared hash is close to a shared machine, but alone it stays below the
 *       hard threshold — two brothers on one family PC land exactly here.</li>
 *   <li><b>Profile equal</b> — the same brand + options seen from a
 *       <em>different</em> network (home Wi-Fi vs mobile): 35 points. The
 *       device hash folds the subnet in, so without this a twin who plays
 *       from two networks was two strangers.</li>
 *   <li><b>Launcher UUID equal</b> — the client claimed the same profile
 *       UUID under a different name, and it is not the name-derived one:
 *       70 points. Cracked launchers mint that UUID once per installation.</li>
 *   <li><b>Plugin channels equal</b> — the same non-baseline list of client
 *       mod channels: 20 points. Same mod set, likely same install.</li>
 *   <li><b>Same IP (HMAC)</b> — 25 points. A CGNAT tower shares an IP across
 *       hundreds of households, so an IP alone must never convict.</li>
 *   <li><b>Same typed address</b> — 5 points. A bookmark habit.</li>
 *   <li><b>Name similarity</b> — Levenshtein ≤ 2 on a 3+ char stem (the
 *       "builder / builderr" pattern): 15 points.</li>
 *   <li><b>Registration timing</b> — a new account registered within the
 *       configured window of the other one's last sighting: 20 points.</li>
 *   <li><b>Password reuse</b> — equal deterministic, keyed fingerprints
 *       across accounts (compared only on a successful login, when the
 *       joiner's fingerprint exists): +40.</li>
 * </ul>
 *
 * <p>Grades: 0–49 <b>none</b>, 50–79 <b>possible</b>, 80–99 <b>likely</b>,
 * 100+ <b>confirmed</b>. Reports to staff; the punishment decision stays
 * human (per ToS §7). Every result ≥ 50 is written to the permanent
 * per-pair ledger ({@code account_links}), so a link found once is never
 * forgotten even after the audit log and fingerprints age out.
 */
public final class AccountLinker {

    /** Signals and their point values. Tunable via constructor for tests. */
    public record Weights(int deviceHash, int sameIp, int nameSimilarity, int timingAfterBan, int passwordReuse,
                          int profile, int clientUuid, int channels, int host) {
        public Weights(int deviceHash, int sameIp, int nameSimilarity, int timingAfterBan, int passwordReuse) {
            this(deviceHash, sameIp, nameSimilarity, timingAfterBan, passwordReuse, 35, 70, 20, 5);
        }

        public static Weights defaults() {
            return new Weights(60, 25, 15, 20, 40, 35, 70, 20, 5);
        }
    }

    public enum Grade { NONE, POSSIBLE, LIKELY, CONFIRMED }

    /** One candidate account with the raw signals observed. */
    public static final class Candidate {
        public final String uuid;
        public final String username;
        public final boolean sameDeviceHash;
        public final boolean sameIpHmac;
        public final boolean similarName;
        public final boolean registeredSoonAfterBan;
        public final boolean samePasswordPhc;
        public boolean sameProfile;
        public boolean sameClientUuid;
        public boolean sameChannels;
        public boolean sameHost;
        int score;

        Candidate(String uuid, String username, boolean device, boolean ip, boolean name,
                  boolean timing, boolean password) {
            this.uuid = uuid;
            this.username = username;
            this.sameDeviceHash = device;
            this.sameIpHmac = ip;
            this.similarName = name;
            this.registeredSoonAfterBan = timing;
            this.samePasswordPhc = password;
        }

        public int score() {
            return score;
        }
    }

    /**
     * Everything known about the joiner at analysis time. Fields may be
     * null when the material has not arrived yet (channels come seconds
     * after the join; the password only after a successful login).
     */
    public record Material(String deviceHash, String profileHash, String clientUuid, String hostUsed,
                           String channelsHash, boolean channelsBaseline, String ipHmac, String pwFp) {}

    private final Weights weights;
    private final long timingWindowMs;

    public AccountLinker() {
        this(Weights.defaults(), 72L * 3_600_000L);
    }

    public AccountLinker(Weights weights) {
        this(weights, 72L * 3_600_000L);
    }

    public AccountLinker(Weights weights, long timingWindowMs) {
        this.weights = weights;
        this.timingWindowMs = timingWindowMs;
    }

    // ------------------------------------------------------------------ core

    /** Pure scoring (legacy five-signal form): signals in, grade out. Unit-tested. */
    public int score(boolean sameDeviceHash, boolean sameIp, boolean similarName,
                      boolean registeredSoonAfterBan, boolean samePasswordPhc) {
        return score(sameDeviceHash, sameIp, similarName, registeredSoonAfterBan, samePasswordPhc,
                false, false, false, false);
    }

    /** Pure scoring over every signal. Profile only counts when the device hash did not match already. */
    public int score(boolean sameDeviceHash, boolean sameIp, boolean similarName,
                     boolean registeredSoonAfterBan, boolean samePasswordPhc,
                     boolean sameProfile, boolean sameClientUuid, boolean sameChannels, boolean sameHost) {
        int s = 0;
        if (sameDeviceHash) s += weights.deviceHash();
        else if (sameProfile) s += weights.profile();
        if (sameClientUuid) s += weights.clientUuid();
        if (sameChannels) s += weights.channels();
        if (sameIp) s += weights.sameIp();
        if (sameHost) s += weights.host();
        if (similarName) s += weights.nameSimilarity();
        if (registeredSoonAfterBan) s += weights.timingAfterBan();
        if (samePasswordPhc) s += weights.passwordReuse();
        return s;
    }

    /**
     * The gate decision for one ledger row, pure. A human's standing decision
     * outranks the score in both directions: TRUSTED clears a pair the model
     * is (correctly) still suspicious of, ALT binds a pair the model never
     * scored high enough to catch. Without a decision the best grade ever seen
     * is compared against the caller's threshold, as it always was.
     */
    public static boolean linkedByLedger(String verdict, String bestGrade, Grade min) {
        LinkVerdict v = LinkVerdict.of(verdict);
        if (v.decidesAlone()) return v.linkedWhenDecidingAlone();
        try {
            return Grade.valueOf(bestGrade).ordinal() >= min.ordinal();
        } catch (IllegalArgumentException | NullPointerException unknownGrade) {
            return false;
        }
    }

    /**
     * The same decision for the referral bonus the state pays (MaxCore's
     * microloan limit used to ask this too; it now respects trust).
     * {@link LinkVerdict#TRUSTED} is deliberately ignored here:
     * it says the two accounts may deal with <em>each other</em> (pay, trade,
     * duel, marry), not that one person may collect a state bonus twice. ALT
     * still binds; everything else is decided by the grade, as before.
     */
    public static boolean linkedForStateBenefits(String verdict, String bestGrade, Grade min) {
        if (LinkVerdict.of(verdict) == LinkVerdict.ALT) return true;
        try {
            return Grade.valueOf(bestGrade).ordinal() >= min.ordinal();
        } catch (IllegalArgumentException | NullPointerException unknownGrade) {
            return false;
        }
    }

    public Grade grade(int score) {
        if (score >= 100) return Grade.CONFIRMED;
        if (score >= 80) return Grade.LIKELY;
        if (score >= 50) return Grade.POSSIBLE;
        return Grade.NONE;
    }

    // ------------------------------------------------------------------ matching helpers

    /** Levenshtein ≤ 2 over the lowercase stems, after trimming common
     *  decorations (trailing digits/underscores like "builder" vs
     *  "builderr"). Pure, unit-tested. */
    public static boolean similarNames(String a, String b) {
        if (a == null || b == null) return false;
        String sa = stem(a), sb = stem(b);
        if (sa.length() < 3 || sb.length() < 3) return false;
        if (sa.equals(sb)) return !a.equals(b);   // same stem, different decoration — the classic twin pattern
        return levenshtein(sa, sb) <= 2;
    }

    /** Strips trailing digits and underscores: "steve_builder2" → "steve_builder". */
    static String stem(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        int end = s.length();
        while (end > 0 && (Character.isDigit(s.charAt(end - 1)) || s.charAt(end - 1) == '_')) end--;
        return s.substring(0, end);
    }

    static int levenshtein(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        return d[a.length()][b.length()];
    }

    // ------------------------------------------------------------------ live analysis

    /** Legacy entry (tests): device + ip + name + timing + password. */
    public List<Candidate> analyse(String uuid, String name, String deviceHash, String ipHmac,
                                    String pwFp, long now, Database db) {
        return analyse(uuid, name, deviceHash, ipHmac, pwFp, now, db, 0);
    }

    /** Session-created variant used on join (no password yet). */
    public List<Candidate> analyse(String uuid, String name, String deviceHash, String ipHmac,
                                    long now, Database db) {
        return analyse(uuid, name, deviceHash, ipHmac, null, now, db, 0);
    }

    /** Legacy entry with the joiner's creation time. */
    public List<Candidate> analyse(String uuid, String name, String deviceHash, String ipHmac,
                                    String pwFp, long now, Database db, long myCreatedAt) {
        return analyse(uuid, name, new Material(deviceHash, null, null, null, null, true, ipHmac, pwFp),
                now, db, myCreatedAt);
    }

    /**
     * Full analysis. Candidates are surfaced by every indexed signal
     * (device hash, install profile, launcher UUID, non-baseline channel
     * list, password fingerprint) and then scored on all of them, so a
     * twin found through one signal still collects the others. Runs on the
     * auth worker; must not touch game state.
     */
    public List<Candidate> analyse(String uuid, String name, Material m, long now, Database db, long myCreatedAt) {
        List<Candidate> out = new ArrayList<>();
        if (db == null || m == null || m.deviceHash() == null) return out;
        try {
            // --- surface candidates -------------------------------------------------
            Map<String, Database.DeviceRow> deviceSeen = new HashMap<>();
            for (Database.DeviceRow row : db.sightingsOfDevice(m.deviceHash(), uuid)) {
                deviceSeen.put(row.uuid(), row);
            }
            Map<String, Database.SightingRow> profileSeen = new HashMap<>();
            if (m.profileHash() != null) {
                for (Database.SightingRow row : db.sightingsOfProfile(m.profileHash(), uuid)) {
                    profileSeen.put(row.uuid(), row);
                }
            }
            Map<String, Database.SightingRow> clientUuidSeen = new HashMap<>();
            if (m.clientUuid() != null) {
                for (Database.SightingRow row : db.sightingsOfClientUuid(m.clientUuid(), uuid)) {
                    clientUuidSeen.put(row.uuid(), row);
                }
            }
            Map<String, Database.SightingRow> channelsSeen = new HashMap<>();
            if (m.channelsHash() != null && !m.channelsBaseline()) {
                for (Database.SightingRow row : db.sightingsOfChannels(m.channelsHash(), uuid)) {
                    channelsSeen.put(row.uuid(), row);
                }
            }

            java.util.Set<String> candidateUuids = new java.util.LinkedHashSet<>();
            candidateUuids.addAll(deviceSeen.keySet());
            candidateUuids.addAll(profileSeen.keySet());
            candidateUuids.addAll(clientUuidSeen.keySet());
            // Channels alone never surface a candidate — the same popular mod
            // pack is not a person. They only add weight to one found elsewhere.

            Map<String, Database.LinkRow> users = new HashMap<>();
            for (Database.LinkRow row : db.linkRowsForUuids(new ArrayList<>(candidateUuids))) {
                users.put(row.uuid(), row);
            }
            if (m.pwFp() != null) {
                for (Database.LinkRow row : db.rowsWithPwFp(m.pwFp(), uuid)) {
                    users.putIfAbsent(row.uuid(), row);
                }
            }

            // --- score --------------------------------------------------------------
            for (Map.Entry<String, Database.LinkRow> e : users.entrySet()) {
                String cand = e.getKey();
                Database.LinkRow row = e.getValue();
                boolean sameDevice = deviceSeen.containsKey(cand);
                boolean sameProfile = profileSeen.containsKey(cand);
                boolean sameClientUuid = clientUuidSeen.containsKey(cand);
                boolean sameChannels = channelsSeen.containsKey(cand);
                boolean sameHost = false;
                if (m.hostUsed() != null && !m.hostUsed().isEmpty()) {
                    Database.SightingRow any = profileSeen.get(cand);
                    if (any == null) any = clientUuidSeen.get(cand);
                    if (any == null) any = channelsSeen.get(cand);
                    if (any != null && m.hostUsed().equals(any.hostUsed())) sameHost = true;
                }

                // The twin's last moment on THIS device — the faithful proxy
                // for "when they stopped being able to log in" (i.e. the
                // ban). Timing without a device sighting is judged against
                // the account's creation time, which is the weakest form.
                Database.DeviceRow sight = deviceSeen.get(cand);
                long lastSeenOnDevice = sight != null ? sight.lastSeen() : 0;
                if (lastSeenOnDevice == 0 && profileSeen.containsKey(cand)) {
                    lastSeenOnDevice = profileSeen.get(cand).lastSeen();
                }
                long timingAnchor = lastSeenOnDevice > 0 ? lastSeenOnDevice : row.createdAt();

                String candidateName = row.username() == null
                        ? "?" + row.uuid().substring(0, 8) : row.username();
                boolean similar = similarNames(name, candidateName);
                boolean sameIp = m.ipHmac() != null
                        && (m.ipHmac().equals(row.regIpHmac()) || m.ipHmac().equals(row.lastIpHmac()));
                boolean samePw = m.pwFp() != null && m.pwFp().equals(row.pwFp());
                boolean timing = isTimingSignal(myCreatedAt, now, timingAnchor);

                int sc = score(sameDevice, sameIp, similar, timing, samePw,
                        sameProfile, sameClientUuid, sameChannels, sameHost);
                if (sc >= 50) {
                    Candidate c = new Candidate(row.uuid(), candidateName, sameDevice, sameIp,
                            similar, timing, samePw);
                    c.sameProfile = sameProfile;
                    c.sameClientUuid = sameClientUuid;
                    c.sameChannels = sameChannels;
                    c.sameHost = sameHost;
                    c.score = sc;
                    out.add(c);
                }
            }
            out.sort((a, b) -> Integer.compare(b.score, a.score));
        } catch (Exception ex) {
            BastionAuth.LOGGER.warn("AccountLinker analysis failed for {}: {}", name, ex.toString());
        }
        return out;
    }

    /**
     * Timing signal, pure. The joiner's account must have been CREATED
     * within the window after the candidate's LAST SIGHTING on this
     * device: a banned player never logs in again, so their final sighting
     * is the punishment moment in all but name, and the spare account is
     * registered right after it. An old sibling account (created years
     * ago, e.g. two brothers sharing a PC) never qualifies.
     */
    public boolean isTimingSignal(long myCreatedAt, long now, long candidateLastSeen) {
        if (candidateLastSeen <= 0) return false;
        long created = myCreatedAt > 0 ? myCreatedAt : now;
        long delta = created - candidateLastSeen;
        return delta >= 0 && delta <= timingWindowMs;
    }

    /** Human-readable report line for staff/admin UI. */
    public static String describe(Candidate c, Grade g) {
        return String.format(Locale.ROOT, "%s ↔ %s: %d (%s) — %s",
                c.username, g, c.score, g.name(), describeSignals(c));
    }

    /**
     * The signals that built the link, as a compact "device+IP+name"
     * string — the machine-readable form the BastionAC bridge carries into
     * the network registry UI.
     */
    public static String describeSignals(Candidate c) {
        List<String> signals = new ArrayList<>();
        if (c.sameDeviceHash) signals.add("device");
        else if (c.sameProfile) signals.add("profile");
        if (c.sameClientUuid) signals.add("launcher-uuid");
        if (c.sameChannels) signals.add("mods");
        if (c.sameIpHmac) signals.add("ip");
        if (c.sameHost) signals.add("host");
        if (c.similarName) signals.add("name");
        if (c.registeredSoonAfterBan) signals.add("timing");
        if (c.samePasswordPhc) signals.add("password");
        return String.join("+", signals);
    }
}
