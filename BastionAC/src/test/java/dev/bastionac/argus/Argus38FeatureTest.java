package dev.bastionac.argus;

import dev.bastionac.argus.features.*;
import dev.bastionac.telemetry.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.bastionac.telemetry.TelemetryFormat.*;
import static org.junit.jupiter.api.Assertions.*;

class Argus38FeatureTest {
    private static double count(FeatureSet f,String key) {return f.raw().get(key).num;}
    private static FeatureSet movement(SessionData s) {
        FeatureSet f=new FeatureSet();MovementFeatures.analyze(s,f,raw->.6f);return f;
    }
    @Test void airborneGroundFlagWithFloorContactIsNotFreeFall() throws Exception {
        PhysicsReplayTest.Sim sim=new PhysicsReplayTest.Sim();
        for(int i=0;i<100;i++)sim.tick(false,false);
        SessionData s=sim.read();
        for(int i=0;i<s.moveT.n;i++) {s.moveFlags.a[i]&=~MV_GROUND;s.moveEnv.a[i]|=ENV_SUPPORT|ENV_FLOOR_FACE;}
        assertEquals(0,count(movement(s),"move.rise"));
        for(int i=0;i<s.moveT.n;i++)s.moveEnv.a[i]&=~(ENV_SUPPORT|ENV_FLOOR_FACE);
        assertTrue(count(movement(s),"move.rise")>0,"unexplained hover must remain visible");
    }
    @Test void wallAscentIsVisibleButLaddersShoreAndVanillaJumpsAreNot() throws Exception {
        LabSimulator sim=new LabSimulator(ArgusLab.honest(909),Map.of(),null);
        sim.wallAscent(1200,.25);
        SessionData s=TelemetryReader.readRaw(sim.w.drain());
        assertTrue(count(movement(s),"move.wall_climb")>0);
        for(int i=0;i<s.moveT.n;i++)s.moveEnv.a[i]|=ENV_CLIMBABLE;
        assertEquals(0,count(movement(s),"move.wall_climb"));
        PhysicsReplayTest.Sim honest=new PhysicsReplayTest.Sim();
        for(int i=0;i<1000;i++)honest.tick(true,i%40==0);
        s=honest.read();
        for(int i=0;i<s.moveT.n;i++)s.moveFlags.a[i]|=MV_HCOLL;
        assertEquals(0,count(movement(s),"move.wall_climb"));
        s.moveY.a[0]=64;
        for(int i=1;i<s.moveT.n;i++)s.moveY.a[i]=64+Math.min(1,(i%20)*.2);
        assertEquals(0,count(movement(s),"move.wall_climb"));
    }
    @Test void hiddenDistantAbortedGridIsDifferentFromOpenFastMiningAndReachableCancellation() throws Exception {
        var profile=ArgusLab.honest(817);profile.pingMs=20;
        LabSimulator sim=new LabSimulator(profile,Map.of(),null);
        sim.packetMine(1200,.25,false);
        SessionData s=TelemetryReader.readRaw(sim.w.drain());
        FeatureSet f=new FeatureSet();MiningFeatures.analyze(s,f,null,null);
        assertTrue(count(f,"mine.hidden_probe")>100);
        for(int i=0;i<s.actionT.n;i++)s.actionExposed.a[i]=1;
        f=new FeatureSet();MiningFeatures.analyze(s,f,null,null);
        assertEquals(0,count(f,"mine.hidden_probe"));
        for(int i=0;i<s.actionT.n;i++) {s.actionExposed.a[i]=0;s.actionPos.a[i]=MiningFeatures.pack(i%3,64,0);}
        f=new FeatureSet();MiningFeatures.analyze(s,f,null,null);
        assertEquals(0,count(f,"mine.hidden_probe"));
    }
    @Test void shortVehicleDisplacementIsFilteredAndMultipleSessionsAccumulate() throws Exception {
        LabSimulator sim=new LabSimulator(ArgusLab.honest(553),Map.of(),null);
        sim.boat(LabSimulator.BoatRoute.LAND,1800,0);
        SessionData s=TelemetryReader.readRaw(sim.w.drain());
        for(int i=20;i<s.states.size();i++)s.states.get(i).x+=5;
        assertTrue(VehicleFeatures.inspect(s,LabSimulator.VEHICLE_CONTEXT).episodes().isEmpty());
        sim=new LabSimulator(ArgusLab.honest(553),Map.of(),null);
        sim.boat(LabSimulator.BoatRoute.LAND,1800,1);
        s=TelemetryReader.readRaw(sim.w.drain());
        FeatureSet f=new FeatureSet();VehicleFeatures.analyze(s,f,LabSimulator.VEHICLE_CONTEXT);
        double single=count(f,"veh.land_speed"),den=f.raw().get("veh.land_speed").den;
        VehicleFeatures.analyze(s,f,LabSimulator.VEHICLE_CONTEXT);
        assertEquals(single*2,count(f,"veh.land_speed"),1e-9);assertEquals(den*2,f.raw().get("veh.land_speed").den,1e-9);
    }
    @Test void honestCeilingProjectionDoesNotShrinkExclusivePositiveFeatures() {
        List<Calibration.Sample> bag=new ArrayList<>();
        for(int i=0;i<20;i++) {
            bag.add(new Calibration.Sample(Map.of("shared",4.0),0,false));
            bag.add(new Calibration.Sample(Map.of("exclusive",1.6),0,true));
        }
        var prior=ArgusLab.trainEvidenceBagged(List.of(bag,bag,bag),"projection-test");
        assertEquals(2.5,prior.weight("exclusive"),.001);
        assertTrue(prior.weight("shared")*4<=ArgusLab.HONEST_BUDGET);
    }
    @Test void sixDryWallTicksBeforeSetbackAreNotErasedByEarlierGrace() throws Exception {
        SessionWriter w=new SessionWriter("u","wall",1_000_000,"f",1L<<30);
        w.header("{\"uuid\":\"u\",\"name\":\"wall\",\"start\":1000000}");
        long t=1_000_000;
        w.env(t,ENV_SUPPORT);w.speed(t,.1f);
        for(int i=0;i<30;i++) {t+=50;w.move(t,true,0,64,0,true,0,0,true,false);w.tickEnd(t+1);}
        w.move(t+10,true,0,64,0,true,0,0,false,true);w.tickEnd(t+11);
        for(int i=1;i<=6;i++) {t+=50;w.move(t,true,0,64+i*.26,0,true,0,0,false,true);w.tickEnd(t+1);}
        w.teleportSent(t+60,1,0,64,0,0);
        t+=100;w.move(t,true,0,64,0,true,0,0,true,false);w.tickEnd(t+1);
        assertEquals(6,count(movement(TelemetryReader.readRaw(w.drain())),"move.wall_climb"));
    }
    @Test void honestCeilingOnlyProjectsCoordinatesOfTheViolatedExample() {
        List<Calibration.Sample> bag=new ArrayList<>();
        for(int i=0;i<20;i++) {
            bag.add(new Calibration.Sample(Map.of("costly",4.0,"other",3.0),0,false));
            bag.add(new Calibration.Sample(Map.of("harmless",.01),0,false));
            bag.add(new Calibration.Sample(Map.of("harmless",1.6),0,true));
        }
        var prior=ArgusLab.trainEvidenceBagged(List.of(bag,bag,bag),"sparse-projection-test");
        assertTrue(prior.weight("harmless")>2.4);
        assertTrue(ArgusLab.model(prior).score(Map.of("costly",4.0,"other",3.0),0)<=ArgusLab.HONEST_CEILING);
        assertTrue(prior.weight("costly")*4<=ArgusLab.HONEST_BUDGET);
        assertTrue(prior.weight("other")*3<=ArgusLab.HONEST_BUDGET);
    }
    @Test void customWorldKeyRulesAbstainWhileOrdinaryWorldSpeedStillHasEvidence() throws Exception {
        PhysicsReplayTest.Sim sim=new PhysicsReplayTest.Sim();
        for(int i=0;i<150;i++) {sim.vz=.5;sim.tick(false,false);}
        SessionData s=sim.read();assertTrue(count(movement(s),"move.move_nokey")>0);
        SessionData.State st=new SessionData.State();st.t=1_000_000;st.world=3;st.gravity=.08f;st.jumpStrength=.42f;s.states.add(st);
        FeatureSet f=movement(s);assertEquals(0,count(f,"move.move_nokey"));
        assertTrue(count(f,"move.ground_gain")>0,"physical features stay active");
    }
    @Test void realTrainingMarginsRemainInsideTheHonestBudget() {
        List<Calibration.Sample> bag=new ArrayList<>();
        for(int i=0;i<10;i++) {bag.add(new Calibration.Sample(Map.of("shared",.8),0,false));bag.add(new Calibration.Sample(Map.of("shared",4.0),0,true));}
        var prior=ArgusLab.trainEvidenceBagged(List.of(bag,bag,bag),"margin-test",List.of(new Calibration.Sample(Map.of("shared",4.0),0,true)));
        assertTrue(ArgusLab.model(prior).score(Map.of("shared",4.0),0)>=.95);
        assertTrue(prior.weight("shared")*.8<=ArgusLab.HONEST_BUDGET);
    }
    @Test void migratedClearedSignalOnlyExamplesStayBelowTheExistingLine() {
        try {
            Calibration.Prior.override(new Calibration.Prior("clear-projection",-3,Map.of("sig",2.5,"positive",1.5)));
            List<Calibration.Sample> samples=new ArrayList<>();
            for(int i=0;i<54;i++)samples.add(new Calibration.Sample(Map.of(),0,false));
            for(int i=0;i<2;i++)samples.add(new Calibration.Sample(Map.of(),2.5,false));
            for(int i=0;i<3;i++)samples.add(new Calibration.Sample(Map.of("positive",4.0),1.45,true));
            Calibration model=new Calibration();model.fit(samples,.6);
            assertEquals(56,model.clean);assertTrue(model.score(Map.of(),2.5)<.6);
            assertTrue(model.score(Map.of("positive",4.0),1.45)>=.95);
        } finally {Calibration.Prior.override(null);}
    }
}
