package dev.bastionac.argus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argus for players who left and for alerts from the past (3.3.2). */
class OfflineInvestigationTest {

    private static final long H = 3_600_000L;
    // Three sessions: 10:00-11:00, 14:00-14:30, 21:00-22:10 (ms on some day).
    private static final long[] STARTS = {10 * H, 14 * H, 21 * H};
    private static final long[] ENDS = {11 * H, 14 * H + H / 2, 22 * H + H / 6};

    @Test
    void theLatestSessionForAPlayerWhoLeft() {
        SessionLocator.Window w = SessionLocator.pick(STARTS, ENDS, 0);
        assertNotNull(w);
        assertEquals(21 * H - SessionLocator.MARGIN_MS, w.from());
        assertEquals(22 * H + H / 6 + SessionLocator.MARGIN_MS, w.to());
    }

    @Test
    void theSessionThatWasRunningAtTheAlert() {
        SessionLocator.Window w = SessionLocator.pick(STARTS, ENDS, 14 * H + 600_000L);
        assertEquals(14 * H - SessionLocator.MARGIN_MS, w.from(), "the 14:10 alert is in the 14:00 session");
        assertEquals(14 * H + H / 2 + SessionLocator.MARGIN_MS, w.to());
        // An alert stamped a few seconds before its file's name still finds it.
        assertEquals(21 * H - SessionLocator.MARGIN_MS, SessionLocator.pick(STARTS, ENDS, 21 * H - 5_000L).from());
    }

    @Test
    void beforeTheOldestFileIsGone() {
        assertNull(SessionLocator.pick(STARTS, ENDS, 9 * H), "older than the retention: nothing to read");
        assertNull(SessionLocator.pick(new long[0], new long[0], 0));
    }

    private static CaseFile closedWith(CaseFile.Verdict v, boolean alert) {
        CaseFile c = new CaseFile();
        c.id = "A";
        c.uuid = "00000000-0000-0000-0000-000000000001";
        c.verdict = v;
        if (alert) c.addSignal(new Signal(1, java.util.UUID.fromString(c.uuid), "x", Signal.Kind.ALERT, "fly VL 6.0: dy=0.08"));
        c.status = CaseFile.Status.CLOSED;
        c.closedAt = 2;
        return c;
    }

    @Test
    void thinDataAfterAlertsWaitsForAPerson() {
        assertTrue(closedWith(CaseFile.Verdict.INSUFFICIENT, true).awaitsDecision(),
                "alerts fired, too little data: staff must see it");
        assertFalse(closedWith(CaseFile.Verdict.INSUFFICIENT, false).awaitsDecision(), "no alerts, nothing to look at");
        assertFalse(closedWith(CaseFile.Verdict.CLEAN, true).awaitsDecision(), "cleared — told once, not a to-do");
        CaseFile dismissed = closedWith(CaseFile.Verdict.INSUFFICIENT, true);
        dismissed.action = CaseFile.Action.DISMISSED;
        assertFalse(dismissed.awaitsDecision());
    }

    @Test
    void theAlertsBehindACaseInOneLine() {
        CaseFile c = closedWith(CaseFile.Verdict.CLEAN, true);
        java.util.UUID u = java.util.UUID.fromString(c.uuid);
        c.addSignal(new Signal(2, u, "x", Signal.Kind.ALERT, "fly VL 7.5: dy=0.10"));
        c.addSignal(new Signal(3, u, "x", Signal.Kind.ALERT, "highjump VL 5.0: 1.6 бл"));
        c.addSignal(new Signal(4, u, "x", Signal.Kind.MANUAL, "запросил Admin"));
        assertEquals("fly ×2, highjump", Argus.alertChecks(c));
    }
}
