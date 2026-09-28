package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attack-chain correlation, v3: the chain counts <em>distinct checks at
 * alert level</em>, not points. One check — no chain verdict (its own
 * ladder decides). Two distinct — WATCH. Three distinct ban-eligible —
 * BURNED, then the chain resets. Probes, recon and evasion are context and
 * never a ban input on their own. The detector interface is Minecraft-free,
 * so these tests run without the game jar.
 */
class AttackChainDetectorTest {

    /** Minimal feed stand-in. */
    private static final class Fake implements AttackChainDetector.ServerPlayerEntityLike {
        final UUID uuid = UUID.randomUUID();
        final String name;
        Fake(String name) { this.name = name; }
        @Override public UUID uuid() { return uuid; }
        @Override public String name() { return name; }
    }

    @Test
    void probesAreContextOnly() {
        AttackChainDetector.forgetAllForTests();
        Fake p = new Fake("quiet");
        for (int i = 0; i < 25; i++) AttackChainDetector.probe(p, "reach", "3.05 margin");
        AttackChainDetector.Chain chain = AttackChainDetector.chainOf(p.uuid());
        assertNotNull(chain);
        assertEquals(0, chain.score, "probes never count as an alert-level check");
        assertEquals(AttackChainDetector.Verdict.NONE, chain.verdict());
        assertFalse(AttackChainDetector.isWatched(p.uuid()));
    }

    @Test
    void oneCheckAloneNeverBurnsHoweverOften() {
        AttackChainDetector.forgetAllForTests();
        Fake p = new Fake("nuker-only");
        for (int i = 0; i < 40; i++) AttackChainDetector.exploit(p, "nuker", "22 blocks/s");
        AttackChainDetector.Chain chain = AttackChainDetector.chainOf(p.uuid());
        assertEquals(1, chain.score);
        assertEquals(AttackChainDetector.Verdict.NONE, chain.verdict(),
                "a single module is that module's own ladder, not a chain");
        assertEquals(0, chain.burnedCount);
    }

    @Test
    void twoDistinctChecksWatchThreeBurn() {
        AttackChainDetector.forgetAllForTests();
        Fake p = new Fake("multi");
        AttackChainDetector.exploit(p, "bigmove", "12 blocks");
        assertEquals(AttackChainDetector.Verdict.NONE, AttackChainDetector.chainOf(p.uuid()).verdict());
        AttackChainDetector.exploit(p, "fly", "rising");
        assertEquals(AttackChainDetector.Verdict.WATCH, AttackChainDetector.chainOf(p.uuid()).verdict());
        assertTrue(AttackChainDetector.isWatched(p.uuid()));
        assertEquals(AttackChainDetector.WATCH_SEVERITY, AttackChainDetector.severityMultiplier(p.uuid()));
        AttackChainDetector.exploit(p, "speed", "0.9 bl/t");
        // Burned → banned (no server in tests) → chain reset for the next episode.
        AttackChainDetector.Chain chain = AttackChainDetector.chainOf(p.uuid());
        assertEquals(1, chain.burnedCount);
        assertEquals(0, chain.events.size());
        assertEquals(AttackChainDetector.Verdict.NONE, chain.verdict());
    }

    @Test
    void excludedChecksCountForWatchButNotForBan() {
        AttackChainDetector.forgetAllForTests();
        AttackChainDetector.configure(60, 2, 3, Set.of("bigmove", "timer"));
        Fake p = new Fake("laggy");
        AttackChainDetector.exploit(p, "bigmove", "effects");
        AttackChainDetector.exploit(p, "timer", "burst");
        AttackChainDetector.Chain chain = AttackChainDetector.chainOf(p.uuid());
        assertEquals(AttackChainDetector.Verdict.WATCH, chain.verdict(), "two distinct checks — watched");
        assertEquals(0, chain.eligibleKinds().size());
        AttackChainDetector.exploit(p, "reach", "3.4");
        chain = AttackChainDetector.chainOf(p.uuid());
        assertEquals(0, chain.burnedCount, "only one ban-eligible check — no ban");
        assertEquals(AttackChainDetector.Verdict.WATCH, chain.verdict());
        AttackChainDetector.exploit(p, "fly", "hover");
        AttackChainDetector.exploit(p, "speed", "fast");
        assertEquals(1, AttackChainDetector.chainOf(p.uuid()).burnedCount,
                "three eligible checks (reach, fly, speed) burn regardless of the excluded ones");
    }

    @Test
    void evasionAndReconNeverBanOnTheirOwn() {
        AttackChainDetector.forgetAllForTests();
        Fake p = new Fake("nervous");
        for (int i = 0; i < 5; i++) AttackChainDetector.evasion(p, "staff-toggle", "went quiet");
        for (int i = 0; i < 10; i++) AttackChainDetector.recon(p, "wall-track", "tracked");
        AttackChainDetector.Chain chain = AttackChainDetector.chainOf(p.uuid());
        assertEquals(0, chain.burnedCount);
        assertEquals(AttackChainDetector.Verdict.NONE, chain.verdict(), "context without any alert is nothing");
        // One alert plus context puts the player in front of a human — nothing more.
        AttackChainDetector.exploit(p, "reach", "3.4");
        chain = AttackChainDetector.chainOf(p.uuid());
        assertEquals(AttackChainDetector.Verdict.WATCH, chain.verdict());
        assertEquals(0, chain.burnedCount);
    }

    @Test
    void ourOwnKickIsNotEvasion() {
        // The disconnect hook only feeds "left-before-kick" when the player closed
        // the connection themselves; the detector cannot know that, so this test
        // documents the contract: an evasion event alone never changes a verdict.
        AttackChainDetector.forgetAllForTests();
        Fake p = new Fake("kicked");
        AttackChainDetector.exploit(p, "nuker", "22 blocks/s");
        AttackChainDetector.evasion(p, "left-before-kick", "closed while a kick was scheduled");
        assertEquals(0, AttackChainDetector.chainOf(p.uuid()).burnedCount);
    }

    @Test
    void banThresholdNeverBelowWatchThreshold() {
        AttackChainDetector.forgetAllForTests();
        AttackChainDetector.configure(60, 4, 2, Set.of());
        Fake p = new Fake("cfg");
        AttackChainDetector.exploit(p, "a", "");
        AttackChainDetector.exploit(p, "b", "");
        AttackChainDetector.exploit(p, "c", "");
        assertEquals(0, AttackChainDetector.chainOf(p.uuid()).burnedCount, "ban distinct clamps up to watch distinct (4)");
        AttackChainDetector.exploit(p, "d", "");
        assertEquals(1, AttackChainDetector.chainOf(p.uuid()).burnedCount);
    }
}
