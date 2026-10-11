package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnockbackVerifierTest {

    private static final double SPRINT_ACCEL = 0.13 * 1.1;

    /** Vanilla horizontal knockback travel with the given input pushing against it each tick. */
    private static double vanilla(double speed, boolean groundBefore, boolean[] grounded, double against) {
        double u = speed, disp = 0;
        boolean prev = groundBefore;
        for (boolean g : grounded) {
            u -= against * (prev ? 1.0 : 0.2);
            disp += u;
            u *= prev ? 0.546 : 0.91;
            prev = g;
        }
        return disp;
    }

    private static double need(double min) {
        return min * 0.9 - 0.03;
    }

    @Test
    void honestHitFromTheGroundPasses() {
        boolean[] air = {false, false, false, false};
        double[] dys = {0.36, 0.28, 0.20, 0.12};
        double min = KnockbackVerifier.minDisplacement(0.4, 0.36, true, air, dys, SPRINT_ACCEL, true);
        assertTrue(min >= KnockbackVerifier.MIN_JUDGED, "base hit is judged: " + min);
        assertTrue(vanilla(0.4, true, air, 0.0) >= need(min), "no input");
        assertTrue(vanilla(0.4, true, air, 0.13) >= need(min), "sprinting straight into the hit");
    }

    @Test
    void antiKbFails() {
        boolean[] air = {false, false, false, false};
        double[] dys = {0.36, 0.28, 0.20, 0.12};
        double min = KnockbackVerifier.minDisplacement(0.4, 0.36, true, air, dys, SPRINT_ACCEL, true);
        assertTrue(0.0 < need(min), "no travel at all");
        assertTrue(0.25 * vanilla(0.4, true, air, 0.0) < need(min), "a quarter of the knockback");
    }

    @Test
    void stayingOnTheFloorCannotTalkTheReplayDown() {
        // Anti-KB that also cancels the lift: the client claims the ground
        // every tick. Vanilla physics says it is airborne, so ground friction
        // and ground input are not granted, and no travel at all still fails.
        boolean[] grounded = {true, true, true, true};
        double[] dys = {0, 0, 0, 0};
        double min = KnockbackVerifier.minDisplacement(0.4, 0.36, true, grounded, dys, SPRINT_ACCEL, true);
        assertTrue(min >= KnockbackVerifier.MIN_JUDGED, "judged: " + min);
        assertTrue(0.0 < need(min));
    }

    @Test
    void landingInsideTheWindowIsNotConvicted() {
        // Hit while falling at the end of a jump: lands on the second tick and
        // skids on the ground — the case the old 0.98-drag model convicted.
        boolean[] g = {false, true, true, true};
        double[] dys = {-0.3, -0.1, 0, 0};
        double min = KnockbackVerifier.minDisplacement(0.4, 0.0, false, g, dys, SPRINT_ACCEL, true);
        assertTrue(vanilla(0.4, false, g, 0.0) >= need(min));
        assertTrue(vanilla(0.4, false, g, 0.13) >= need(min) || min < KnockbackVerifier.MIN_JUDGED);
    }

    @Test
    void jumpResetIsAllowed() {
        // On the ground, jump pressed as the hit lands: vy 0.42 instead of 0.36,
        // and the sprint-jump boost of 0.2 goes straight into the attacker.
        boolean[] air = {false, false, false, false};
        double[] dys = {0.42, 0.33, 0.25, 0.16};
        double min = KnockbackVerifier.minDisplacement(0.4, 0.36, true, air, dys, SPRINT_ACCEL, true);
        double honest = 0.4 - SPRINT_ACCEL - 0.2;
        double u = honest * 0.546, disp = honest;
        for (int k = 1; k < 4; k++) {
            u -= 0.026;
            disp += u;
            u *= 0.91;
        }
        assertTrue(disp >= need(min), "jump reset " + disp + " vs " + need(min));
    }
}
