package dev.bastionac.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ACConfigTest {

    @Test
    void defaultsContainAllTunedChecks() {
        ACConfig cfg = ACConfig.defaults();
        assertTrue(cfg.check("speed").enabled);
        assertTrue(cfg.check("reach").alertVl > 0);
        assertTrue(cfg.check("bigmove").weight >= 5);
        // unknown check name falls back to a safe default instead of NPE
        assertTrue(cfg.check("nonexistent").enabled);
    }

    @Test
    void newTierOneChecksHaveDefaults() {
        ACConfig cfg = ACConfig.defaults();
        // All three new checks must be present, enabled and self-consistent.
        for (String name : new String[]{"velocity", "packetmine", "criticals"}) {
            ACConfig.CheckCfg c = cfg.check(name);
            assertTrue(c.enabled, name + " must be enabled by default");
            assertTrue(c.weight > 0, name + " weight");
            assertTrue(c.alertVl > 0, name + " alertVl");
            assertTrue(c.mitigateVl > 0, name + " mitigateVl");
            assertTrue(c.kickVl > c.alertVl, name + " kickVl must sit above alertVl");
        }
    }

    @Test
    void newTierTwoChecksHaveDefaults() {
        ACConfig cfg = ACConfig.defaults();
        for (String name : new String[]{"blink", "noslow", "scaffold"}) {
            ACConfig.CheckCfg c = cfg.check(name);
            assertTrue(c.enabled, name + " must be enabled by default");
            assertTrue(c.weight > 0, name + " weight");
            assertTrue(c.alertVl > 0, name + " alertVl");
            assertTrue(c.kickVl > c.alertVl, name + " kickVl must sit above alertVl");
        }
    }

    @Test
    void blinkAndNoSlowTuningIsClamped() {
        ACConfig cfg = ACConfig.defaults();
        cfg.general.blinkBurstOverdraw = 0;
        cfg.general.blinkMaxIatStdMs = -1;
        cfg.general.noSlowGraceTicks = 0;
        cfg.general.noSlowMaxFactor = 99;
        cfg.clamp();
        assertTrue(cfg.general.blinkBurstOverdraw >= 3 && cfg.general.blinkBurstOverdraw <= 40);
        assertTrue(cfg.general.blinkMaxIatStdMs >= 0.1 && cfg.general.blinkMaxIatStdMs <= 10.0);
        assertTrue(cfg.general.noSlowGraceTicks >= 2 && cfg.general.noSlowGraceTicks <= 20);
        assertTrue(cfg.general.noSlowMaxFactor >= 0.2 && cfg.general.noSlowMaxFactor <= 1.0);
    }

    @Test
    void antiKbTuningIsClamped() {
        ACConfig cfg = ACConfig.defaults();
        // Out-of-range values must be pulled back into the safe band.
        cfg.general.velocityVerifyTicks = 0;
        cfg.general.velocityMinRatio = 5.0;
        cfg.general.velocityConfirmStreak = -3;
        cfg.clamp();
        assertTrue(cfg.general.velocityVerifyTicks >= 2 && cfg.general.velocityVerifyTicks <= 10);
        assertTrue(cfg.general.velocityMinRatio >= 0.3 && cfg.general.velocityMinRatio <= 0.95);
        assertTrue(cfg.general.velocityConfirmStreak >= 1 && cfg.general.velocityConfirmStreak <= 10);
    }

    @Test
    void mitigationCanBeDisabledWithMinusOne() {
        ACConfig cfg = ACConfig.defaults();
        assertEquals(-1.0, cfg.check("groundspoof").mitigateVl, 1e-9);
        assertEquals(-1.0, cfg.check("multitarget").mitigateVl, 1e-9);
    }

    @Test
    void observeOnlyChecksNeverKick() {
        ACConfig cfg = ACConfig.defaults();
        // Checks that only observe (mitigateVl == -1) must also never auto-kick.
        // NoFall is not on this list since config v9: it became an outcome
        // audit (fall distance against damage actually taken) and was re-armed.
        for (String name : new String[]{"groundspoof", "multitarget", "noswing",
                "autoclick", "autoaction"}) {
            assertEquals(-1.0, cfg.check(name).mitigateVl, 1e-9, name);
            assertEquals(-1.0, cfg.check(name).kickVl, 1e-9, name + " kickVl");
        }
        assertTrue(cfg.check("nofall").mitigateVl > 0, "nofall is armed since config v9");
        assertTrue(cfg.general.kickEnabled);
    }

    @Test
    void kickMessagesHaveDefaults() {
        ACConfig cfg = ACConfig.defaults();
        assertTrue(cfg.messages.announceKickToEveryone);
        assertFalse(cfg.messages.revealCheckToPlayer);
        assertFalse(cfg.messages.kickBroadcast.isBlank());
    }

    @Test
    void shadowTelemetryIsSilentUntilConsoleReportingIsExplicitlyEnabled() {
        ACConfig cfg = ACConfig.defaults();
        assertTrue(cfg.general.shadowTelemetryEnabled,
                "local, observe-only telemetry should remain available for diagnostics");
        assertFalse(cfg.general.shadowConsoleReports,
                "production telemetry must not emit summaries or packet-pressure logs by default");
    }

    @Test
    void loadCreatesFileAndKeepsAdminOverrides(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        ACConfig first = ACConfig.loadOrCreate(file, w -> {});
        assertTrue(Files.exists(file));
        assertTrue(first.general.setbackEnabled);

        // Admin edits one value; reload keeps it and fills the rest.
        String edited = Files.readString(file, StandardCharsets.UTF_8)
                .replace("\"setbackEnabled\": true", "\"setbackEnabled\": false");
        Files.writeString(file, edited, StandardCharsets.UTF_8);
        ACConfig second = ACConfig.loadOrCreate(file, w -> {});
        assertFalse(second.general.setbackEnabled);
        assertTrue(second.check("speed").enabled);
    }

    /**
     * A config written before the 2.14 retune carries the thresholds that
     * auto-banned a legitimate player, and merging alone would keep them: an
     * existing key always wins. The schema bump has to re-apply the defaults for
     * exactly the retuned checks, and only those.
     */
    @Test
    void oldConfigIsMigratedButKeepsUnrelatedTuning(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, """
                {
                  "punishment": { "banExcludedChecks": ["bigmove", "timer"] },
                  "checks": {
                    "packetmine": { "enabled": true, "weight": 2.0, "alertVl": 2.0,
                                    "mitigateVl": 3.0, "kickVl": 10.0, "decayPerSecond": 0.5 },
                    "spider":     { "enabled": true, "weight": 6.0, "alertVl": 2.0,
                                    "mitigateVl": 4.0, "kickVl": 8.0, "decayPerSecond": 0.5 }
                  }
                }
                """, StandardCharsets.UTF_8);

        boolean[] warned = {false};
        ACConfig cfg = ACConfig.loadOrCreate(file, w -> warned[0] = true);

        assertTrue(warned[0], "the migration must announce itself in the log");
        assertEquals(ACConfig.CONFIG_VERSION, cfg.general.configVersion);
        // Retuned check: the old alert-on-the-first-flag threshold is gone.
        assertEquals(ACConfig.defaults().check("packetmine").alertVl, cfg.check("packetmine").alertVl);
        // Untouched check: the admin's own tuning survives.
        assertEquals(6.0, cfg.check("spider").weight);
        // v7: precise checks ban on their own ladder (no longer excluded); the
        // effect-sensitive and observe-only ones stay excluded.
        assertFalse(cfg.punishment.banExcludedChecks.contains("packetmine"));
        assertFalse(cfg.punishment.banExcludedChecks.contains("scaffold"));
        assertTrue(cfg.punishment.banExcludedChecks.contains("bigmove"));
        assertTrue(cfg.punishment.banExcludedChecks.contains("timer"));
        assertTrue(cfg.punishment.exemptOperators);
        // Observe-only heuristics never kick after the migration.
        assertEquals(-1.0, cfg.check("autoclick").kickVl);
        assertEquals(-1.0, cfg.check("noswing").kickVl);

        // Second load is a no-op: the migration must not fire twice and must not
        // undo tuning the admin applies AFTER it ran.
        String edited = Files.readString(file, StandardCharsets.UTF_8)
                .replace("\"packetmine\": {\n      \"enabled\": true", "\"packetmine\": {\n      \"enabled\": false");
        Files.writeString(file, edited, StandardCharsets.UTF_8);
        boolean[] warnedAgain = {false};
        ACConfig again = ACConfig.loadOrCreate(file, w -> warnedAgain[0] = true);
        assertFalse(warnedAgain[0], "migration must run exactly once");
        assertFalse(again.check("packetmine").enabled, "post-migration edits must stick");
    }

    @Test
    void brokenFileIsBackedUpAndDefaultsApply(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{ this is not json ", StandardCharsets.UTF_8);
        boolean[] warned = {false};
        ACConfig cfg = ACConfig.loadOrCreate(file, w -> warned[0] = true);
        assertTrue(warned[0]);
        assertTrue(cfg.general.alertsToOps);
        assertTrue(Files.list(dir).anyMatch(p -> p.getFileName().toString().contains("broken")));
    }

    @Test
    void timerIntervalStaysBelowVanillaTickRate() {
        // A vanilla client sends one move packet per 50ms tick. At or above 50
        // the Timer check would flag every legitimate player forever, so the
        // clamp must keep this strictly under it no matter what an admin types.
        ACConfig cfg = ACConfig.defaults();
        assertTrue(cfg.general.timerMinIntervalMs < 50,
                "timerMinIntervalMs must stay under vanilla's 50ms/packet");
        assertTrue(cfg.general.timerMinIntervalMs > 0);
    }

    @Test
    void timerIntervalIsRestoredAndClamped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        // 0 is what an older config (without the key) deserialises to.
        Files.writeString(file, "{\"general\":{\"timerMinIntervalMs\":0}}", StandardCharsets.UTF_8);
        assertEquals(40, ACConfig.loadOrCreate(file, w -> {}).general.timerMinIntervalMs);

        Files.writeString(file, "{\"general\":{\"timerMinIntervalMs\":9999}}", StandardCharsets.UTF_8);
        assertEquals(49, ACConfig.loadOrCreate(file, w -> {}).general.timerMinIntervalMs);
    }

    @Test
    void timerDecayCannotOutrunItsOwnFlagRate() {
        // The check restarts its 30-packet window on every flag, so at the
        // configured threshold it can flag at most once per (29 * interval) ms.
        // If decay per second exceeds that rate, VL can never reach alertVl and
        // the check is silently dead — which is exactly what shipped before.
        ACConfig cfg = ACConfig.defaults();
        double windowSeconds = (30 - 1) * cfg.general.timerMinIntervalMs / 1000.0;
        double maxVlPerSecond = cfg.check("timer").weight / windowSeconds;
        assertTrue(cfg.check("timer").decayPerSecond < maxVlPerSecond,
                "timer decay " + cfg.check("timer").decayPerSecond
                        + " must stay under its max flag rate " + maxVlPerSecond);
    }

    @Test
    void blatantClickerIsKickedWithinTwoSeconds() {
        // The whole point of the CPS ceiling: someone who switches on a 20 CPS
        // autoclicker must be gone almost immediately, not after a war of
        // attrition with the decay.
        ACConfig cfg = ACConfig.defaults();
        ACConfig.CheckCfg cps = cfg.check("cps");
        double seconds = dev.bastionac.core.MathUtil.cpsSecondsToReach(
                20, cfg.general.maxCps, cps.weight, cps.decayPerSecond, cps.kickVl);
        assertTrue(seconds <= 2.0, "20 CPS must reach kickVl within 2s, got " + seconds);

        double toAlert = dev.bastionac.core.MathUtil.cpsSecondsToReach(
                20, cfg.general.maxCps, cps.weight, cps.decayPerSecond, cps.alertVl);
        assertTrue(toAlert <= 1.0, "staff must be told inside a second, got " + toAlert);
    }

    @Test
    void borderlineClickerGetsTheBenefitOfTheDoubt() {
        // One click over the ceiling is the region where a butterfly clicker and
        // a badly tuned macro overlap. It must not kick on the spot.
        ACConfig cfg = ACConfig.defaults();
        ACConfig.CheckCfg cps = cfg.check("cps");
        double seconds = dev.bastionac.core.MathUtil.cpsSecondsToReach(
                cfg.general.maxCps + 1, cfg.general.maxCps, cps.weight, cps.decayPerSecond, cps.kickVl);
        assertTrue(seconds >= 8.0, "a borderline rate must take many seconds, got " + seconds);
    }

    @Test
    void maxCpsStaysInsideWhatVanillaCanSend(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        // A vanilla client sends at most one attack per 50ms tick, so a limit
        // above 20 would silently switch the check off rather than relax it.
        Files.writeString(file, "{\"general\":{\"maxCps\":99}}", StandardCharsets.UTF_8);
        assertEquals(20, ACConfig.loadOrCreate(file, w -> {}).general.maxCps);
        // 0 is what an older config (without the key) deserialises to.
        Files.writeString(file, "{\"general\":{\"maxCps\":0}}", StandardCharsets.UTF_8);
        assertEquals(14, ACConfig.loadOrCreate(file, w -> {}).general.maxCps);
        // And it can never be dragged down onto ordinary players.
        Files.writeString(file, "{\"general\":{\"maxCps\":3}}", StandardCharsets.UTF_8);
        assertEquals(10, ACConfig.loadOrCreate(file, w -> {}).general.maxCps);
    }

    @Test
    void insaneValuesAreClamped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"general\":{\"joinGraceTicks\":999999,\"alertCooldownSeconds\":-5}}",
                StandardCharsets.UTF_8);
        ACConfig cfg = ACConfig.loadOrCreate(file, w -> {});
        assertEquals(1200, cfg.general.joinGraceTicks);
        assertEquals(1, cfg.general.alertCooldownSeconds);
    }
}
