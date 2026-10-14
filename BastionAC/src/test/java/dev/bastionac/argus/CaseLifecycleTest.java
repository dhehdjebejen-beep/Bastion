package dev.bastionac.argus;

import dev.bastionac.config.ACConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The races between the Argus thread and staff decisions, as plain state transitions. */
class CaseLifecycleTest {

    private static final long MIN = 60_000L;
    private final ACConfig.ArgusCfg cfg = new ACConfig.ArgusCfg();

    private static CaseFile fresh(long now) {
        CaseFile c = new CaseFile();
        c.id = "A";
        c.uuid = "00000000-0000-0000-0000-000000000001";
        c.openedAt = now;
        c.dueAt = now + 2 * MIN;
        c.deadlineAt = now + 15 * MIN;
        return c;
    }

    @Test
    void neverTwoAnalysesAtOnce() {
        CaseFile c = fresh(0);
        int first = CaseLifecycle.begin(c);
        assertTrue(first > 0);
        assertEquals(-1, CaseLifecycle.begin(c), "a second job must not start beside the first");
        assertTrue(c.rerunRequested, "…but it must not be lost either");
        assertEquals(CaseLifecycle.Step.NONE, CaseLifecycle.next(c, 10 * MIN, true, cfg));
    }

    @Test
    void requestDuringAnalysisRunsRightAfter() {
        CaseFile c = fresh(0);
        int gen = CaseLifecycle.begin(c);
        assertFalse(CaseLifecycle.requestNow(c, MIN), "an admin's request while busy is deferred");
        assertEquals(CaseLifecycle.Outcome.LIVE, CaseLifecycle.arrive(c, gen));
        c.verdict = CaseFile.Verdict.SUSPECT;
        CaseLifecycle.settle(c, true, 2 * MIN, true);
        // Before: the request waited out reanalyzeMinutes (15) after the result.
        assertEquals(CaseLifecycle.Step.ANALYZE, CaseLifecycle.next(c, 2 * MIN, true, cfg));
    }

    @Test
    void staffDecisionDuringAnalysisIsNotUndone() {
        CaseFile c = fresh(0);
        int gen = CaseLifecycle.begin(c);
        c.label = CaseFile.Label.CHEATER;
        CaseLifecycle.closeByStaff(c, MIN);
        // The result that was already on its way.
        assertEquals(CaseLifecycle.Outcome.RECORD_ONLY, CaseLifecycle.arrive(c, gen));
        assertEquals(CaseFile.Status.CLOSED, c.status, "a closed case stays closed");
        assertFalse(c.inFlight);
        assertEquals(CaseLifecycle.Step.NONE, CaseLifecycle.next(c, 60 * MIN, true, cfg));
    }

    @Test
    void staleResultIsIgnored() {
        CaseFile c = fresh(0);
        int gen = CaseLifecycle.begin(c);
        assertEquals(CaseLifecycle.Outcome.STALE, CaseLifecycle.arrive(c, gen + 1));
        assertTrue(c.inFlight, "a foreign result must not release the real job");
        assertEquals(CaseLifecycle.Outcome.LIVE, CaseLifecycle.arrive(c, gen));
        assertEquals(CaseLifecycle.Outcome.STALE, CaseLifecycle.arrive(c, gen), "and only once");
    }

    @Test
    void guiltyOrOfflineIsFinalAtOnce() {
        CaseFile guilty = fresh(0);
        int g = CaseLifecycle.begin(guilty);
        CaseLifecycle.arrive(guilty, g);
        guilty.verdict = CaseFile.Verdict.GUILTY;
        CaseLifecycle.settle(guilty, true, 2 * MIN, true);
        assertTrue(guilty.finalPass, "guilty does not wait for the deadline");

        CaseFile left = fresh(0);
        int l = CaseLifecycle.begin(left);
        CaseLifecycle.arrive(left, l);
        left.verdict = CaseFile.Verdict.SUSPECT;
        CaseLifecycle.settle(left, false, 2 * MIN, true);
        assertTrue(left.finalPass, "no new telemetry will come from a player who left");
    }

    @Test
    void cleanCaseRunsToItsDeadlineThenCloses() {
        CaseFile c = fresh(0);
        assertEquals(CaseLifecycle.Step.NONE, CaseLifecycle.next(c, MIN, true, cfg));
        assertEquals(CaseLifecycle.Step.ANALYZE, CaseLifecycle.next(c, 2 * MIN, true, cfg));
        int gen = CaseLifecycle.begin(c);
        CaseLifecycle.arrive(c, gen);
        c.verdict = CaseFile.Verdict.CLEAN;
        CaseLifecycle.settle(c, true, 3 * MIN, true);
        assertFalse(c.finalPass);
        assertEquals(CaseLifecycle.Step.ANALYZE, CaseLifecycle.next(c, 15 * MIN, true, cfg), "deadline pass");
        assertTrue(c.finalPass);
        gen = CaseLifecycle.begin(c);
        CaseLifecycle.arrive(c, gen);
        CaseLifecycle.settle(c, true, 16 * MIN, true);
        assertEquals(CaseLifecycle.Step.NONE, CaseLifecycle.next(c, 30 * MIN, true, cfg));
        assertEquals(CaseLifecycle.Step.CLOSE, CaseLifecycle.next(c, 46 * MIN, true, cfg));
    }

    @Test
    void offlineAtDeadlineBecomesFinalWithoutAnotherRead() {
        CaseFile c = fresh(0);
        int gen = CaseLifecycle.begin(c);
        CaseLifecycle.arrive(c, gen);
        c.verdict = CaseFile.Verdict.SUSPECT;
        CaseLifecycle.settle(c, true, 3 * MIN, true);
        assertEquals(CaseLifecycle.Step.NONE, CaseLifecycle.next(c, 15 * MIN, false, cfg));
        assertTrue(c.finalPass);
    }

    @Test
    void lostJobIsPickedUpAgain() {
        CaseFile c = fresh(0);
        c.status = CaseFile.Status.QUEUED;   // e.g. a restart mid-analysis, inFlight not stored
        assertEquals(CaseLifecycle.Step.ANALYZE, CaseLifecycle.next(c, 3 * MIN, true, cfg));
    }

    @Test
    void thinGuiltyWaitsForTheDeadlinePass() {
        CaseFile c = fresh(0);
        int gen = CaseLifecycle.begin(c);
        CaseLifecycle.arrive(c, gen);
        c.verdict = CaseFile.Verdict.GUILTY;
        CaseLifecycle.settle(c, true, 2 * MIN, false);
        assertFalse(c.finalPass, "two minutes of data are not the last word");
        assertEquals(CaseLifecycle.Step.ANALYZE, CaseLifecycle.next(c, 15 * MIN, true, cfg), "read again at the deadline");

        CaseFile left = fresh(0);
        gen = CaseLifecycle.begin(left);
        CaseLifecycle.arrive(left, gen);
        left.verdict = CaseFile.Verdict.GUILTY;
        CaseLifecycle.settle(left, false, 2 * MIN, false);
        assertTrue(left.finalPass, "a player who left sends nothing more to wait for");
    }

    private static CaseFile analysedGuilty(long openedAt) {
        CaseFile c = fresh(openedAt);
        int gen = CaseLifecycle.begin(c);
        CaseLifecycle.arrive(c, gen);
        c.verdict = CaseFile.Verdict.GUILTY;
        c.lastSignalAt = openedAt + MIN;
        CaseLifecycle.settle(c, true, openedAt + 3 * MIN, true);
        return c;
    }

    @Test
    void aSignalHoursLaterIsANewEpisode() {
        CaseFile c = analysedGuilty(0);
        assertFalse(CaseLifecycle.newEpisode(c, 30 * MIN), "same evening: the same case");
        assertFalse(CaseLifecycle.newEpisode(c, 5 * 60 * MIN), "under the gap");
        long twoDays = 2 * 24 * 60 * MIN;
        assertTrue(CaseLifecycle.newEpisode(c, twoDays), "two days later: a new case, not the old one re-read");

        CaseLifecycle.closeAsEpisode(c, "B", twoDays);
        assertFalse(c.open());
        assertEquals(CaseFile.Verdict.GUILTY, c.verdict, "the old verdict is kept");
        assertTrue(c.awaitsDecision(), "…and still asks staff for a decision");
        assertEquals("новый эпизод — дело #B", c.closeNote);
    }

    @Test
    void busyOrUndecidedOrDecidedCasesAreNoNewEpisode() {
        long later = 3 * 24 * 60 * MIN;
        CaseFile busy = analysedGuilty(0);
        busy.inFlight = true;
        assertFalse(CaseLifecycle.newEpisode(busy, later));
        CaseFile collecting = fresh(0);
        assertFalse(CaseLifecycle.newEpisode(collecting, later), "never analysed: it is read first");
        CaseFile closed = analysedGuilty(0);
        closed.label = CaseFile.Label.CHEATER;
        CaseLifecycle.closeByStaff(closed, MIN);
        assertFalse(CaseLifecycle.newEpisode(closed, later));
        assertFalse(closed.awaitsDecision(), "staff decided it");
    }

    @Test
    void analysisReadsAtMostHalfADayBack() {
        CaseFile c = analysedGuilty(0);
        long hour = 60 * MIN;
        assertEquals(-hour, CaseLifecycle.readFrom(c, 2 * hour, hour), "a young case: an hour before it opened");
        long twoDays = 48 * hour;
        assertEquals(twoDays - 12 * hour, CaseLifecycle.readFrom(c, twoDays, hour),
                "an old case: never days of play at once");
        assertEquals(twoDays - 24 * hour, CaseLifecycle.readFrom(c, twoDays, 24 * hour), "a longer window is honoured");
    }

    @Test
    void sessionScanAfterAStaffDecisionIsDropped() {
        CaseFile decided = fresh(0);
        decided.action = CaseFile.Action.BANNED;
        CaseLifecycle.closeByStaff(decided, 10 * MIN);
        assertTrue(CaseLifecycle.recentlyDecided(List.of(decided), 12 * MIN));
        assertFalse(CaseLifecycle.recentlyDecided(List.of(decided), 80 * MIN), "a later session is new");

        CaseFile autoClosed = fresh(0);
        autoClosed.status = CaseFile.Status.CLOSED;
        autoClosed.closedAt = 10 * MIN;
        assertFalse(CaseLifecycle.recentlyDecided(List.of(autoClosed), 12 * MIN),
                "a clean case Argus closed by itself is no staff decision");
    }
}
