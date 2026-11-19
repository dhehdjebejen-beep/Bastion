package dev.bastionac.argus.features;

import dev.bastionac.telemetry.SessionData;
import java.util.ArrayList;
import java.util.List;
import static dev.bastionac.telemetry.TelemetryFormat.*;

/** Three-dimensional ray reconstruction and angular phase diagnostics. */
public final class CombatPredictionEngine {
    private CombatPredictionEngine() {}
    public record Vec(double x,double y,double z) {}
    public record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    public record Trace(boolean hit,double distance,double miss,double angle) {}
    public record Result(boolean history,double distance,double miss,double centerError,boolean backtrack,
                         double range,int candidates) {}
    public static Vec look(float yaw,float pitch) {
        double y=Math.toRadians(yaw),p=Math.toRadians(pitch);
        return new Vec(-Math.sin(y)*Math.cos(p),-Math.sin(p),Math.cos(y)*Math.cos(p));
    }
    /** Slab clipping, including parallel axes, inside origins and boxes behind the origin. */
    public static Trace trace(Vec e,Vec d,Box b) {
        double enter=0,exit=Double.POSITIVE_INFINITY;
        double[] origin={e.x,e.y,e.z},direction={d.x,d.y,d.z};
        double[] low={b.minX,b.minY,b.minZ},high={b.maxX,b.maxY,b.maxZ};
        boolean hit=true;
        for(int k=0;k<3;k++) {
            if(Math.abs(direction[k])<1e-12) { if(origin[k]<low[k]||origin[k]>high[k])hit=false; }
            else {
                double a=(low[k]-origin[k])/direction[k],z=(high[k]-origin[k])/direction[k];
                enter=Math.max(enter,Math.min(a,z));exit=Math.min(exit,Math.max(a,z));
            }
        }
        if(hit&&exit>=enter)return new Trace(true,enter,0,0);
        // Squared distance to a convex box is piecewise quadratic along the ray.
        // Its minimum lies at a slab boundary or at a stationary point between boundaries.
        List<Double> cuts=new ArrayList<>();cuts.add(0d);
        for(int k=0;k<3;k++)if(Math.abs(direction[k])>=1e-12) {
            double a=(low[k]-origin[k])/direction[k],z=(high[k]-origin[k])/direction[k];
            if(a>0)cuts.add(a);if(z>0)cuts.add(z);
        }
        cuts.sort(Double::compare);cuts.add(Double.POSITIVE_INFINITY);
        double best=Double.POSITIVE_INFINITY,at=0;
        for(int i=0;i<cuts.size()-1;i++) {
            double l=cuts.get(i),r=cuts.get(i+1),m=Double.isFinite(r)?(l+r)/2:l+1;
            double numerator=0,denominator=0;
            for(int k=0;k<3;k++) {
                double p=origin[k]+m*direction[k];
                if(p<low[k]||p>high[k]) {
                    double edge=p<low[k]?low[k]:high[k];
                    numerator+=direction[k]*(edge-origin[k]);denominator+=direction[k]*direction[k];
                }
            }
            double t=denominator>0?Math.max(l,Math.min(r,numerator/denominator)):l;
            double dist=0;
            for(int k=0;k<3;k++) {
                double p=origin[k]+t*direction[k],q=Math.max(low[k],Math.min(high[k],p));
                dist+=(p-q)*(p-q);
            }
            if(dist<best) {best=dist;at=t;}
        }
        double miss=Math.sqrt(best);
        return new Trace(false,Double.POSITIVE_INFINITY,miss,Math.toDegrees(Math.atan2(miss,Math.max(1e-9,at))));
    }
    private record Point(long t,double x,double y,double z,float w,float h) {}
    private static Box box(Point p) {return new Box(p.x-p.w-.03,p.y-p.h/2-.03,p.z-p.w-.03,p.x+p.w+.03,p.y+p.h/2+.03,p.z+p.w+.03);}
    public static double range(SessionData s,long t) {
        int i=MovementFeatures.lowerBound(s.useT.a,s.useT.n,t+1)-1;
        int raw=i>=0&&i<s.useItem.n?s.useItem.a[i]:s.priorUseItem;
        long use=i>=0?s.useT.a[i]:s.priorUseT;
        int slot=MovementFeatures.lowerBound(s.slotT.a,s.slotT.n,use+1);
        if(raw<0||t-use>30000||(slot<s.slotT.n&&s.slotT.a[slot]<=t))return 3;
        if(s.itemRange!=null)return s.itemRange.applyAsDouble(raw);
        try {
            var item=net.minecraft.registry.Registries.ITEM.get(raw);
            return item!=null&&item.getComponents().contains(net.minecraft.component.DataComponentTypes.KINETIC_WEAPON)?4.5:3;
        } catch(RuntimeException e) {return 3;}
    }
    public static Result predict(SessionData s,SessionData.Attack a) {
        int now=CombatFeatures.lastPos(s,a.t),before=now<0?-1:CombatFeatures.lastPos(s,s.moveT.a[now]-1);
        List<Vec> eyes=new ArrayList<>();eyes.add(new Vec(a.eyeX,a.eyeY,a.eyeZ));
        if(before>=0)eyes.add(new Vec(a.eyeX+s.moveX.a[before]-s.moveX.a[now],
                a.eyeY+s.moveY.a[before]-s.moveY.a[now],a.eyeZ+s.moveZ.a[before]-s.moveZ.a[now]));
        double ey=now<0?1.62:a.eyeY-s.moveY.a[now];
        int ping=Math.max(0,a.ping),minPing=ping,maxPing=ping;
        for(int i=MovementFeatures.lowerBound(s.pongT.a,s.pongT.n,a.t-10000);i<s.pongT.n&&s.pongT.a[i]<=a.t;i++) {
            minPing=Math.min(minPing,s.pongRtt.a[i]);maxPing=Math.max(maxPing,s.pongRtt.a[i]);
        }
        long from=a.t-maxPing-210,to=a.t-Math.max(0,minPing-60),oldFrom=a.t-ping-650,oldTo=a.t-ping-220;
        List<Point> points=new ArrayList<>();
        for(int i=MovementFeatures.lowerBound(s.targetT.a,s.targetT.n,oldFrom-200);i<s.targetT.n&&s.targetT.a[i]<=a.t;i++) {
            if(s.targetId.a[i]!=a.targetId)continue;
            int p=CombatFeatures.lastPos(s,s.targetT.a[i]);if(p<0)continue;
            float historicalEye=s.moveEnv.n>p&&(s.moveEnv.a[p]&ENV_SNEAKING)!=0?1.27f:1.62f;
            // Preserve an observed nonstandard pose through its unchanged interval.
            double eyeAt=Math.abs(ey-1.62)<.05||Math.abs(ey-1.27)<.05?historicalEye:ey;
            points.add(new Point(s.targetT.a[i],s.moveX.a[p]+s.targetDX.a[i],s.moveY.a[p]+eyeAt+s.targetDY.a[i],
                    s.moveZ.a[p]+s.targetDZ.a[i],s.targetHW.a[i],s.targetH.a[i]));
        }
        points.sort(java.util.Comparator.comparingLong(Point::t));
        boolean history=points.stream().filter(p->p.t>=from&&p.t<=to).count()>=2;
        points.add(new Point(a.t,a.cx,a.cy,a.cz,a.halfWidth>0?a.halfWidth:.3f,a.height>0?a.height:1.8f));
        Vec d=look(a.yaw,a.pitch);
        double distance=Double.POSITIVE_INFINITY,miss=Double.POSITIVE_INFINITY,center=Double.POSITIVE_INFINITY,oldDistance=Double.POSITIVE_INFINITY;
        int candidates=0;
        for(int i=0;i<points.size();i++) {
            Point p=points.get(i),q=i+1<points.size()?points.get(i+1):p;
            // Never bridge absent packets, a teleport-sized target jump or a dimension transition.
            long span=q.t-p.t;
            int steps=span>0&&span<=200&&Math.hypot(q.x-p.x,q.z-p.z)<3?(int)(span/10):0;
            for(int k=0;k<=steps;k++) {
                double f=steps==0?0:(double)k/steps;
                Point at=new Point(p.t+(long)(span*f),p.x+(q.x-p.x)*f,p.y+(q.y-p.y)*f,p.z+(q.z-p.z)*f,p.w,p.h);
                boolean legal=at.t>=from&&at.t<=to,old=at.t>=oldFrom&&at.t<oldTo;
                if(!legal&&!old)continue;
                for(Vec e:eyes) {
                    Trace ray=trace(e,d,box(at));
                    if(legal) {
                        candidates++;distance=Math.min(distance,ray.distance);miss=Math.min(miss,ray.miss);
                        double x=at.x-e.x,y=at.y-e.y,z=at.z-e.z,len=Math.sqrt(x*x+y*y+z*z);
                        if(len>1e-9)center=Math.min(center,Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(x*d.x+y*d.y+z*d.z)/len)))));
                    }
                    if(old)oldDistance=Math.min(oldDistance,ray.distance);
                }
            }
        }
        double range=range(s,a.t);
        boolean back=history&&(distance>range+.15||miss>.15)&&oldDistance<=range;
        return new Result(history,distance,miss,center,back,range,candidates);
    }
    public static void analyze(SessionData s,FeatureSet out) {
        long judged=0,miss=0,back=0;
        for(var a:s.attacks) {
            var r=predict(s,a);
            if(!r.history||a.ping>1000||PacketFeatures.anyIn(s.tpSentT,a.t-1000,a.t+200))continue;
            judged++;
            if(r.miss>.10) {miss++;out.moment("cmb.hitbox_miss",a.t);}
            if(r.backtrack) {back++;out.moment("cmb.backtrack",a.t);}
        }
        out.rate("cmb.hitbox_miss",miss,judged);out.rate("cmb.backtrack",back,judged);
        kinematics(s,out);
    }
    public record Phase(double velocity,double acceleration,double jerk) {}
    public static Phase phase(double delta,double previousVelocity,double previousAcceleration,double seconds) {
        double velocity=delta/seconds,acceleration=(velocity-previousVelocity)/seconds;
        return new Phase(velocity,acceleration,(acceleration-previousAcceleration)/seconds);
    }
    private static void kinematics(SessionData s,FeatureSet out) {
        int last=-1;double v=0,acc=0,previousDelta=0;long snap=0,judged=0;
        int[] bins=new int[16];int n=0;
        var resets=CombatFeatures.teleportTimes(s);
        var attackTimes=new dev.bastionac.telemetry.Prim.Longs();
        for(var a:s.attacks)attackTimes.add(a.t);
        for(int i=0;i<s.moveT.n;i++) {
            if((s.moveFlags.a[i]&MV_LOOK)==0)continue;
            // Use the final orientation of each physical tick. Packet splitting
            // and duplicate arrival times do not create camera derivatives.
            if(s.protocol>=768&&i+1<s.moveT.n&&s.moveTick.a[i+1]==s.moveTick.a[i]
                    &&(s.moveFlags.a[i+1]&MV_LOOK)!=0)continue;
            long time=s.moveT.a[i];
            int ticks=last<0?0:s.moveTick.a[i]-s.moveTick.a[last];
            boolean contiguous=s.protocol>=768?ticks==1:last>=0&&time>s.moveT.a[last]&&time-s.moveT.a[last]<=100;
            if(last>=0&&contiguous&&!PacketFeatures.anyIn(resets,time-1500,time+200)
                    &&!PacketFeatures.anyIn(s.vehicleT,time-200,time+200)
                    &&PacketFeatures.anyIn(attackTimes,time-2500,time+2500)) {
                double delta=CombatFeatures.wrap(s.moveYaw.a[i]-s.moveYaw.a[last]);
                // TCP burst arrival intervals are not camera time. Client tick indices
                // preserve the angular derivatives through a released packet queue.
                double seconds=s.protocol>=768&&ticks>0?ticks*.05:.05;
                var p=phase(delta,v,acc,seconds);
                out.sample("cmb.angular_jerk",Math.abs(p.jerk));v=p.velocity;acc=p.acceleration;
                judged++;
                if(Math.abs(delta)>25&&delta*previousDelta<0&&Math.abs(delta+previousDelta)<1) {snap++;out.moment("cmb.snap_back",s.moveT.a[i]);}
                previousDelta=delta;
                if(Math.abs(delta)>.01) {
                    bins[Math.min(15,(int)(Math.log1p(Math.abs(delta)*2)/Math.log(2)*2))]++;n++;
                    // Disjoint blocks provide one supported entropy estimate per
                    // 128 actual turns, rather than repeating a session statistic.
                    if(n==128) {
                        double entropy=0;
                        for(int b:bins)if(b>0) {double probability=(double)b/n;entropy-=probability*Math.log(probability)/Math.log(2);}
                        out.sample("cmb.step_entropy",entropy);
                        java.util.Arrays.fill(bins,0);n=0;
                    }
                }
            } else {v=acc=previousDelta=0;java.util.Arrays.fill(bins,0);n=0;}
            last=i;
        }
        out.rate("cmb.snap_back",snap,judged);
    }
}
