package dev.bastionac.core;

import dev.bastionac.BastionAC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Attack-chain correlation — the EDR layer over the per-check engine.
 *
 * <p>Version 3 replaces the point score with a question that maps onto how
 * the staff actually reason about a suspect: <em>how many different
 * checks</em> has this player tripped at alert level recently?
 *
 * <ul>
 *   <li><b>One check</b> — the chain says nothing. That check's own ladder
 *       (kick at {@code kickVl}, temp-ban after {@code alertsToBan} alerts
 *       unless the check is in {@code banExcludedChecks}) is the whole
 *       answer. A nuker is a nuker; it does not need a second opinion.</li>
 *   <li><b>Two distinct checks</b> — {@link Stage#WATCH}: the player goes
 *       on the watch list. Staff get a chain alert, alert thresholds tighten
 *       by {@link #WATCH_SEVERITY}, telemetry is kept. No punishment.</li>
 *   <li><b>Three distinct ban-eligible checks</b> — {@link Stage#BURNED}:
 *       three independent detectors agreeing inside one window is the
 *       composite evidence the ladder was designed for. The ban goes
 *       through {@link BanManager#chainBan} on the <em>normal</em>
 *       escalation step (prior bans decide the length, not "top of the
 *       ladder"), and checks the admin excluded from banning never count
 *       toward it — they still count toward WATCH.</li>
 * </ul>
 *
 * <p>PROBE (sub-alert flags), RECON (through-wall tracking) and EVASION
 * (behaviour changes around staff) are <em>context</em>: recorded, shown in
 * the report and the panel, never a ban input on their own. Enough context
 * (three RECON or one EVASION event) promotes a single-check chain to WATCH
 * so the humans look — which is exactly what a second opinion is for.
 *
 * <p>The old model banned on points, and a kick fed an EVASION event back
 * into the chain that had just caused it; every kick therefore became a
 * week-long ban. Points, evasion-on-kick and the per-kind cap are gone.
 *
 * <p><b>Rejoin survival.</b> The chain map is deliberately NOT cleared on
 * disconnect: the window itself expires by time, so a player one check short
 * of WATCH cannot reset it by relogging. Stale entries are pruned by
 * {@link #sweep}.
 */
public final class AttackChainDetector {

    public enum Stage { RECON, PROBE, EXPLOIT, EVASION }

    /** What the chain currently means. */
    public enum Verdict { NONE, WATCH, BURNED }

    /** One correlated event inside the current chain. */
    public record ChainEvent(long at, Stage stage, String kind, String detail, int points) {
        /** Legacy shape: points default to the stage weight. */
        public ChainEvent(long at, Stage stage, String kind, String detail) {
            this(at, stage, kind, detail, weightOf(stage));
        }
    }

    /** The live chain of one player. */
    public static final class Chain {
        public final UUID uuid;
        public final String name;
        public final Deque<ChainEvent> events = new ArrayDeque<>();
        /** Distinct checks at alert level inside the window — the number that matters now. */
        public int score;
        /** Every event inside the window, context included. */
        public int rawScore;
        public int burnedCount;
        long lastAt;
        /** WATCH is reported once per episode, not on every further event. */
        boolean reportedWatch;

        Chain(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        /** Distinct check keys with at least one EXPLOIT event still inside the window. */
        public Set<String> alertKinds() {
            return alertKinds(System.currentTimeMillis());
        }

        Set<String> alertKinds(long now) {
            Set<String> out = new LinkedHashSet<>();
            for (ChainEvent e : events) {
                if (e.stage() == Stage.EXPLOIT && now - e.at() <= windowMs) out.add(e.kind());
            }
            return out;
        }

        /** Alert kinds minus the checks the admin excluded from banning. */
        public Set<String> eligibleKinds() {
            Set<String> out = alertKinds(System.currentTimeMillis());
            out.removeIf(k -> excluded.contains(k));
            return out;
        }

        int contextCount(Stage stage, long now) {
            int n = 0;
            for (ChainEvent e : events) {
                if (e.stage() == stage && now - e.at() <= windowMs) n++;
            }
            return n;
        }

        /** Points contributed by one module so far (kept for the panel). */
        int kindPoints(String kind) {
            int sum = 0;
            for (ChainEvent e : events) {
                if (e.kind().equals(kind)) sum += e.points();
            }
            return sum;
        }

        public Verdict verdict() {
            return verdictAt(System.currentTimeMillis());
        }

        Verdict verdictAt(long now) {
            if (now - lastAt > windowMs) return Verdict.NONE;
            Set<String> alerts = alertKinds(now);
            int eligible = 0;
            for (String k : alerts) if (!excluded.contains(k)) eligible++;
            if (eligible >= banDistinct) return Verdict.BURNED;
            if (alerts.size() >= watchDistinct) return Verdict.WATCH;
            if (!alerts.isEmpty() && (contextCount(Stage.RECON, now) >= 3 || contextCount(Stage.EVASION, now) >= 1)) {
                return Verdict.WATCH;
            }
            return Verdict.NONE;
        }
    }

    private static final Map<UUID, Chain> CHAINS = new HashMap<>();

    // ------------------------------------------------------------------ policy (config-driven)

    /** How long a chain stays open after its last event (ms). */
    static volatile long windowMs = 60 * 60_000L;
    /** Distinct alert-level checks that put a player on the watch list. */
    static volatile int watchDistinct = 2;
    /** Distinct ban-eligible alert-level checks that burn the chain. */
    static volatile int banDistinct = 3;
    /** Checks that never count toward a chain ban (mirror of punishment.banExcludedChecks). */
    static volatile Set<String> excluded = Set.of();
    /** Chains untouched for this long are pruned (memory bound for offline players). */
    private static final long PRUNE_AFTER_MS = 3 * 60 * 60_000L;

    /** Severity multiplier applied to alert thresholds while a player is on the watch list. */
    public static double WATCH_SEVERITY = 0.9;
    /** @deprecated kept for the panel; same value as {@link #WATCH_SEVERITY}. */
    @Deprecated
    public static double ARMED_SEVERITY = WATCH_SEVERITY;

    /** Applies the punishment policy from the config (called on load/reload). */
    public static synchronized void configure(int windowMinutes, int watchDistinctChecks, int banDistinctChecks,
                                              java.util.Collection<String> excludedChecks) {
        windowMs = Math.max(1, windowMinutes) * 60_000L;
        watchDistinct = Math.max(1, watchDistinctChecks);
        banDistinct = Math.max(watchDistinct, banDistinctChecks);
        excluded = excludedChecks == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(excludedChecks));
    }

    private AttackChainDetector() {}

    // ------------------------------------------------------------------ feeding

    /** Stage weight — only a display value now. */
    static int weightOf(Stage stage) {
        return switch (stage) {
            case RECON -> 1;
            case PROBE -> 1;
            case EXPLOIT -> 5;
            case EVASION -> 10;
        };
    }

    /**
     * Records an event into the player's chain and re-evaluates it. Called
     * from the check engine (probes/exploits) and the context producers
     * (recon/evasion). A WATCH verdict is reported once per episode; a
     * BURNED verdict hands the incident to the ban manager and starts a
     * fresh chain.
     */
    public static synchronized void event(ServerPlayerEntityLike player, Stage stage, String kind, String detail) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        Chain chain = CHAINS.computeIfAbsent(player.uuid(), u -> new Chain(player.uuid(), player.name()));
        // Close stale chains: a quiet window starts a new episode.
        if (now - chain.lastAt > windowMs) {
            chain.events.clear();
            chain.reportedWatch = false;
        }
        Verdict before = chain.verdictAt(now);
        chain.lastAt = now;
        chain.events.addLast(new ChainEvent(now, stage, kind, detail, weightOf(stage)));
        while (chain.events.size() > 128) chain.events.pollFirst();
        // Drop events that fell out of the window so the report shows the episode, not history.
        chain.events.removeIf(e -> now - e.at() > windowMs);
        chain.score = chain.alertKinds(now).size();
        chain.rawScore = chain.events.size();

        Verdict after = chain.verdictAt(now);
        if (after == Verdict.BURNED) {
            report(chain, Verdict.BURNED);
            banOnBurned(chain);
            chain.events.clear();
            chain.score = 0;
            chain.rawScore = 0;
            chain.reportedWatch = false;
        } else if (after == Verdict.WATCH && (before != Verdict.WATCH || !chain.reportedWatch)) {
            chain.reportedWatch = true;
            report(chain, Verdict.WATCH);
        }
    }

    /** Probe feed: a sub-threshold borderline flag worth remembering (context only). */
    public static void probe(ServerPlayerEntityLike player, String kind, String detail) {
        event(player, Stage.PROBE, kind, detail);
    }

    /** Exploit feed: a confirmed alert (VL ≥ alert threshold). */
    public static void exploit(ServerPlayerEntityLike player, String kind, String detail) {
        event(player, Stage.EXPLOIT, kind, detail);
    }

    /** Evasion feed: behaviour changes around staff (context only). */
    public static void evasion(ServerPlayerEntityLike player, String kind, String detail) {
        event(player, Stage.EVASION, kind, detail);
    }

    /** Recon feed: sustained through-wall tracking (context only). */
    public static void recon(ServerPlayerEntityLike player, String kind, String detail) {
        event(player, Stage.RECON, kind, detail);
    }

    // ------------------------------------------------------------------ consequences

    private static void banOnBurned(Chain chain) {
        chain.burnedCount++;
        MinecraftServerLike srv = BastionAC.serverLike();
        if (srv == null) return; // tests / not yet started
        Set<String> kinds = chain.eligibleKinds();
        srv.execute(() -> BanManager.chainBan(chain.uuid, chain.name, chain.burnedCount, String.join("+", kinds)));
    }

    /** Live severity multiplier for a player (never cached). */
    public static synchronized double severityMultiplier(UUID uuid) {
        Chain chain = CHAINS.get(uuid);
        if (chain == null) return 1.0;
        return chain.verdictAt(System.currentTimeMillis()) == Verdict.NONE ? 1.0 : WATCH_SEVERITY;
    }

    /** True while the player is on the watch list (telemetry gate). */
    public static synchronized boolean isWatched(UUID uuid) {
        Chain chain = CHAINS.get(uuid);
        return chain != null && chain.verdictAt(System.currentTimeMillis()) != Verdict.NONE;
    }

    // ------------------------------------------------------------------ queries

    public static synchronized Chain chainOf(UUID uuid) {
        return CHAINS.get(uuid);
    }

    /** Snapshot of all open chains, for the admin UI: watched first, then by breadth. */
    public static synchronized List<Chain> activeChains() {
        long now = System.currentTimeMillis();
        List<Chain> out = new ArrayList<>();
        for (Chain c : CHAINS.values()) {
            if (now - c.lastAt <= windowMs && !c.events.isEmpty()) out.add(c);
        }
        out.sort((a, b) -> {
            int v = Integer.compare(b.verdictAt(now).ordinal(), a.verdictAt(now).ordinal());
            if (v != 0) return v;
            int s = Integer.compare(b.score, a.score);
            return s != 0 ? s : Long.compare(b.lastAt, a.lastAt);
        });
        return out;
    }

    /** Count of open chains, cheap enough for a panel tooltip. */
    public static synchronized int activeCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Chain c : CHAINS.values()) {
            if (now - c.lastAt <= windowMs && !c.events.isEmpty()) n++;
        }
        return n;
    }

    /** Prunes chains whose window expired long ago. */
    public static synchronized void sweep() {
        long cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS;
        CHAINS.values().removeIf(c -> c.lastAt < cutoff);
    }

    /** @deprecated the chain must survive a rejoin; kept as a no-op for the disconnect hook. */
    @Deprecated
    public static void forget(UUID uuid) {
        // intentionally not removing — see class javadoc
    }

    /** Test isolation: drops every chain and restores default policy. */
    static synchronized void forgetAllForTests() {
        CHAINS.clear();
        configure(60, 2, 3, Set.of());
    }

    private static void report(Chain chain, Verdict verdict) {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(verdict == Verdict.WATCH ? "[CHAIN-WATCH] " : "[CHAIN-BURNED] ");
        Set<String> alerts = chain.alertKinds(now);
        sb.append(chain.name).append(": ").append(alerts.size()).append(" разных проверок на уровне алерта ")
                .append(alerts).append(", событий ").append(chain.events.size());
        int recon = chain.contextCount(Stage.RECON, now);
        int evasion = chain.contextCount(Stage.EVASION, now);
        if (recon > 0 || evasion > 0) {
            sb.append(" — контекст: recon ").append(recon).append(", evasion ").append(evasion);
        }
        sb.append(" — ");
        int n = 0;
        for (ChainEvent e : chain.events) {
            if (n++ > 0) sb.append(" → ");
            sb.append(e.stage()).append('/').append(e.kind());
        }
        String line = sb.toString();
        BastionAC.LOGGER.warn("{}", line);
        // Player-list access belongs on the server thread; the feed can arrive from netty.
        MinecraftServerLike srv = BastionAC.serverLike();
        String grade = verdict == Verdict.WATCH ? "watch" : "burned";
        if (srv != null) srv.execute(() -> Alerts.sendChain(chain.name, chain.uuid, grade, line));
        else Alerts.sendChain(chain.name, chain.uuid, grade, line);
    }

    /** Minimal player view so tests can feed the detector without Minecraft. */
    public interface ServerPlayerEntityLike {
        UUID uuid();
        String name();
    }

    /** Minimal server view so the ban hook stays testable without Minecraft. */
    public interface MinecraftServerLike {
        void execute(Runnable task);
    }
}
