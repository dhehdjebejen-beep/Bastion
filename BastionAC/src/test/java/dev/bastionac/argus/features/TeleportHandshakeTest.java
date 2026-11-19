package dev.bastionac.argus.features;

import dev.bastionac.telemetry.SessionData;
import org.junit.jupiter.api.Test;

import static dev.bastionac.telemetry.TelemetryFormat.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TeleportHandshakeTest {
    @Test void delayedConfirmedDestinationUsesTheRecordedPositionGrid() {
        var s = base();
        sent(s, 2000, 7, 10.00003, 64.00003, -20.00003);
        confirmed(s, 9000, 7, true);
        move(s, 11000, 0, 64, 0, false);
        move(s, 12000, 10, 64, -20, true);
        assertEquals(0, events(s));
    }

    @Test void confirmedDestinationCannotExcuseLaterJumpsOrBeReused() {
        var s = base();
        sent(s, 2000, 7, 10, 64, 20);
        confirmed(s, 9000, 7, true);
        move(s, 12000, 10, 64, 20, true);
        move(s, 20000, 15, 64, 20, true);
        confirmed(s, 20010, 7, true);
        move(s, 21000, 10, 64, 20, true);
        assertEquals(2, events(s));
    }

    @Test void missingInvalidOrWrongConfirmationCannotAuthorizeDestination() {
        for (int kind = 0; kind < 3; kind++) {
            var s = base();
            sent(s, 2000, 7, 10, 64, 20);
            if (kind == 1) confirmed(s, 9000, 7, false);
            if (kind == 2) confirmed(s, 9000, 8, true);
            move(s, 12000, 10, 64, 20, true);
            assertEquals(1, events(s), "confirmation variant " + kind);
        }
    }

    @Test void firstPositionMismatchConsumesTheAcknowledgedDestination() {
        var s = base();
        sent(s, 2000, 7, 10, 64, 20);
        confirmed(s, 9000, 7, true);
        move(s, 12000, 1, 64, 0, true);
        move(s, 13000, 10, 64, 20, true);
        assertEquals(1, events(s));
    }

    @Test void verticalMismatchCannotAuthorizeHorizontalJump() {
        var s = base();
        sent(s, 2000, 7, 10, 64, 20);
        confirmed(s, 9000, 7, true);
        move(s, 12000, 10, 65, 20, true);
        assertEquals(1, events(s));
    }

    @Test void aReplacementTeleportInvalidatesAnOlderDestinationAndConfirmation() {
        var s = base();
        sent(s, 2000, 7, 10, 64, 20);
        confirmed(s, 9000, 7, true);
        sent(s, 9500, 8, -10, 64, -20);
        move(s, 12000, 10, 64, 20, true);
        assertEquals(1, events(s));
    }

    @Test void confirmationBeforeItsSendCannotAuthorizeEvenAnIdenticalId() {
        var s = base();
        confirmed(s, 1500, 7, true);
        sent(s, 2000, 7, 10, 64, 20);
        move(s, 12000, 10, 64, 20, true);
        assertEquals(1, events(s));
    }

    @Test void positionBeforeConfirmationConsumesThePendingDestination() {
        for (boolean targetFirst : new boolean[]{false, true}) {
            var s = base();
            sent(s, 2000, 7, 10, 64, 20);
            move(s, 8000, targetFirst ? 10 : 1, 64, targetFirst ? 20 : 0, true);
            confirmed(s, 9000, 7, true);
            move(s, 10000, targetFirst ? 15 : 10, 64, 20, true);
            assertEquals(targetFirst ? 2 : 1, events(s));
        }
    }

    @Test void aLaterSetbackNeverExcusesTheJumpThatCausedIt() {
        var s = base();
        move(s, 12000, 10, 64, 20, true);
        sent(s, 12025, 7, 0, 64, 0);
        confirmed(s, 20000, 7, true);
        move(s, 21000, 0, 64, 0, true);
        assertEquals(1, events(s));
    }

    private static SessionData base() {
        var s = new SessionData();
        move(s, 1000, 0, 64, 0, true);
        return s;
    }

    private static void sent(SessionData s, long time, int id, double x, double y, double z) {
        s.tpSentT.add(time);
        s.tpSentId.add(id);
        s.tpX.add(x);
        s.tpY.add(y);
        s.tpZ.add(z);
    }

    private static void confirmed(SessionData s, long time, int id, boolean valid) {
        s.tpConfT.add(time);
        s.tpConfId.add(id);
        s.tpConfValid.add(valid ? 1 : 0);
    }

    private static void move(SessionData s, long time, double x, double y, double z, boolean position) {
        s.moveT.add(time);
        s.moveFlags.add(position ? MV_POS | MV_GROUND : MV_GROUND);
        s.moveX.add(x);
        s.moveY.add(y);
        s.moveZ.add(z);
        s.moveEnv.add(0);
        s.moveSpeed.add(.1f);
        s.firstT = Math.min(s.firstT, time);
        s.lastT = Math.max(s.lastT, time);
    }

    private static int events(SessionData s) {
        var fs = new FeatureSet();
        MovementFeatures.teleports(s, fs);
        return fs.acc("move.teleport").momentTotal;
    }
}
