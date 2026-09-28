package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VlTrackerTest {

    @Test
    void addAccumulates() {
        VlTracker vl = new VlTracker();
        assertEquals(1.0, vl.add(CheckType.SPEED, 1.0), 1e-9);
        assertEquals(3.5, vl.add(CheckType.SPEED, 2.5), 1e-9);
        assertEquals(0.0, vl.get(CheckType.FLY), 1e-9);
    }

    @Test
    void decayReachesZeroAndClears() {
        VlTracker vl = new VlTracker();
        vl.add(CheckType.REACH, 2.0);
        vl.decay(1.0, 1.0, CheckType.REACH);
        assertEquals(1.0, vl.get(CheckType.REACH), 1e-9);
        vl.decay(1.0, 5.0, CheckType.REACH);
        assertEquals(0.0, vl.get(CheckType.REACH), 1e-9);
        assertTrue(vl.snapshot().isEmpty());
    }

    @Test
    void alertCooldownEnforced() {
        VlTracker vl = new VlTracker();
        assertTrue(vl.tryAlert(CheckType.SPEED, 10_000, 5_000));
        assertFalse(vl.tryAlert(CheckType.SPEED, 12_000, 5_000));
        assertTrue(vl.tryAlert(CheckType.FLY, 12_000, 5_000)); // independent per check
        assertTrue(vl.tryAlert(CheckType.SPEED, 15_001, 5_000));
    }

    @Test
    void resetClearsSingleCheck() {
        VlTracker vl = new VlTracker();
        vl.add(CheckType.SPEED, 4);
        vl.add(CheckType.FLY, 2);
        vl.reset(CheckType.SPEED);
        assertEquals(0.0, vl.get(CheckType.SPEED), 1e-9);
        assertEquals(2.0, vl.get(CheckType.FLY), 1e-9);
    }
}
