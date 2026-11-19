package dev.bastionac.argus.features;

import dev.bastionac.telemetry.SessionData;
import java.util.ArrayList;
import java.util.List;
import static dev.bastionac.telemetry.TelemetryFormat.*;

/** Deterministic reconstruction of a version-one client movement stream. */
public final class MovementPredictionEngine {
    private MovementPredictionEngine() {}
    private static final float[] SIN = new float[65536];
    static { for (int i=0;i<SIN.length;i++) SIN[i]=(float)Math.sin(i*Math.PI*2/65536); }
    public static float sin(float radians) { return SIN[(int)(radians*10430.378f)&65535]; }
    public static float cos(float radians) { return SIN[(int)(radians*10430.378f+16384f)&65535]; }
    public static double zero(double v) { return Math.abs(v)<.003 ? 0 : v; }
    public record Branch(int forward,int strafe,int mode,boolean jump) {}
    public record Step(double x,double y,double z,double nextX,double nextY,double nextZ) {}
    private static final List<Branch> BRANCHES;
    static {
        List<Branch> list=new ArrayList<>();
        for(int f=-1;f<=1;f++)for(int s=-1;s<=1;s++) {
            if(f==0&&s==0) { list.add(new Branch(0,0,0,false));list.add(new Branch(0,0,0,true)); }
            else for(int mode=0;mode<4;mode++)for(boolean jump:new boolean[]{false,true})list.add(new Branch(f,s,mode,jump));
        }
        BRANCHES=List.copyOf(list);
    }
    public static List<Branch> branches() { return BRANCHES; }
    /** Mode: walk, sprint, sneak, item. Attribute already includes recorded modifiers. */
    public static Step step(double vx,double vy,double vz,boolean ground,float yaw,float speed,float slip,
                            float gravity,float jumpStrength,Branch branch,float sneak,float use,boolean spear) {
        vx=zero(vx);vy=zero(vy);vz=zero(vz);
        boolean sprint=branch.mode==1;
        float f=.98f*branch.forward,s=.98f*branch.strafe;
        if(branch.mode==2) { f*=sneak;s*=sneak; }
        if(branch.mode==3&&!spear) { f*=use;s*=use;sprint=false; }
        float rad=yaw*.017453292f,sy=sin(rad),cy=cos(rad);
        if(ground&&branch.jump) {
            vy=Math.max(vy,jumpStrength);
            if(sprint) { vx-=sy*.2f;vz+=cy*.2f; }
        }
        float accel=ground ? speed*(.21600002f/(slip*slip*slip)) : sprint ? .026f : .02f;
        double length=(double)s*s+(double)f*f;
        if(length>=1e-7) {
            double scale=length>1 ? 1/Math.sqrt(length) : 1;
            double side=s*scale*accel,forward=f*scale*accel;
            vx+=side*cy-forward*sy;vz+=forward*cy+side*sy;
        }
        float drag=ground?slip*.91f:.91f;
        return new Step(vx,vy,vz,vx*drag,(vy-gravity)*.98f,vz*drag);
    }
    public record Residual(double physical,double input,double vertical,double spatial) {}
    public static Residual residual(double vx,double vy,double vz,double dx,double dy,double dz,boolean ground,
                                    float yaw,float speed,float slip,float gravity,float jump,int keys,int previousKeys,
                                    boolean vanillaKeys,boolean using,float use,boolean spear) {
        return residual(vx,vy,vz,dx,dy,dz,ground,yaw,speed,slip,gravity,jump,keys,previousKeys,
                vanillaKeys,using,use,spear,false);
    }
    public static Residual residual(double vx,double vy,double vz,double dx,double dy,double dz,boolean ground,
                                    float yaw,float speed,float slip,float gravity,float jump,int keys,int previousKeys,
                                    boolean vanillaKeys,boolean using,float use,boolean spear,boolean recordedSneak) {
        double phys=Double.POSITIVE_INFINITY,key=Double.POSITIVE_INFINITY,space=Double.POSITIVE_INFINITY;
        for(Branch b:BRANCHES) {
            // A known active item use constrains every possible input branch.
            Branch effective=using&&!spear ? new Branch(b.forward,b.strafe,3,b.jump) : b;
            int sneaks=b.mode==2||recordedSneak?2:1;
            float clientSpeed=b.mode==1?Math.max(speed,.13f):speed;
            int attrs=clientSpeed!=speed?2:1;
            for(int sn=0;sn<sneaks;sn++) for(int at=0;at<attrs;at++) {
                float sneak=sn==0?.3f:.75f,attr=at==0?speed:clientSpeed;
                float combinedUse=use*((using&&!spear&&(recordedSneak||b.mode==2))?sneak:1f);
                Step p=step(vx,vy,vz,ground,yaw,attr,slip,gravity,jump,effective,sneak,combinedUse,spear);
                double r=Math.hypot(dx-p.x,dz-p.z);
                phys=Math.min(phys,r);space=Math.min(space,Math.hypot(r,dy-p.y));
                if(!vanillaKeys||matches(b,keys)||matches(b,previousKeys))key=Math.min(key,r);
            }
        }
        return new Residual(phys,key,dy-vy,space);
    }
    private static boolean matches(Branch b,int keys) {
        int f=((keys&IN_FORWARD)!=0?1:0)-((keys&IN_BACK)!=0?1:0);
        int s=((keys&IN_LEFT)!=0?1:0)-((keys&IN_RIGHT)!=0?1:0);
        return b.forward==f&&b.strafe==s&&(!b.jump||(keys&IN_JUMP)!=0);
    }
    public static void analyze(SessionData s,FeatureSet out,MovementFeatures.BlockInfo blocks) {
        if(s.moveT.n<10)return;
        boolean[] bad=MovementFeatures.excluded(s);
        var tk=s.tickEndT.n>20&&(s.protocol<0||s.protocol>=768)
                ? MovementFeatures.byClientTick(s,bad):MovementFeatures.byPacket(s,bad);
        analyze(s,tk,out,blocks);
    }
    static void analyze(SessionData s,MovementFeatures.Ticks tk,FeatureSet out,MovementFeatures.BlockInfo blocks) {
        long judged=0,offset=0,inputOff=0,keyJudged=0,slowJudged=0,noSlow=0;
        int state=0,useIndex=-1;
        for(int c=2;c<tk.n;c++) {
            int a=c-2,b=c-1;
            while(state+1<s.states.size()&&s.states.get(state+1).t<=tk.t[c])state++;
            while(useIndex+1<s.useT.n&&s.useT.a[useIndex+1]<=tk.t[c])useIndex++;
            var st=s.states.isEmpty()?null:s.states.get(state);
            if(st!=null&&st.world==3)continue;
            if(!tk.valid[a]||!tk.valid[b]||!tk.valid[c]||tk.hcoll[b]||tk.hcoll[c]
                    ||((tk.env[a]|tk.env[b]|tk.env[c])&MovementFeatures.EXCLUDE)!=0)continue;
            if(!Float.isFinite(tk.speed[c])||tk.speed[c]<=0)continue;
            double dx=tk.x[c]-tk.x[b],dy=tk.y[c]-tk.y[b],dz=tk.z[c]-tk.z[b];
            if(Math.hypot(dx,dz)<=.015)continue;
            boolean using=(tk.env[b]&ENV_USING_ITEM)!=0&&(tk.env[c]&ENV_USING_ITEM)!=0;
            int item=useIndex>=0&&useIndex<s.useItem.n?s.useItem.a[useIndex]:s.priorUseItem;
            float use=blocks==null?Float.NaN:blocks.useMultiplier(item);
            boolean spear=blocks!=null&&blocks.spear(item);
            long useTime=useIndex>=0?s.useT.a[useIndex]:s.priorUseT;
            // Environment changes can precede queued input. An old item identity
            // cannot prove which hand is still active after release or a slot change.
            if(using&&(useTime==Long.MIN_VALUE||tk.t[c]-useTime>30000
                    ||released(s,useTime,tk.t[c])))continue;
            if(using&&st!=null&&tk.t[c]-useTime>1000&&!st.has(ST_USING_ITEM))continue;
            // Missing item identity cannot establish a slowing contract.
            if(using&&!Float.isFinite(use)&&!spear)continue;
            float gravity=st==null||st.gravity<=0?.08f:st.gravity;
            float jump=st==null||st.jumpStrength<=0?.42f:st.jumpStrength;
            double best=Double.POSITIVE_INFINITY,bestKey=Double.POSITIVE_INFINITY;
            float slipA=slip(blocks,tk.groundBlock[a]),slipB=slip(blocks,tk.groundBlock[b]);
            int frictionVariants=((tk.env[a]|tk.env[b])&ENV_ICE)!=0?3:1;
            for(int fv=0;fv<frictionVariants;fv++) {
                float fa=fv==0?slipA:fv==1?.98f:.989f,fb=fv==0?slipB:fv==1?.98f:.989f;
                float drag=tk.ground[a]?fa*.91f:.91f;
                double vx=zero((tk.x[b]-tk.x[a])*drag),vz=zero((tk.z[b]-tk.z[a])*drag);
                double vy=zero((tk.y[b]-tk.y[a]-gravity)*.98f);
                for(int y=0;y<2;y++) {
                    var r=residual(vx,vy,vz,dx,dy,dz,tk.ground[b],tk.yaw[y==0?c:b],tk.speed[c],fb,
                            gravity,jump,tk.input[c],tk.input[b],s.hasInput&&(s.protocol<0||s.protocol>=768),
                            using,use,spear,(tk.env[c]&ENV_SNEAKING)!=0);
                    best=Math.min(best,r.physical);bestKey=Math.min(bestKey,r.input);
                }
            }
            judged++;
            if(best>.0045) { offset++;out.moment("move.pred_offset",tk.t[c]); }
            if(s.hasInput&&(s.protocol<0||s.protocol>=768)) {
                keyJudged++;
                if(bestKey>.01) { inputOff++;out.moment("pred.input_offset",tk.t[c]); }
            }
            if(using&&!spear&&use<1) {
                slowJudged++;
                if(best>.0045) {noSlow++;out.moment("pred.noslow",tk.t[c]);}
            }
        }
        out.rate("move.pred_offset",offset,judged);
        out.rate("pred.input_offset",inputOff,keyJudged);
        out.rate("pred.noslow",noSlow,slowJudged);
        knockback(s,out,blocks);
    }
    private static boolean released(SessionData s,long use,long now) {
        if(PacketFeatures.anyIn(s.slotT,use+1,now))return true;
        for(int i=MovementFeatures.lowerBound(s.actionT.a,s.actionT.n,use+1);i<s.actionT.n&&s.actionT.a[i]<=now;i++)
            if(s.actionKind.a[i]==5||s.actionKind.a[i]==6)return true;
        return false;
    }
    private static float slip(MovementFeatures.BlockInfo blocks,int raw) {
        float v=blocks==null||raw<0?.6f:blocks.slipperiness(raw);
        return v>0&&Float.isFinite(v)?v:.6f;
    }
    /** Audit an impulse against an independent four-tick response trajectory. */
    static void knockback(SessionData s,FeatureSet out) {knockback(s,out,null);}
    static void knockback(SessionData s,FeatureSet out,MovementFeatures.BlockInfo blocks) {
        long judged=0,ignored=0;
        for(Impulse r:impulses(s,blocks)) if(r.judged) {
            judged++;
            if(r.ignored) {ignored++;out.moment("pred.kb_ignore",r.sent);}
        }
        out.rate("pred.kb_ignore",ignored,judged);
    }
    public record Impulse(long sent,long end,boolean judged,boolean ignored,double fraction,
                          double fullError,double fittedError,String reason) {}
    public static List<Impulse> impulses(SessionData s,MovementFeatures.BlockInfo blocks) {
        List<Impulse> results=new ArrayList<>();
        // Old movement-only protocols cannot distinguish queued packets from
        // physical ticks, nor constrain keyboard acceleration during an impulse.
        if(!s.hasInput||(s.protocol>=0&&s.protocol<768)||s.tickEndT.n<20||s.moveT.n<6)return results;
        var tk=MovementFeatures.byClientTick(s,new boolean[s.moveT.n]);
        int si=0;
        for(int v=0;v<s.velT.n;v++) {
            long sent=s.velT.a[v];double h=Math.hypot(s.velX.a[v],s.velZ.a[v]);
            if(h<=.18)continue;
            while(si+1<s.states.size()&&s.states.get(si+1).t<=sent)si++;
            var st=s.states.isEmpty()?null:s.states.get(si);
            long delay=st==null?0:Math.max(0,st.ping);
            int lo=MovementFeatures.lowerBound(s.moveT.a,s.moveT.n,sent-2000);
            for(int i=Math.max(1,lo);i<s.moveT.n&&s.moveT.a[i]<=sent+1500;i++)
                delay=Math.max(delay,s.moveT.a[i]-s.moveT.a[i-1]);
            for(int i=MovementFeatures.lowerBound(s.pongT.a,s.pongT.n,sent-10000);i<s.pongT.n&&s.pongT.a[i]<=sent+1500;i++)
                delay=Math.max(delay,s.pongRtt.a[i]);
            long end=sent+Math.min(2000,delay)+250;
            String reason=null;
            if(st==null||st.world==3||MovementFeatures.exemptMode(st)||st.has(ST_INVULNERABLE))reason="state";
            else if(end>s.lastT)reason="incomplete";
            else if(PacketFeatures.anyIn(s.tpSentT,sent-200,end+200)||PacketFeatures.anyIn(s.lifeT,sent-200,end+200)
                    ||PacketFeatures.anyIn(s.corrT,sent-200,end+200))reason="position reset";
            else if(!PacketFeatures.anyIn(s.damageT,sent-250,sent+250))reason="no damage";
            else if(v+1<s.velT.n&&s.velT.a[v+1]<=end)reason="replacement impulse";
            if(reason!=null) {results.add(new Impulse(sent,end,false,false,0,0,0,reason));continue;}
            int first=Math.max(2,MovementFeatures.lowerBound(tk.t,tk.n,sent-50));
            double full=Double.POSITIVE_INFINITY,best=Double.POSITIVE_INFINITY,fraction=1;
            boolean obstructed=false;int windows=0;
            for(int c=first;c<tk.n&&tk.t[c]<=end;c++) {
                if(!tk.valid[c]||tk.hcoll[c]||(tk.env[c]&MovementFeatures.EXCLUDE)!=0)obstructed=true;
                if(c+3>=tk.n||tk.t[c+3]>end+200)continue;
                boolean valid=true;
                for(int k=c-2;k<=c+3;k++)if(!tk.valid[k]||tk.hcoll[k]||(tk.env[k]&MovementFeatures.EXCLUDE)!=0)valid=false;
                if(!valid)continue;
                windows++;
                // One already integrated tick is possible when a velocity update
                // and queued movement straddle the client tick boundary.
                for(int phase=0;phase<4;phase++)for(int keys=0;keys<2;keys++)for(int angle=0;angle<2;angle++) {
                    double f=impulseError(s,tk,c,v,1,phase,keys,angle,blocks,st);
                    full=Math.min(full,f);
                    for(int q=0;q<=34;q++) {
                        double scale=q*.025;
                        double e=impulseError(s,tk,c,v,scale,phase,keys,angle,blocks,st);
                        if(e<best) {best=e;fraction=scale;}
                    }
                }
            }
            // A reduced response must fit a coherent vector trajectory, not
            // merely be shorter than the impulse in one delivery packet.
            boolean judged=windows>0&&!obstructed&&Double.isFinite(full)&&(full<=.12||best<.08);
            boolean ignored=judged&&full>.12&&best<.08&&full-best>.08&&fraction<=.85;
            results.add(new Impulse(sent,end,judged,ignored,full<=.12?1:fraction,
                    Double.isFinite(full)?full:0,Double.isFinite(best)?best:0,
                    judged?"trajectory":obstructed?"environment":windows>0?"unmodelled response":"tick coverage"));
        }
        return results;
    }
    private static double impulseError(SessionData s,MovementFeatures.Ticks tk,int c,int v,double scale,
                                       int phase,int keyShift,int yawShift,MovementFeatures.BlockInfo blocks,SessionData.State st) {
        double h=Math.hypot(s.velX.a[v],s.velZ.a[v]);
        double vx=s.velX.a[v]*scale,vy=s.velY.a[v]*scale,vz=s.velZ.a[v]*scale;
        if(s.velExplosion.n>v&&s.velExplosion.a[v]!=0) {
            float drag=tk.ground[c-2]?slip(blocks,tk.groundBlock[c-2])*.91f:.91f;
            vx+=(tk.x[c-1]-tk.x[c-2])*drag;vz+=(tk.z[c-1]-tk.z[c-2])*drag;
            vy=(tk.y[c-1]-tk.y[c-2]-.08f)*.98f+vy;
        }
        float gravity=st.gravity>0?st.gravity:.08f,jump=st.jumpStrength>0?st.jumpStrength:.42f;
        boolean onsetGround=tk.ground[c-1];
        if(phase>=2) {
            if(((tk.env[c-1]|tk.env[c])&(ENV_GROUND_BELOW|ENV_FLOOR_FACE))==0)return Double.POSITIVE_INFINITY;
            onsetGround=true;
        }
        if((phase&1)!=0) {
            float drag=onsetGround?slip(blocks,tk.groundBlock[c-1])*.91f:.91f;
            vx*=drag;vz*=drag;vy=(vy-gravity)*.98f;
        }
        double horizontal=0,vertical=0;
        boolean airborne=false;
        for(int k=c;k<c+4;k++) {
            int input=tk.input[k-keyShift];
            int forward=((input&IN_FORWARD)!=0?1:0)-((input&IN_BACK)!=0?1:0);
            int strafe=((input&IN_LEFT)!=0?1:0)-((input&IN_RIGHT)!=0?1:0);
            boolean sneak=(input&IN_SNEAK)!=0||(tk.env[k]&ENV_SNEAKING)!=0;
            boolean sprint=!sneak&&((input&IN_SPRINT)!=0||(tk.env[k]&ENV_SPRINTING)!=0);
            boolean using=(tk.env[k]&ENV_USING_ITEM)!=0;
            int useIndex=MovementFeatures.lowerBound(s.useT.a,s.useT.n,tk.t[k]+1)-1;
            int raw=useIndex>=0?s.useItem.a[useIndex]:s.priorUseItem;
            boolean spear=blocks!=null&&blocks.spear(raw);
            float use=using&&!spear&&blocks!=null?blocks.useMultiplier(raw):1;
            if(!Float.isFinite(use))return Double.POSITIVE_INFINITY;
            int mode=using&&!spear?3:sneak?2:sprint?1:0;
            float attr=Float.isFinite(tk.speed[k])&&tk.speed[k]>0?tk.speed[k]:st.speedAttr;
            if(attr<=0)attr=.1f;
            if(sprint)attr=Math.max(attr,.13f);
            var branch=new Branch(forward,strafe,mode,(input&IN_JUMP)!=0);
            var p=step(vx,vy,vz,k==c?onsetGround:tk.ground[k-1],tk.yaw[k-yawShift],attr,slip(blocks,tk.groundBlock[k-1]),
                    gravity,jump,branch,.3f,use*(using&&sneak?.3f:1),spear);
            double dx=tk.x[k]-tk.x[k-1]-p.x,dz=tk.z[k]-tk.z[k-1]-p.z;
            double dy=tk.y[k]-tk.y[k-1];
            horizontal+=dx*dx+dz*dz;
            // Vertical coherence strengthens partial suppression only in a
            // recorded rising flight. Ground contact cannot prove head clearance.
            if(!tk.ground[k]&&!tk.ground[k-1]&&dy>0) {vertical+=(dy-p.y)*(dy-p.y);airborne=true;}
            vx=p.nextX;vz=p.nextZ;vy=tk.ground[k]?0:p.nextY;
        }
        double verticalScale=Math.max(.1,Math.abs(s.velY.a[v])*.5);
        return Math.sqrt((horizontal/(h*h)+(airborne?vertical/(verticalScale*verticalScale):0))/4);
    }
}
