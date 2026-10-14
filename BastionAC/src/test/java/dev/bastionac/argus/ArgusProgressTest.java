package dev.bastionac.argus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live progress of one analysis, as the staff menu reads it (3.2). */
class ArgusProgressTest {

    @Test
    void stagesRecordTheirTimeAndTheBarOnlyMovesForward() {
        ArgusProgress p = new ArgusProgress("A", 1, 1_000);
        assertEquals(0.0, p.view().fraction(), "queued: nothing done yet");
        p.stage(ArgusProgress.Stage.WAITING_SERVER, 1_400);
        p.stage(ArgusProgress.Stage.READING, 3_000);
        p.step(1, 2);
        double reading = p.view().fraction();
        assertTrue(reading > 0 && reading < 1.0 / ArgusProgress.WORK_STAGES, "half of the first stage: " + reading);
        p.stage(ArgusProgress.Stage.MOVEMENT, 3_050);
        assertTrue(p.view().fraction() > reading);
        p.stage(ArgusProgress.Stage.SCORING, 3_200);
        p.stage(ArgusProgress.Stage.DONE, 3_260);

        ArgusProgress.View v = p.view();
        assertTrue(v.done());
        assertEquals(1.0, v.fraction());
        assertEquals(400L, v.took().get(ArgusProgress.Stage.QUEUED));
        assertEquals(1_600L, v.took().get(ArgusProgress.Stage.WAITING_SERVER));
        assertEquals(50L, v.took().get(ArgusProgress.Stage.READING));
        assertEquals(150L, v.took().get(ArgusProgress.Stage.MOVEMENT));
        assertEquals(60L, v.took().get(ArgusProgress.Stage.SCORING));
        assertFalse(v.took().containsKey(ArgusProgress.Stage.WORLD), "a stage that never ran has no time");
        assertEquals(260L, v.runMs(99_999), "the run is counted from the first working stage to the end");
    }

    @Test
    void nothingMovesAfterDone() {
        ArgusProgress p = new ArgusProgress("A", 1, 0);
        p.stage(ArgusProgress.Stage.READING, 10);
        p.stage(ArgusProgress.Stage.DONE, 20);
        p.stage(ArgusProgress.Stage.MOVEMENT, 30);
        assertEquals(ArgusProgress.Stage.DONE, p.view().stage());
        assertEquals(20L, p.view().finishedAt());
    }
}
