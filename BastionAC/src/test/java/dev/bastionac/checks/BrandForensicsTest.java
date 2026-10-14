package dev.bastionac.checks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BrandSpoof beyond the channel rule: what a spoofing client gets wrong about itself. */
class BrandForensicsTest {

    @Test
    void honestClientsSayNothing() {
        assertTrue(ClientChecks.brandFindings(List.of("vanilla"), "vanilla", Set.of()).isEmpty());
        assertTrue(ClientChecks.brandFindings(List.of("fabric"), "fabric", Set.of()).isEmpty());
        assertTrue(ClientChecks.brandFindings(List.of("vanilla", "vanilla"), "vanilla", Set.of()).isEmpty(),
                "the same brand twice (a reconfiguration) is not a change");
    }

    @Test
    void aBrandThatChangesMidSessionIsASpoof() {
        var f = ClientChecks.brandFindings(List.of("fabric", "vanilla"), "vanilla", Set.of());
        assertEquals(1, f.size());
        assertTrue(f.get(0).text().contains("«fabric» → «vanilla»"), f.get(0).text());
    }

    @Test
    void doctoredStrings() {
        assertTrue(ClientChecks.doctored("Vanilla"));
        assertTrue(ClientChecks.doctored("vanilla "));
        assertTrue(ClientChecks.doctored("§avanilla"));
        assertTrue(ClientChecks.doctored("van\u0000illa"));
        assertTrue(ClientChecks.doctored("x".repeat(65)));
        assertTrue(ClientChecks.doctored(""));
        assertFalse(ClientChecks.doctored("vanilla"));
        assertFalse(ClientChecks.doctored("fabric"));
        assertFalse(ClientChecks.doctored("lunarclient:1.2.3"));
    }

    @Test
    void missingBrandIsOnlyAHalfWeightHint() {
        var f = ClientChecks.brandFindings(List.of(), null, Set.of());
        assertEquals(1, f.size());
        assertEquals(0.5, f.get(0).weight(), 1e-9);
    }

    @Test
    void theOldChannelRuleStillStands() {
        assertFalse(ClientChecks.brandFindings(List.of("vanilla"), "vanilla", Set.of("fabric:registry/sync")).isEmpty());
    }

    @Test
    void tickEndAbsence() {
        assertFalse(ClientChecks.tickEndsMissing(50, false, -1), "too early to say");
        assertTrue(ClientChecks.tickEndsMissing(100, false, -1), "no Via: every client is a native 1.21.11");
        assertFalse(ClientChecks.tickEndsMissing(500, true, -1), "Via present, version unknown: no verdict");
        assertTrue(ClientChecks.tickEndsMissing(100, true, 47), "Via makes tick ends up even for 1.8");
    }
}
