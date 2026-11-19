package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;
import dev.bastionac.argus.features.PacketFeatures;
import dev.bastionac.telemetry.SessionData;
import dev.bastionac.telemetry.SessionWriter;
import dev.bastionac.telemetry.TelemetryReader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketTimingFeatureTest {
    /** The client keeps ticking while the ordered connection queues its packets. */
    static SessionData queued(double factor, int period, int hold, int phase) throws Exception {
        return queued(factor, period, hold, phase, 600_000);
    }

    private static SessionData queued(double factor, int period, int hold, int phase, long duration) throws Exception {
        var writer = new SessionWriter("synthetic", "Timing", 0, "f", 1L << 40);
        writer.header("{\"uuid\":\"synthetic\",\"name\":\"Timing\",\"start\":0}");
        writer.client(0, "vanilla", 774, "en_us", 12, 1, 127, 0, List.of());
        for (int tick = 0; tick <= duration * factor / 50; tick++) {
            long sent = Math.round(tick * 50 / factor);
            long position = Math.floorMod(sent - phase, period);
            long arrived = position < hold ? sent + hold - position : sent;
            writer.tickEnd(arrived);
        }
        return TelemetryReader.readRaw(writer.drain());
    }

    private static FeatureSet measure(SessionData session) {
        var result = new FeatureSet();
        PacketFeatures.analyze(session, result);
        return result;
    }

    @Test void honestQueuesKeepTheirMissingWallTimeAtDifferentWindowPhases() throws Exception {
        for (int[] lag : new int[][]{{13_000, 2_000}, {17_000, 4_000}, {37_000, 12_000},
                {21_000, 1_000}, {21_000, 2_000}}) {
            for (int phase : new int[]{0, 3500, 9800}) {
                var measured = measure(queued(1, lag[0], lag[1], phase));
                var rate = measured.value(Features.spec("pkt.tick_rate"));
                assertNotNull(rate);
                assertTrue(rate.support() >= 40, "the pauses must not discard most of the session");
                assertTrue(rate.value() <= 20.02, "period=" + lag[0] + ", phase=" + phase);
                assertTrue(rate.value() >= 20 * 600_000.0 / (600_000 + lag[1]) - 0.02,
                        "only the unfinished final queue may reduce the observed rate");
                assertTrue(Scorer.score(measured, new Baselines(), "synthetic", new Calibration(), 0).score() < 0.6);
            }
        }
    }

    @Test void initialThirtySecondQueueAndCroppedWindowsRetainTheKnownPrefix() throws Exception {
        for (int spacing : new int[]{0, 1, 5}) {
            var writer = new SessionWriter("synthetic", "Timing", 0, "f", 1L << 40);
            writer.header("{\"start\":0}");
            writer.client(0, "vanilla", 774, "en_us", 12, 1, 127, 0, List.of());
            long previous = -1;
            for (long sent = 0; sent <= 600_000; sent += 50) {
                long phase = sent % 61_000;
                long release = phase < 30_000 ? sent + 30_000 - phase : sent;
                long arrived = Math.max(release, previous + spacing);
                writer.tickEnd(arrived);
                previous = arrived;
            }
            byte[] bytes = writer.drain();
            for (long from : new long[]{0, 25_000, 30_000, 30_001, 30_020}) {
                var session = TelemetryReader.readRaw(bytes, from, 600_000);
                assertTrue(session.clockHistoryAvailable);
                assertEquals(12_001, session.clockTicks, "clipping must not erase the queued prefix");
                assertEquals(600_000, session.clockToMs);
                assertEquals(20.0, measure(session).value(Features.spec("pkt.tick_rate")).value(), 1e-9);
                assertTrue(session.tickEndT.a[0] >= from, "ordinary event arrays stay inside the requested window");
            }
        }
    }

    @Test void recordsAfterTheWindowCannotLendTicksOrTimeToTheHistory() throws Exception {
        var writer = new SessionWriter("synthetic", "Timing", 0, "f", 1L << 40);
        writer.header("{\"start\":0}");
        for (long t = 0; t <= 120_000; t += 50) writer.tickEnd(t);
        for (long t = 120_001; t <= 130_000; t++) writer.tickEnd(t);
        writer.note(900_000, "future marker");
        var session = TelemetryReader.readRaw(writer.drain(), 60_000, 120_000);
        assertEquals(1201, session.tickEndT.n);
        assertEquals(2401, session.clockTicks);
        assertEquals(120_000, session.clockToMs);
        assertEquals(120_000, session.lastT);
        assertEquals(20.0, measure(session).value(Features.spec("pkt.tick_rate")).value(), 1e-9);
    }

    @Test void missingOrInvalidClockOriginDoesNotInventPrePacketWaitingTime() throws Exception {
        for (String header : new String[]{"{}", "{broken", "{\"start\":0.5}", "{\"start\":\"0\"}"}) {
            var writer = new SessionWriter("synthetic", "Timing", 0, "f", 1L << 40);
            writer.header(header);
            for (long t = 0; t <= 120_000; t += 40) writer.tickEnd(t);
            var session = TelemetryReader.readRaw(writer.drain());
            assertFalse(session.clockHistoryAvailable, header);
            assertNull(measure(session).value(Features.spec("pkt.tick_rate")), header);
        }
    }

    @Test void anOldNumericHeaderCannotManufactureUnboundedSupport() throws Exception {
        var writer = new SessionWriter("synthetic", "Timing", 0, "f", 1L << 40);
        writer.header("{\"start\":0}");
        for (long i = 0; i <= 400; i++) writer.tickEnd(1_800_000_000_000L + i * 50);
        var session = TelemetryReader.readRaw(writer.drain());
        var rate = measure(session).value(Features.spec("pkt.tick_rate"));
        assertNotNull(rate);
        assertEquals(2, rate.support(), "401 tick markers cannot provide millions of ten-second samples");
        assertTrue(rate.value() < 20);
    }

    @Test void realTimerSurvivesTheSameQueuesAtAllThreeLabStrengths() throws Exception {
        for (double level : new double[]{0.25, 0.5, 1}) {
            double factor = 1 + 0.25 * level;
            for (int[] lag : new int[][]{{13_000, 2_000}, {17_000, 4_000}, {37_000, 12_000}}) {
                for (int phase : new int[]{0, 3500, 9800}) {
                    // Finish at a release while the client still ticks. Stopping
                    // generation inside the final queue intentionally lowers the
                    // observed average; it is not a complete sustained Timer trace.
                    long end = phase + ((600_000L - phase + lag[0] - 1) / lag[0]) * lag[0] + lag[1];
                    var measured = measure(queued(factor, lag[0], lag[1], phase, end));
                    assertEquals(20 * factor, measured.value(Features.spec("pkt.tick_rate")).value(), 0.02);
                    assertTrue(Scorer.score(measured, new Baselines(), "synthetic", new Calibration(), 0).score() >= 0.6,
                            "Timer strength=" + level + ", period=" + lag[0] + ", phase=" + phase);
                }
            }
        }
    }
}
