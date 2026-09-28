package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowTelemetryTest {

    @Test
    void disabledBudgetNeverChangesScanFlowButRecordsPressure() {
        ShadowTelemetry.beginTick(100, 50_000_000L);

        assertTrue(ShadowTelemetry.reserveEnvironmentScan(2, false));
        assertTrue(ShadowTelemetry.reserveEnvironmentScan(2, false));
        assertTrue(ShadowTelemetry.reserveEnvironmentScan(2, false));

        assertEquals(1, ShadowTelemetry.skippedEnvironmentScansForTest());
        assertTrue(ShadowTelemetry.summary().contains("envBudgetSkipped=1"));
    }

    @Test
    void placementAttemptAndAuthoritativeOutcomesRemainSeparate() {
        ShadowTelemetry.beginTick(101, 0L);
        ShadowTelemetry.recordPlacementAttempt();
        ShadowTelemetry.recordPlacementAttempt();
        ShadowTelemetry.recordPlacementOutcome(true);
        ShadowTelemetry.recordPlacementOutcome(false);

        assertEquals(2, ShadowTelemetry.placementAttemptsForTest());
        assertEquals(1, ShadowTelemetry.placementAcceptedForTest());
        assertEquals(1, ShadowTelemetry.placementRejectedForTest());
    }

    @Test
    void tickBoundaryResetsPerTickPacketCounters() {
        ShadowTelemetry.beginTick(200, 0L);
        ShadowTelemetry.recordMovePacket(java.util.UUID.randomUUID());
        ShadowTelemetry.recordMovePacket(java.util.UUID.randomUUID());
        assertEquals(2, ShadowTelemetry.totalMovePacketsForTest());

        ShadowTelemetry.beginTick(201, 0L);
        assertEquals(0, ShadowTelemetry.totalMovePacketsForTest());
    }
}
