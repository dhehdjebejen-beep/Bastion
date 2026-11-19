package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;
import dev.bastionac.argus.features.VehicleFeatures;
import dev.bastionac.telemetry.SessionData;
import dev.bastionac.telemetry.TelemetryReader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.bastionac.telemetry.TelemetryFormat.ST_VEHICLE;
import static org.junit.jupiter.api.Assertions.*;

class VehicleFeaturesTest {
    private static SessionData trace(LabSimulator.BoatRoute route, double level) throws Exception {
        var p = ArgusLab.honest(317);
        p.jitterMs = 0;
        p.lagOneIn = Integer.MAX_VALUE;
        var simulator = new LabSimulator(p, Map.of(), null);
        simulator.boat(route, 1800, level);
        return TelemetryReader.readRaw(simulator.w.drain());
    }

    private static FeatureSet measure(SessionData s) {
        var fs = new FeatureSet();
        VehicleFeatures.analyze(s, fs, LabSimulator.VEHICLE_CONTEXT);
        return fs;
    }

    @Test void honestWaterIceShoreAndLandHaveNoVehicleEvents() throws Exception {
        for (var route : new LabSimulator.BoatRoute[]{LabSimulator.BoatRoute.WATER, LabSimulator.BoatRoute.ICE,
                LabSimulator.BoatRoute.SHORE, LabSimulator.BoatRoute.LAND}) {
            var diagnostics = VehicleFeatures.inspect(trace(route, 0), LabSimulator.VEHICLE_CONTEXT);
            assertTrue(diagnostics.episodes().isEmpty(), route + ": " + diagnostics.episodes());
            assertTrue(diagnostics.secondsByType().get("minecraft:oak_boat") > 80);
        }
    }

    @Test void landSpeedAndFlyShowAtAllThreeIntensities() throws Exception {
        for (double level : new double[]{0.25, 0.5, 1}) {
            var speed = measure(trace(LabSimulator.BoatRoute.LAND, level));
            assertTrue(speed.value(Features.spec("veh.land_speed")).value() > 0.9, "strength=" + level);
            var flight = measure(trace(LabSimulator.BoatRoute.FLY, level));
            assertTrue(flight.value(Features.spec("veh.rise")).value() > 0.15);
            assertTrue(flight.value(Features.spec("veh.hover")).value() > 0.4);
        }
    }

    @Test void airAtFeetAloneNeverProvesHover() throws Exception {
        var s = trace(LabSimulator.BoatRoute.LAND, 0);
        s.states.forEach(v -> v.blockAtFeet = 0); // hull may sit on a slab or a shore edge
        assertFalse(measure(s).has("veh.hover"));
    }

    @Test void vanillaLandSpeedAndRearSeatRotationAreNotAcceleration() throws Exception {
        var s = trace(LabSimulator.BoatRoute.LAND, 0);
        var last = s.states.getLast();
        var previous = s.states.get(s.states.size() - 2);
        assertEquals(2.0, (last.x - previous.x) * 1000 / (last.t - previous.t), 1e-5);
        // STATE stores the rider: turning a rear seat through 180 degrees
        // can add 1.2 blocks to the hull's one-second displacement.
        for (int i = 0; i < s.states.size(); i++) s.states.get(i).x += i % 2 == 0 ? -0.6 : 0.6;
        assertTrue(VehicleFeatures.inspect(s, LabSimulator.VEHICLE_CONTEXT).episodes().isEmpty());
    }

    @Test void mountsTeleportsAndServerPushesBreakTheWindow() throws Exception {
        var s = trace(LabSimulator.BoatRoute.LAND, 1);
        for (var state : s.states) s.velT.add(state.t - 1);
        assertTrue(VehicleFeatures.inspect(s, LabSimulator.VEHICLE_CONTEXT).episodes().isEmpty());
        s = trace(LabSimulator.BoatRoute.LAND, 1);
        for (var state : s.states) s.tpSentT.add(state.t - 1);
        assertTrue(VehicleFeatures.inspect(s, LabSimulator.VEHICLE_CONTEXT).episodes().isEmpty());
        s = trace(LabSimulator.BoatRoute.LAND, 1);
        for (int i = 0; i < s.states.size(); i += 2) s.states.get(i).flags &= ~ST_VEHICLE;
        assertTrue(VehicleFeatures.inspect(s, LabSimulator.VEHICLE_CONTEXT).episodes().isEmpty());
    }

    @Test void horsesMinecartsUnknownAndModdedWorldsAreNotBoatEvidence() throws Exception {
        for (String name : new String[]{"minecraft:horse", "minecraft:minecart", "mod:oak_boat", "unknown"}) {
            VehicleFeatures.Context context = new VehicleFeatures.Context() {
                public String vehicle(int type) { return name; }
                public VehicleFeatures.Surface surface(int raw) { return LabSimulator.VEHICLE_CONTEXT.surface(raw); }
            };
            assertTrue(VehicleFeatures.inspect(trace(LabSimulator.BoatRoute.FLY, 1), context).episodes().isEmpty());
        }
        var s = trace(LabSimulator.BoatRoute.LAND, 1);
        s.states.forEach(v -> v.world = 3);
        assertTrue(VehicleFeatures.inspect(s, LabSimulator.VEHICLE_CONTEXT).episodes().isEmpty());
    }
}
