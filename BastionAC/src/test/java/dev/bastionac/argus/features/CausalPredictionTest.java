package dev.bastionac.argus.features;

import dev.bastionac.telemetry.*;
import org.junit.jupiter.api.Test;
import static dev.bastionac.telemetry.TelemetryFormat.*;
import static org.junit.jupiter.api.Assertions.*;

class CausalPredictionTest {
    static SessionData impulse(double accepted,double h,boolean sprint,boolean queue) throws Exception {
        var w=new SessionWriter("u","p",1000,"f",1000000);w.header("{\"start\":1000}");
        w.client(1000,"vanilla",774,"ru_ru",12,1,127,0,java.util.List.of());
        double x=0,y=64,z=0,vx=0,vy=0,vz=0;boolean ground=true;
        for(int i=0;i<50;i++) {
            long t=1000+i*50;
            if(queue&&i>=19&&i<=26)t=2300;
            int keys=sprint?IN_FORWARD|IN_SPRINT:0;
            w.input(t,keys);w.env(t,sprint?ENV_SPRINTING:0);w.ground(t,1);w.speed(t,sprint?.13f:.1f);
            if(i==18) {
                var st=new SessionWriter.State();st.flags=ST_CHUNK_LOADED;st.ping=50;st.speedAttr=.1f;
                st.gravity=.08f;st.jumpStrength=.42f;w.state(t,st);
                w.damage(t,2,"minecraft:player_attack",1);w.velocity(t,0,.3f,(float)h,false);
                vx=0;vy=.3*accepted;vz=h*accepted;
            }
            boolean startedGround=ground;
            // Independent scalar arithmetic for a yaw-zero axial input.
            if(Math.abs(vy)<.003)vy=0;if(Math.abs(vz)<.003)vz=0;
            if(sprint)vz+=(ground?.13f*(.21600002f/(.6f*.6f*.6f)):.026f)*.98f;
            x+=vx;y+=vy;z+=vz;
            if(y<=64) {y=64;vy=0;ground=true;}else ground=false;
            w.move(t,true,x,y,z,true,0,0,ground,false);
            if(queue)w.move(t,false,x,y,z,true,0,0,ground,false);
            w.tickEnd(t);
            float drag=startedGround?.6f*.91f:.91f;vx*=drag;vz*=drag;vy=(vy-.08f)*.98f;
        }
        return TelemetryReader.readRaw(w.drain());
    }
    @Test void honestSmallImpulseSurvivesQueuedDuplicateLookPackets() throws Exception {
        var fs=new FeatureSet();MovementPredictionEngine.knockback(impulse(1,.218,false,true),fs);
        assertEquals(1,fs.acc("pred.kb_ignore").den);assertEquals(0,fs.acc("pred.kb_ignore").num);
    }
    @Test void sprintInImpulseDirectionCannotConsumeAcceptedVelocity() throws Exception {
        var fs=new FeatureSet();MovementPredictionEngine.knockback(impulse(1,.218,true,false),fs);
        assertEquals(1,fs.acc("pred.kb_ignore").den);assertEquals(0,fs.acc("pred.kb_ignore").num);
    }
    @Test void landingSupportCanPrecedeTheReportedGroundFlag() throws Exception {
        var s=impulse(1,.218,true,false);
        s.moveFlags.a[17]&=~MV_GROUND;
        s.moveEnv.a[18]|=ENV_GROUND_BELOW|ENV_FLOOR_FACE;
        var fs=new FeatureSet();MovementPredictionEngine.knockback(s,fs);
        assertEquals(1,fs.acc("pred.kb_ignore").den);assertEquals(0,fs.acc("pred.kb_ignore").num);
    }
    @Test void fullAndPartialSuppressionFitReducedVector() throws Exception {
        for(double suppression:new double[]{1,.5,.25}) {
            var fs=new FeatureSet();MovementPredictionEngine.knockback(impulse(1-suppression,.6,false,false),fs);
            assertEquals(1,fs.acc("pred.kb_ignore").den,"coverage "+suppression);
            assertEquals(1,fs.acc("pred.kb_ignore").num,"suppression "+suppression);
        }
    }
    @Test void correctionOrLiquidCannotProveIgnoredImpulse() throws Exception {
        var s=impulse(0,.6,false,false);s.corrT.add(2000);
        var fs=new FeatureSet();MovementPredictionEngine.knockback(s,fs);assertEquals(0,fs.acc("pred.kb_ignore").den);
        s.corrT.n=0;for(int i=0;i<s.moveEnv.n;i++)s.moveEnv.a[i]|=ENV_LIQUID;
        fs=new FeatureSet();MovementPredictionEngine.knockback(s,fs);assertEquals(0,fs.acc("pred.kb_ignore").den);
    }
    @Test void loginCohortDoesNotCreateTimerGain() {
        for(boolean smeared:new boolean[]{false,true}) {
            var ticks=new Prim.Longs();
            for(int i=0;i<11;i++)ticks.add(1000+(smeared?i*2:0));
            for(int i=1;i<=160;i++)ticks.add(1022+i*50);
            assertFalse(PacketFeatures.gainedTime(ticks,1500));
        }
    }
    @Test void sustainedTimerRetainsGainAtAllLaboratoryLevels() {
        for(double level:new double[]{.25,.5,1}) {
            var ticks=new Prim.Longs();for(int i=0;i<1000;i++)ticks.add(1000+Math.round(i*50/(1+.25*level)));
            assertTrue(PacketFeatures.gainedTime(ticks,20000));
        }
    }
    @Test void shortModernLoginUsesClockInsteadOfRawServerBalance() {
        for(boolean timer:new boolean[]{false,true}) {
            var s=new SessionData();s.protocol=774;s.startMs=s.firstT=1000;
            if(!timer)for(int i=0;i<11;i++)s.tickEndT.add(1000);
            for(int i=1;i<=160;i++)s.tickEndT.add(1022+i*(timer?40:50));
            s.lastT=s.tickEndT.a[s.tickEndT.n-1];
            var state=new SessionData.State();state.t=1500;state.timerWorst=-450;s.states.add(state);
            var fs=new FeatureSet();PacketFeatures.analyze(s,fs);
            assertEquals(timer?1:0,fs.acc("pkt.timer_episodes").num);
        }
    }
    @Test void aReleasedQueuePreservesElapsedWaitingTime() {
        var ticks=new Prim.Longs();
        for(int i=0;i<600;i++)ticks.add(i>=100&&i<120?7000:1000+i*50);
        assertFalse(PacketFeatures.gainedTime(ticks,15000));
    }
    @Test void canonicalReturnsAndOutwardDiscontinuitiesAreDistinct() {
        for(float from:new float[]{-889.89f,416.39f,-1659.40f,-1706.34f})
            assertFalse(CombatFeatures.isYawWrap(from,93.89f,0,93.89-from));
        assertTrue(CombatFeatures.isYawWrap(0,2579,0,2579));
        assertTrue(CombatFeatures.isYawWrap(2579,70376,10,67797));
    }
    @Test void entropySupportComesFromDisjointPhysicalBlocks() {
        var s=new SessionData();s.protocol=774;
        var attack=new SessionData.Attack();attack.t=1000;s.attacks.add(attack);
        for(int i=0;i<1026;i++) {
            long t=1000+i*50L;s.moveT.add(t);s.moveTick.add(i);s.moveFlags.add(MV_LOOK);
            s.moveYaw.add(i*.5f);s.movePitch.add(0);
            if(i%40==0) {var a=new SessionData.Attack();a.t=t;s.attacks.add(a);}
        }
        var fs=new FeatureSet();CombatPredictionEngine.analyze(s,fs);
        var value=fs.value(Features.spec("cmb.step_entropy"));assertNotNull(value);
        assertEquals(8,value.support());assertEquals(0,value.value());
    }
    @Test void supportedSnapAndEntropyReachTheScorer() {
        var s=new SessionData();s.protocol=774;
        for(int i=0;i<1538;i++) {
            long time=1000+i*50L;s.moveT.add(time);s.moveTick.add(i);s.moveFlags.add(MV_LOOK);
            s.moveYaw.add(i%2==0?0:45);s.movePitch.add(0);
            if(i%20==0) {var a=new SessionData.Attack();a.t=time;s.attacks.add(a);}
        }
        var fs=new FeatureSet();CombatPredictionEngine.analyze(s,fs);
        var score=dev.bastionac.argus.Scorer.score(fs,null,"u",null,0);
        for(String key:new String[]{"cmb.snap_back","cmb.step_entropy"}) {
            var row=score.rows().stream().filter(r->r.spec().key().equals(key)).findFirst().orElseThrow();
            assertTrue(row.support()>=row.spec().minSupport());assertTrue(row.evidence()>0,key);
        }
    }
    private FeatureSet clicking(boolean machine,boolean tickQuantized) {
        var s=new SessionData();long time=1000;
        for(int i=0;i<300;i++) {
            time+=tickQuantized?50:machine?71+i%2:65+i%5*5;
            s.swingT.add(time);
            if(i%10==0) {var a=new SessionData.Attack();a.t=time;s.attacks.add(a);}
        }
        s.firstT=1000;s.lastT=time;
        var fs=new FeatureSet();CombatFeatures.analyze(s,fs);return fs;
    }
    @Test void mouseClockSeparatesRegularClicksFromFastHonestHands() {
        var clean=clicking(false,false).value(Features.spec("cmb.click_rhythm"));
        var automatic=clicking(true,false).value(Features.spec("cmb.click_rhythm"));
        assertNotNull(clean);assertNotNull(automatic);
        assertTrue(clean.value()>-3);assertTrue(automatic.value()<-4);
        assertNull(clicking(false,true).value(Features.spec("cmb.click_rhythm")));
    }
    @Test void coldKurtosisCanMeasureUniformNoiseWithoutFlaggingALognormalHand() {
        var machine=new FeatureSet();var hand=new FeatureSet();var random=new java.util.Random(41);
        for(int i=0;i<3000;i++) {
            machine.sample("cmb.rhythm_kurt",70+random.nextInt(5));
            hand.sample("cmb.rhythm_kurt",Math.round(70*Math.exp(random.nextGaussian()*.12)));
        }
        var automatic=dev.bastionac.argus.Scorer.score(machine,null,"u",null,0);
        var ordinary=dev.bastionac.argus.Scorer.score(hand,null,"v",null,0);
        assertTrue(automatic.evidenceByKey().getOrDefault("cmb.rhythm_kurt",0.0)>0);
        assertEquals(0.0,ordinary.evidenceByKey().getOrDefault("cmb.rhythm_kurt",0.0).doubleValue());
    }
    @Test void weakMultipleDelayPeaksCannotEstablishReactionTime() {
        var profile=new CombatFeatures.Profile();
        profile.windows=6;
        for(int i=0;i<profile.sxy.length;i++) {
            profile.sxx[i]=profile.syy[i]=250;profile.information[i]=160;
            profile.sxy[i]=250*(i==0?.18:i==8?.17:0);
        }
        var features=new FeatureSet();profile.emit(features);
        assertNull(features.value(Features.spec("cmb.track_lag")));
        assertNull(features.value(Features.spec("cmb.track_instant")));
    }
    @Test void independentInstantAndDelayedResponsesPreserveTheirPhysicalLag() {
        for(int reaction:new int[]{0,4}) {
            var profile=new CombatFeatures.Profile();var random=new java.util.Random(731+reaction);
            for(int window=0;window<6;window++) {
                double[] target=new double[70],camera=new double[100];
                for(int i=0;i<camera.length;i++)camera[i]=random.nextGaussian()*.1;
                for(int i=0;i<target.length;i++) {
                    target[i]=random.nextGaussian();camera[i+3+reaction]+=target[i];
                }
                profile.add(target,camera,3,3);
            }
            var features=new FeatureSet();profile.emit(features);
            assertEquals(reaction,features.value(Features.spec("cmb.track_lag")).value());
            if(reaction==0)assertTrue(features.value(Features.spec("cmb.track_instant")).value()>.8);
            else assertTrue(features.value(Features.spec("cmb.track_instant")).value()<.15);
        }
    }
}
