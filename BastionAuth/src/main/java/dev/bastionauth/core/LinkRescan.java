package dev.bastionauth.core;

import dev.bastionauth.db.Database;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The re-check of the link ledger (08.11; a full recount since 12.11), pure apart from reading the
 * database: every registered account is analysed with what is stored for it, both directions of a
 * pair are kept at their stronger score, and the result says what to rewrite, what to add and what
 * to take off the ledger. {@link AuthManager#rescanLinks} applies it, at start and once a day.
 *
 * <p>12.11: the ledger kept pairs the corrected model had long stopped linking —
 * pairs scored at 0 points or strangers sharing a pirate launcher's nil id —
 * because a lower score was only accepted for the few signals discredited on 08.11, and a reviewed
 * pair (WATCH) was never looked at again. Now every open pair (NEW, WATCH) is what the model says
 * today; below "possible" it is archived. TRUSTED and ALT are a human's decision and stay as they are.
 *
 * <p>Two signals describe a moment that the stored data cannot show again: "registered right after
 * the other was last on this machine" ({@code timing}) and "… last at this address"
 * ({@code ip-timing}, from the audit log, which is pruned after 90 days). Those are carried over
 * from the ledger, but only while the thing they stand on — the same machine or launcher, the same
 * address — is still found now. A pair whose machine match turned out to be a shared default
 * profile or a nil launcher id loses its timing with it.
 */
public final class LinkRescan {

    public record Rescore(String uuidA, String uuidB, String nameA, String nameB,
                          int oldScore, int score, AccountLinker.Grade grade, String signals) {}

    public record Added(String uuid, String name, String otherUuid, String otherName,
                        int score, AccountLinker.Grade grade, String signals, String describe) {}

    /** A pair the model no longer links: it leaves the ledger for the archive. */
    public record Cleared(String uuidA, String uuidB, String nameA, String nameB, String verdict,
                          int oldScore, String oldSignals, int score, String signals) {}

    public record Result(int accounts, List<Rescore> rescores, List<Added> added, List<Cleared> cleared) {
        public Result(int accounts, List<Rescore> rescores, List<Added> added) {
            this(accounts, rescores, added, List.of());
        }
    }

    /** Below this a pair is not a link ("possible" starts here). */
    static final int KEEP_FROM = 50;

    public static Result compute(Database db, AccountLinker linker, long now) {
        Map<String, AccountLinker.Candidate> best = new HashMap<>();
        Map<String, String[]> sides = new HashMap<>();
        Set<String> registered = new HashSet<>();
        List<Database.LinkRow> all = db.allLinkRows();
        for (Database.LinkRow me : all) registered.add(me.uuid());
        for (Database.LinkRow me : all) {
            // Every machine the account was seen on, not only the last one.
            List<Database.SightingRow> seen = new ArrayList<>(db.sightingsOfUuid(me.uuid()));
            if (seen.isEmpty()) seen.add(null);
            String name = me.username() == null ? "?" : me.username();
            for (Database.SightingRow sight : seen) {
                for (String ip : new String[]{me.lastIpHmac(), me.regIpHmac()}) {
                    if (ip == null && sight == null && me.pwFp() == null) continue;
                    AccountLinker.Material m = new AccountLinker.Material(
                            sight == null ? null : sight.deviceHash(), sight == null ? null : sight.profileHash(),
                            sight == null ? null : sight.clientUuid(), sight == null ? null : sight.hostUsed(),
                            null, true, ip, me.pwFp(), false);
                    for (AccountLinker.Candidate c : linker.analyse(me.uuid(), name, m, now, db, me.createdAt(), 0)) {
                        String key = Database.pairKey(me.uuid(), c.uuid);
                        AccountLinker.Candidate prev = best.get(key);
                        if (prev == null || c.score() > prev.score()) {
                            best.put(key, c);
                            sides.put(key, new String[]{me.uuid(), name});
                        }
                    }
                }
            }
        }
        List<Rescore> rescores = new ArrayList<>();
        List<Cleared> cleared = new ArrayList<>();
        Set<String> inLedger = new HashSet<>();
        AccountLinker.Weights w = linker.weights();
        for (Database.LinkLedgerRow row : db.allLinks()) {
            String key = Database.pairKey(row.uuidA(), row.uuidB());
            inLedger.add(key);
            // Only between two registered accounts: a name that never registered has no address or
            // password on file, and its link from the join stays as it was.
            if (!registered.contains(row.uuidA()) || !registered.contains(row.uuidB())) continue;
            if (!open(row.verdict())) continue;
            AccountLinker.Candidate c = best.get(key);
            Set<String> was = parts(row.bestSignals());
            List<String> now2 = new ArrayList<>();
            int score = 0;
            if (c != null) {
                score = c.score();
                now2.addAll(parts(AccountLinker.describeSignals(c)));
                // The moments the stored data cannot show again, carried over while their ground holds.
                if (was.contains("timing") && !c.registeredSoonAfterBan && (c.sameDeviceHash || c.sameClientUuid)) {
                    score += w.timingAfterBan();
                    now2.add("timing");
                }
                if (was.contains("ip-timing") && !c.ipTiming && c.sameIpHmac) {
                    score = Math.max(score, AccountLinker.ADDRESS_FLOOR);
                    now2.add("ip-timing");
                }
            }
            String signals = String.join("+", order(now2));
            if (score < KEEP_FROM) {
                cleared.add(new Cleared(row.uuidA(), row.uuidB(), row.nameA(), row.nameB(), row.verdict(),
                        row.bestScore(), row.bestSignals(), score, signals));
                continue;
            }
            if (score != row.bestScore() || !signals.equals(row.bestSignals() == null ? "" : row.bestSignals())) {
                rescores.add(new Rescore(row.uuidA(), row.uuidB(), row.nameA(), row.nameB(), row.bestScore(),
                        score, linker.grade(score), signals));
            }
        }
        List<Added> added = new ArrayList<>();
        for (Map.Entry<String, AccountLinker.Candidate> e : best.entrySet()) {
            AccountLinker.Candidate c = e.getValue();
            if (inLedger.contains(e.getKey()) || c.score() < 50) continue;
            String[] me = sides.get(e.getKey());
            AccountLinker.Grade g = linker.grade(c.score());
            added.add(new Added(me[0], me[1], c.uuid, c.username, c.score(), g,
                    AccountLinker.describeSignals(c), AccountLinker.describe(c, g)));
        }
        return new Result(all.size(), rescores, added, cleared);
    }

    /** A pair nobody decided for good: unreviewed, or reviewed and kept under watch. */
    static boolean open(String verdict) {
        return verdict == null || verdict.isBlank() || "NEW".equals(verdict) || "WATCH".equals(verdict);
    }

    private static Set<String> parts(String signals) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (signals == null) return out;
        for (String p : signals.split("[+]")) if (!p.isBlank()) out.add(p);
        return out;
    }

    private static final List<String> ORDER = List.of("device", "profile", "launcher-uuid", "mods", "ip", "name",
            "timing", "ip-timing", "rare-password", "password");

    /** The signals in {@link AccountLinker#describeSignals}'s order, each once. */
    private static List<String> order(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String k : ORDER) if (in.contains(k)) out.add(k);
        for (String k : in) if (!out.contains(k)) out.add(k);
        return out;
    }

    /** Whether a link's signals were nothing but the ones the 08.11 review discredited. */
    static boolean onlyDiscredited(String signals) {
        if (signals == null || signals.isBlank()) return true;
        for (String part : signals.split("[+]")) {
            if (!part.equals("profile") && !part.equals("host") && !part.equals("timing")) return false;
        }
        return true;
    }

    private LinkRescan() {}
}
