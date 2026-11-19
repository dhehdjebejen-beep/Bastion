package dev.bastionac.argus.features;

import dev.bastionac.telemetry.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.bastionac.telemetry.TelemetryFormat.*;

class PredictionEngineTest {
    private MovementPredictionEngine.Step step(int f,int s,int mode,boolean jump,boolean spear) {
        return MovementPredictionEngine.step(0,0,0,true,0,.1f,.6f,.08f,.42f,
                new MovementPredictionEngine.Branch(f,s,mode,jump),.3f,.2f,spear);
    }
    @Test void exactlySixtySixUniqueInputs() {
        assertEquals(66,MovementPredictionEngine.branches().size());
        assertEquals(66,MovementPredictionEngine.branches().stream().distinct().count());
    }
    @Test void diagonalNormalisesAfterKeyboardScaling() {
        var axial=step(1,0,0,false,false);var diagonal=step(1,1,0,false,false);
        assertEquals(.098,axial.z(),1e-7);
        assertEquals(.1,Math.hypot(diagonal.x(),diagonal.z()),1e-7);
        assertEquals(diagonal.x(),diagonal.z(),1e-12);
    }
    @Test void sneakingAndItemDiagonalRemainUnnormalised() {
        var a=step(1,0,2,false,false);var d=step(1,1,2,false,false);
        assertEquals(Math.sqrt(2)*a.z(),Math.hypot(d.x(),d.z()),1e-12);
        assertEquals(.0196,step(1,0,3,false,false).z(),1e-7);
    }
    @Test void spearDoesNotSlowTheInput() {assertEquals(step(1,0,0,false,false).z(),step(1,0,3,false,true).z(),1e-12);}
    @Test void jumpAndDragAreOrdered() {
        var p=step(1,0,1,true,false);
        assertEquals((double).42f,p.y());assertEquals(.298,p.z(),1e-7);
        assertEquals(p.z()*(.6f*.91f),p.nextZ());assertEquals((p.y()-.08f)*.98f,p.nextY());
    }
    @Test void sineTableMatchesClientIntegerIndexForNegativeAngles() {
        for(float yaw:new float[]{0,90,-90,180,-179.1f,9876}) {
            float rad=yaw*.017453292f;int index=(int)(rad*10430.378f)&65535;
            assertEquals(Float.floatToIntBits((float)Math.sin(index*Math.PI*2/65536)),Float.floatToIntBits(MovementPredictionEngine.sin(rad)));
        }
    }
    @Test void smallComponentsAreZeroedBeforeAcceleration() {
        var p=MovementPredictionEngine.step(.0029,.0029,-.0029,false,0,.1f,.6f,.08f,.42f,
                new MovementPredictionEngine.Branch(0,0,0,false),.3f,.2f,false);
        assertEquals(0,p.x());assertEquals(0,p.y());assertEquals(0,p.z());assertEquals(-.08f*.98f,p.nextY(),1e-8);
    }
    @Test void previousInputAllowsOneTickPacketShift() {
        var p=step(1,0,0,false,false);
        var r=MovementPredictionEngine.residual(0,0,0,p.x(),0,p.z(),true,0,.1f,.6f,.08f,.42f,
                0,IN_FORWARD,true,false,.2f,false);
        assertEquals(0,r.input(),1e-12);
    }
    @Test void raySlabsCoverParallelInsideBehindAndCorner() {
        var box=new CombatPredictionEngine.Box(-.3,0,2.7,.3,1.8,3.3);
        var hit=CombatPredictionEngine.trace(new CombatPredictionEngine.Vec(0,1.62,0),new CombatPredictionEngine.Vec(0,0,1),box);
        assertTrue(hit.hit());assertEquals(2.7,hit.distance());
        assertFalse(CombatPredictionEngine.trace(new CombatPredictionEngine.Vec(.5,1,0),new CombatPredictionEngine.Vec(0,0,1),box).hit());
        assertEquals(.2,CombatPredictionEngine.trace(new CombatPredictionEngine.Vec(.5,1,0),new CombatPredictionEngine.Vec(0,0,1),box).miss(),1e-12);
        assertEquals(0,CombatPredictionEngine.trace(new CombatPredictionEngine.Vec(0,1,3),new CombatPredictionEngine.Vec(0,0,1),box).distance());
        assertFalse(CombatPredictionEngine.trace(new CombatPredictionEngine.Vec(0,1,5),new CombatPredictionEngine.Vec(0,0,1),box).hit());
    }
    @Test void closestRayPointMatchesIndependentDenseSearch() {
        var b=new CombatPredictionEngine.Box(1,2,3,2,3,4);var e=new CombatPredictionEngine.Vec(0,0,0);
        var d=CombatPredictionEngine.look(-10,-15);double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<100000;i++) {
            double t=i*.0001,x=t*d.x(),y=t*d.y(),z=t*d.z();
            double dx=Math.max(1,Math.min(2,x))-x,dy=Math.max(2,Math.min(3,y))-y,dz=Math.max(3,Math.min(4,z))-z;
            best=Math.min(best,Math.sqrt(dx*dx+dy*dy+dz*dz));
        }
        assertEquals(best,CombatPredictionEngine.trace(e,d,b).miss(),1e-7);
    }
    @Test void kinematicDerivativesHavePhysicalUnits() {
        var p=CombatPredictionEngine.phase(2,20,100,.05);
        assertEquals(40,p.velocity());assertEquals(400,p.acceleration());assertEquals(6000,p.jerk());
    }
    @Test void highSensitivityAccumulatedTurnIsNotYawWrap() {
        assertFalse(CombatFeatures.isYawWrap(350,720,120,370));
        assertFalse(CombatFeatures.isYawWrap(179,-179,2,-358));
        assertTrue(CombatFeatures.isYawWrap(2579,8698,2579,6119));
        assertTrue(CombatFeatures.isYawWrap(18813,32287,-.3,13474));
    }
    @Test void simultaneousSneakAndItemScaleBothAxes() {
        double axis=.1f*(.21600002f/(.6f*.6f*.6f))*(.98f*.3f*.2f);
        var r=MovementPredictionEngine.residual(0,0,0,axis,0,axis,true,0,.1f,.6f,.08f,.42f,
                IN_FORWARD|IN_LEFT|IN_SNEAK,0,true,true,.2f,false,true);
        assertEquals(0,r.physical(),1e-8);assertEquals(0,r.input(),1e-8);
    }
    @Test void sprintAllowsBothRecordedAndClientAttributes() {
        var a=MovementPredictionEngine.step(0,0,0,true,0,.1f,.6f,.08f,.42f,
                new MovementPredictionEngine.Branch(1,0,1,true),.3f,.2f,true);
        var r=MovementPredictionEngine.residual(0,0,0,a.x(),a.y(),a.z(),true,0,.1f,.6f,.08f,.42f,
                IN_FORWARD|IN_SPRINT|IN_JUMP,0,true,true,1,true);
        assertEquals(0,r.physical(),1e-12);
    }
    private SessionData geometry(boolean oldHit,boolean parallax) {
        var s=new SessionData();s.itemRange=raw->3;
        for(int i=0;i<21;i++) {
            long t=1000+i*50;
            double x=parallax&&i==20?1:0;
            s.moveT.add(t);s.moveFlags.add(MV_POS);s.moveX.add(x);s.moveY.add(64);s.moveZ.add(0);
            if(i<20) {
                s.targetT.add(t);s.targetId.add(7);s.targetDX.add((float)((oldHit&&i>=14?1:0)-x));
                s.targetDY.add(0);s.targetDZ.add(2.8f);s.targetHW.add(.3f);s.targetH.add(1.8f);
            }
        }
        var a=new SessionData.Attack();a.t=2000;a.targetId=7;a.eyeX=parallax?1:0;a.eyeY=65.62;
        a.cx=oldHit?1:0;a.cy=65.62;a.cz=2.8;a.halfWidth=.3f;a.height=1.8f;s.attacks.add(a);
        return s;
    }
    @Test void previousEyeParallaxPreservesLegalRay() {
        var s=geometry(false,true);var r=CombatPredictionEngine.predict(s,s.attacks.getFirst());
        assertTrue(r.history());assertEquals(0,r.miss());assertTrue(r.distance()<3);assertFalse(r.backtrack());
    }
    @Test void oldHitboxBeyondPingWindowIsBacktrack() {
        var s=geometry(true,false);var r=CombatPredictionEngine.predict(s,s.attacks.getFirst());
        assertTrue(r.history());assertTrue(r.miss()>.15);assertTrue(r.backtrack());
        s.attacks.getFirst().ping=400;
        assertFalse(CombatPredictionEngine.predict(s,s.attacks.getFirst()).backtrack());
    }
    @Test void hitboxExpansionHasExactTransverseMiss() {
        var s=geometry(false,false);
        for(int i=0;i<s.targetDX.n;i++)s.targetDX.a[i]=.7f;s.attacks.getFirst().cx=.7;
        var r=CombatPredictionEngine.predict(s,s.attacks.getFirst());
        assertEquals(.37,r.miss(),1e-7);assertFalse(r.backtrack());
    }
    private SessionData impulse(boolean response) throws Exception {
        return CausalPredictionTest.impulse(response?1:0,.6,false,false);
    }
    @Test void damageImpulseNeedsResponseAndRejectsCancelledAbility() throws Exception {
        var s=impulse(false);var fs=new FeatureSet();MovementPredictionEngine.knockback(s,fs);
        assertEquals(1,fs.raw().get("pred.kb_ignore").num);
        fs=new FeatureSet();MovementPredictionEngine.knockback(impulse(true),fs);assertEquals(0,fs.raw().get("pred.kb_ignore").num);
        s.tpSentT.add(2000);fs=new FeatureSet();MovementPredictionEngine.knockback(s,fs);assertEquals(0,fs.raw().get("pred.kb_ignore").den);
        s.tpSentT.n=0;s.states.getFirst().world=3;fs=new FeatureSet();MovementPredictionEngine.knockback(s,fs);assertEquals(0,fs.raw().get("pred.kb_ignore").den);
    }
    @Test void onePongCannotEstablishSelectiveMovementStall() {
        var s=new SessionData();s.pongT.add(900);s.pongRtt.add(209);
        assertFalse(PacketFeatures.answeredPromptly(s,350,950));
        s.pongT.add(700);s.pongRtt.add(100);
        java.util.Arrays.sort(s.pongT.a,0,s.pongT.n);s.pongRtt.a[0]=100;s.pongRtt.a[1]=209;
        assertTrue(PacketFeatures.answeredPromptly(s,350,950));
        assertFalse(PacketFeatures.answeredPromptly(s,650,950));
    }
    @Test void versionOneUsePayloadAndCropPrefixStayAligned() throws Exception {
        var w=new SessionWriter("u","p",1000,"f",100000);w.header("{\"start\":1000}");
        w.useItem(1010,1,123,45,-30);w.move(1020,true,1,64,2,true,90,10,true,false);w.tickEnd(1021);
        byte[] bytes=w.drain();var all=TelemetryReader.readRaw(bytes);var crop=TelemetryReader.readRaw(bytes,1015,1030);
        assertEquals(123,all.useItem.a[0]);assertEquals(1,all.useHand.a[0]);assertEquals(-30,all.usePitch.a[0]);
        assertEquals(123,crop.priorUseItem);assertEquals(1010,crop.priorUseT);assertEquals(all.moveX.a[0],crop.moveX.a[0]);
        assertFalse(all.truncated);assertEquals(all.records,crop.records);
    }
}
