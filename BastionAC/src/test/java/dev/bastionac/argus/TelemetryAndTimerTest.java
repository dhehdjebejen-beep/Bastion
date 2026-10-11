package dev.bastionac.argus;

import dev.bastionac.core.ArrivalClock;
import dev.bastionac.core.PlayerData;
import dev.bastionac.core.TimerBalance;
import dev.bastionac.telemetry.SessionData;
import dev.bastionac.telemetry.SessionWriter;
import dev.bastionac.telemetry.TelemetryReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dev.bastionac.telemetry.TelemetryFormat.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session format round-trips exactly; the timer balance and the arrival clock behave. */
class TelemetryAndTimerTest {

    @Test
    void sessionRoundTrip() throws Exception {
        SessionWriter w = new SessionWriter("u-1", "Tester", 5_000, "f", 1L << 40);
        w.header("{\"uuid\":\"u-1\",\"name\":\"Tester\",\"start\":5000}");
        w.env(5_000, ENV_LIQUID);
        w.ground(5_000, 77);
        w.speed(5_000, 0.13f);
        w.input(5_010, IN_FORWARD | IN_JUMP);
        w.move(5_050, true, 100.25, 64.0, -2000.123456, true, -715.6f, 12.5f, true, false);
        w.move(5_100, false, 0, 0, 0, true, -710.0f, 13f, false, true);
        w.move(5_150, true, 100.5, 64.42, -2000.0, false, 0, 0, false, false);
        w.tickEnd(5_151);
        w.teleportSent(5_200, 7, 1.5, 70, 2.5, 0);
        w.teleportConfirm(5_260, 7, true);
        w.flag(5_300, "speed", 6.5, "средняя 0,9");
        w.broken(5_400, 123456789L, 99, 0);
        SessionWriter.Attack a = new SessionWriter.Attack();
        a.targetId = 42;
        a.aimError = 3.5f;
        a.distance = 2.75f;
        a.flags = SessionWriter.Attack.F_PLAYER;
        w.attack(5_500, a);
        byte[] ctx = new byte[125];
        ctx[62] = (byte) CTX_DIAMOND;
        ctx[0] = (byte) CTX_LIQUID;
        w.oreContext(5_600, 5L, ctx);
        w.pong(5_700, 33);
        w.keepAlive(5_800, 41);
        w.client(5_900, "fabric", 774, "ru_ru", 12, 1, 127, 0, List.of("fabric:registry/sync"));

        SessionData d = TelemetryReader.readRaw(w.drain());
        assertEquals("u-1", d.uuid);
        assertEquals(3, d.moveT.n);
        assertEquals(100.25, d.moveX.a[0], 1.0 / 4096);
        assertEquals(-2000.123456, d.moveZ.a[0], 1.0 / 4096);
        assertEquals(-715.6f, d.moveYaw.a[0], 0f, "raw unwrapped yaw is kept exactly");
        assertEquals(100.25, d.moveX.a[1], 1.0 / 4096, "a look-only packet keeps the previous position");
        assertTrue(Float.isNaN(d.moveYaw.a[2]));
        assertEquals(64.42, d.moveY.a[2], 1.0 / 4096);
        assertEquals(IN_FORWARD | IN_JUMP, d.moveInput.a[0]);
        assertEquals(ENV_LIQUID, d.moveEnv.a[0]);
        assertEquals(77, d.moveGround.a[0]);
        assertEquals(0.13f, d.moveSpeed.a[0], 0f);
        assertTrue((d.moveFlags.a[1] & MV_HCOLL) != 0);
        assertEquals(0, d.moveTick.a[2], "the tick end came after the third move");
        assertEquals(1, d.tickEndT.n);
        assertEquals(7, d.tpSentId.a[0]);
        assertEquals(1, d.tpConfValid.a[0]);
        assertEquals("speed", d.flagCheck.get(0));
        assertEquals(123456789L, d.brokenPos.a[0]);
        assertEquals(42, d.attacks.get(0).targetId);
        assertEquals(3.5f, d.attacks.get(0).aimError, 0f);
        assertEquals(CTX_DIAMOND, d.oreContexts.get(0).at(0, 0, 0));
        assertEquals(CTX_LIQUID, d.oreContexts.get(0).at(-2, -2, -2));
        assertEquals(33, d.pongRtt.a[0]);
        assertEquals(41, d.keepAliveRtt.a[0]);
        assertEquals("fabric", d.brand);
        assertEquals(774, d.protocol);
        assertTrue(d.hasInput);
        assertEquals(5_900, d.lastT);
    }

    @Test
    void truncatedStreamIsReadUpToTheCut() throws Exception {
        SessionWriter w = new SessionWriter("u", "T", 0, "f", 1L << 40);
        w.header("{\"uuid\":\"u\",\"name\":\"T\",\"start\":0}");
        for (int i = 0; i < 50; i++) w.move(i * 50L, true, i * 0.2, 64, 0, false, 0, 0, true, false);
        byte[] all = w.drain();
        SessionData d = TelemetryReader.readRaw(java.util.Arrays.copyOf(all, all.length - 3));
        assertTrue(d.truncated);
        assertEquals(49, d.moveT.n);
    }

    @Test
    void lagSpikeNetsOutToZero() {
        TimerBalance t = new TimerBalance();
        long now = 0;
        double worst = 0;
        for (int i = 0; i < 200; i++) worst = Math.min(worst, t.onTick(now += 50, 3000));
        now += 1900;   // the connection froze for 1.9 s (a live case)
        for (int i = 0; i < 38; i++) worst = Math.min(worst, t.onTick(now, 3000));
        for (int i = 0; i < 100; i++) worst = Math.min(worst, t.onTick(now += 50, 3000));
        assertTrue(worst > -100, "a stall and its burst must net out, worst " + worst);
    }

    @Test
    void slowTimerIsCaught() {
        TimerBalance t = new TimerBalance();
        long now = 0;
        int flagsAt = -1;
        for (int i = 0; i < 2000; i++) {
            now += 45;   // ×1.11
            if (t.onTick(now, 3000) < -400) {
                flagsAt = i;
                break;
            }
        }
        assertTrue(flagsAt > 0 && flagsAt < 120, "x1.1 timer flagged within ~6 s, at tick " + flagsAt);
    }

    @Test
    void idleBankIsCapped() {
        TimerBalance t = new TimerBalance();
        t.onTick(0, 3000);
        t.onTick(60_000, 3000);   // a minute of nothing
        long now = 60_000;
        int burst = 0;
        while (t.onTick(now, 3000) >= -400) burst++;
        assertTrue(burst <= 70, "the bank buys at most ~3 s of extra ticks, got " + burst);
    }

    @Test
    void arrivalClockMatchesByIdentity() {
        ArrivalClock c = new ArrivalClock();
        Object a = new Object(), b = new Object(), lost = new Object(), cc = new Object();
        c.stamp(a, 10);
        c.stamp(lost, 11);   // cancelled before the server thread saw it
        c.stamp(b, 12);
        c.stamp(cc, 13);
        assertEquals(10, c.arrivalOf(a, 99));
        assertEquals(12, c.arrivalOf(b, 99));
        assertEquals(13, c.arrivalOf(cc, 99));
        assertEquals(99, c.arrivalOf(new Object(), 99), "never stamped: the fallback");
    }

    @Test
    void teleportConfirmsMustAnswerSentIds() {
        PlayerData d = new PlayerData(UUID.randomUUID(), "p");
        d.noteTeleportSent(5);
        d.noteTeleportSent(6);
        assertFalse(d.consumeTeleportConfirm(9), "a made-up confirm buys nothing");
        assertTrue(d.consumeTeleportConfirm(5));
        assertTrue(d.consumeTeleportConfirm(6));
        assertFalse(d.consumeTeleportConfirm(6), "each id answers once");
        assertEquals(0, d.outstandingTeleports());
    }
}
