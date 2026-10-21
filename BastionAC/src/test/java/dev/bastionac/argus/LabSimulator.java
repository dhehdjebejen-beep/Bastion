package dev.bastionac.argus;

import dev.bastionac.argus.features.MiningFeatures;
import dev.bastionac.telemetry.SessionWriter;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static dev.bastionac.telemetry.TelemetryFormat.*;

/**
 * Argus Lab: one synthetic play session, written in the real telemetry format.
 *
 * <p>A session is a mix of the three things a survival player does — moving
 * around, fighting, digging — played by a {@link Profile} (ping, mouse
 * sensitivity, aim skill, click rhythm, play style) with zero or more
 * {@link Cheat}s switched on at some intensity. The physics is vanilla's
 * (flat ground, air friction 0.91, ground 0.6·0.91, sprint-jump boost), the
 * mouse turns in whole sensitivity steps, clicks land on client ticks, the
 * connection answers pings and keep-alives with its own latency — so an
 * honest profile produces what an honest client sends, and each cheat changes
 * exactly what that cheat changes.
 *
 * <p>The sessions are read back by the same {@code TelemetryReader} and
 * measured by the same feature code as a live case.
 */
final class LabSimulator {

    /** What a cheat module does and how hard (0 = off, 1 = blatant). */
    enum Cheat {
        AURA("KillAura: вычисленный прицел, рывки, ровные клики"),
        AURA_SMOOTH("KillAura с плавной наводкой и GCD-фиксом"),
        AIM_ASSIST("Aim assist: подтягивание прицела к цели"),
        REACH("Reach: удар дальше 3 блоков"),
        AUTOCLICKER("Автокликер: ровный ритм"),
        SPEED("Speed: разгон в воздухе"),
        SPEED_MULT("Speed ×: вся скорость чуть больше (уровень 0,5 = ×1,05)"),
        STRAFE("Strafe: боковой толчок в воздухе"),
        CLICK_TP("ClickTP: телепорт пешком на 3–7 блоков"),
        BHOP("BHop: прыжки без клавиши"),
        TIMER("Timer: тики чаще 20 в секунду"),
        BLINK("Blink: придержанное движение"),
        PING_SPOOF("PingSpoof: задержка keep-alive"),
        XRAY("X-ray: туннели к руде"),
        /** Not a cheat of its own: the others are on only in some fights (level 1 = 30 % of them). */
        TOGGLER("Тогглер: читы включаются на одну драку из нескольких");

        final String label;

        Cheat(String label) {
            this.label = label;
        }
    }

    /** A player: everything that makes two honest people play differently. */
    static final class Profile {
        long seed;
        int pingMs = 60;
        double jitterMs = 8;
        /** Vanilla mouse sensitivity, 0..1. */
        double sensitivity = 0.5;
        /** 0 = poor aim, 1 = very good aim (never computer-exact). */
        double aimSkill = 0.5;
        /** Mean clicks per second while fighting, and how irregular they are (lognormal σ). */
        double cps = 8, clickSigma = 0.35;
        /** Fraction of the session spent fighting / digging (the rest is moving around). */
        double fight = 0.35, dig = 0.25;
        int minutes = 8;
        /** Reach a player actually hits from (sword fights sit around 2.5–2.9). */
        double reach = 2.65;
        /** One tick in this many arrives late by 0.2–0.8 s (a lag spike). */
        int lagOneIn = 2000;

        double step() {
            double f = sensitivity * 0.6 + 0.2;
            return f * f * f * 8 * 0.15;
        }
    }

    final Profile p;
    final java.util.EnumMap<Cheat, Double> cheats = new java.util.EnumMap<>(Cheat.class);
    final Random rnd;
    final SessionWriter w;
    /** The rock for X-ray digging: a slab of stone with ore at a fixed rate. */
    final WorldSample rock;

    private long t = 1_000_000;
    /** Ticks to the next Blink burst — counted across the whole session, roaming or fighting. */
    private int blinkIn = 100;
    private double clientClock = 1_000_000;
    private double x, y = 64, z, vx, vy, vz;
    private boolean ground = true;
    private float yaw, pitch = 5f;
    private long nextPing, nextKeepAlive, nextState;
    private double timerBalance, timerWorst;
    private long lastStateTick;
    private int tick;

    LabSimulator(Profile p, java.util.Map<Cheat, Double> cheats, WorldSample rock) {
        this.p = p;
        this.cheats.putAll(cheats);
        this.rnd = new Random(p.seed);
        this.rock = rock;
        this.w = new SessionWriter("u" + p.seed, "Lab" + p.seed, t, "f", 1L << 40);
        w.header("{\"uuid\":\"u" + p.seed + "\",\"name\":\"Lab\",\"start\":" + t + "}");
        w.client(t, "vanilla", 774, "ru_ru", 12, 1, 127, 0, List.of());
        w.env(t, ENV_SPRINTING);
        w.ground(t, 1);
        w.speed(t, 0.13f);
        w.life(t, LIFE_WORLD, 0, x, y, z);
        nextPing = t + 2000;
        nextKeepAlive = t + 15_000;
        nextState = t + 1000;
    }

    double level(Cheat c) {
        Double v = cheats.get(c);
        if (v == null) return 0;
        // A toggler's modules are off outside the fights it chose.
        if (c != Cheat.TOGGLER && cheats.containsKey(Cheat.TOGGLER) && !toggledOn) return 0;
        return v;
    }

    /** Toggler: whether the modules are on right now, and how many fights were played with them. */
    private boolean toggledOn;
    private int toggledFights;

    boolean on(Cheat c) {
        return level(c) > 0;
    }

    /** Plays the whole session and returns the encoded telemetry. */
    byte[] play() {
        long start = t, end = t + p.minutes * 60_000L;
        Double toggle = cheats.get(Cheat.TOGGLER);
        while (t < end) {
            double r = rnd.nextDouble();
            toggledOn = false;
            if (r < p.fight) {
                if (toggle != null) {
                    // One fight in a few — and at least one before the session is out.
                    toggledOn = rnd.nextDouble() < 0.3 * toggle || (toggledFights == 0 && t > start + (end - start) / 2);
                    if (toggledOn) toggledFights++;
                }
                fight(200 + rnd.nextInt(400));
                toggledOn = false;
            }
            else if (r < p.fight + p.dig) dig(40 + rnd.nextInt(60));
            else roam(200 + rnd.nextInt(400));
        }
        return w.drain();
    }

    // ---------------------------------------------------------------- the clock

    /**
     * One client tick. The client's clock runs at 50 ms a tick (faster under
     * Timer); each packet reaches us that plus the network's jitter later. The
     * server's timer balance is real time minus 50 ms per tick received: an
     * honest client only wobbles around zero, a Timer drifts below it.
     */
    private void advance() {
        double timer = 1 + 0.25 * level(Cheat.TIMER);
        clientClock += 50 / timer;
        tick++;
        long arrive = Math.round(clientClock + Math.abs(rnd.nextGaussian()) * p.jitterMs * 0.3);
        if (rnd.nextInt(p.lagOneIn) == 0) arrive += 200 + rnd.nextInt(600);   // a lag spike
        arrive = Math.max(t + 1, arrive);
        clientClock = Math.max(clientClock, arrive - p.jitterMs * 2.0);
        timerBalance = Math.min(1000, timerBalance + (arrive - t) - 50);
        timerWorst = Math.min(timerWorst, timerBalance);
        t = arrive;
        network();
    }

    private void network() {
        if (t >= nextPing) {
            // A pong travels the same ordered connection as the moves: it is
            // read after the move that came before it, never inside a gap.
            w.pong(t, (int) Math.max(1, p.pingMs + rnd.nextGaussian() * p.jitterMs));
            nextPing = t + 2000;
        }
        if (t >= nextKeepAlive) {
            int extra = (int) (400 * level(Cheat.PING_SPOOF));
            w.keepAlive(t, (int) Math.max(1, p.pingMs + extra + rnd.nextGaussian() * p.jitterMs));
            nextKeepAlive = t + 15_000;
        }
        if (t >= nextState) {
            SessionWriter.State s = new SessionWriter.State();
            s.serverTick = lastStateTick += 20;
            s.msptAvg = 18;
            s.lastTickMs = 18;
            s.ping = p.pingMs;
            s.timerBalance = (float) timerBalance;
            s.timerWorst = (float) timerWorst;
            s.x = x;
            s.y = y;
            s.z = z;
            s.yaw = yaw;
            s.pitch = pitch;
            s.health = 20;
            s.food = 20;
            s.speedAttr = 0.1f;
            s.gravity = 0.08f;
            s.jumpStrength = 0.42f;
            s.stepHeight = 0.6f;
            w.state(t, s);
            timerWorst = timerBalance;
            nextState = t + 1000;
        }
    }

    // ---------------------------------------------------------------- moving

    /** Vanilla movement for one tick on flat ground; the cheats that bend it, bend it here. */
    private void move(boolean forward, boolean jumpKey) {
        move(forward, 0, jumpKey);
    }

    /**
     * @param strafe +1 left (A), −1 right (D), 0 none — vanilla's input vector
     *               (sideways, forward), normalised past length 1, times 0.98,
     *               turned by the yaw
     */
    private void move(boolean forward, int strafe, boolean jumpKey) {
        boolean bhop = on(Cheat.BHOP) && forward && rnd.nextDouble() < 0.6 * level(Cheat.BHOP) + 0.2;
        boolean jump = (jumpKey || bhop) && ground;
        int keys = (forward ? IN_FORWARD | IN_SPRINT : 0) | (jumpKey ? IN_JUMP : 0)
                | (strafe > 0 ? IN_LEFT : strafe < 0 ? IN_RIGHT : 0);
        w.input(t, keys);
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        boolean startGround = ground;
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        if (jump) {
            vy = 0.42;
            if (forward) {
                vx += fx * 0.2;
                vz += fz * 0.2;
            }
        }
        double slip = startGround ? 0.6 : 1.0;
        double airAccel = 0.026 * (1 + 1.6 * level(Cheat.SPEED));
        double accel = startGround ? 0.13 * 0.21600002 / (slip * slip * slip) : airAccel;
        double in = forward ? 1 : 0, side = strafe;
        double len = Math.sqrt(in * in + side * side);
        if (len > 1) {
            in /= len;
            side /= len;
        }
        if (len > 0) {
            double sin = Math.sin(r), cos = Math.cos(r);
            vx += (side * cos - in * sin) * accel * 0.98;
            vz += (in * cos + side * sin) * accel * 0.98;
        }
        if (!startGround && on(Cheat.STRAFE)) {
            double kick = 0.07 * level(Cheat.STRAFE);
            double dir = (tick / 40) % 2 == 0 ? 1 : -1;
            vx += fz * kick * dir;
            vz -= fx * kick * dir;
        }
        if (on(Cheat.SPEED_MULT)) {
            // The "subtle" speed: every tick's motion a few per cent longer, on the ground and in the air.
            double m = 1 + 0.1 * level(Cheat.SPEED_MULT);
            vx *= m;
            vz *= m;
        }
        x += vx;
        z += vz;
        double ny = y + vy;
        if (ny <= 64) {
            ny = 64;
            vy = 0;
            ground = true;
        } else {
            ground = false;
        }
        y = ny;
        double f = slip * 0.91;
        vx *= f;
        vz *= f;
        vy = (vy - 0.08) * 0.98;
    }

    private void sendMove() {
        w.move(t, true, x, y, z, true, yaw, pitch, ground, false);
    }

    private void endTick() {
        w.tickEnd(t + 1);
    }

    /** Walking, sprinting, jumping, looking around. */
    private void roam(int ticks) {
        boolean blink = on(Cheat.BLINK);
        for (int i = 0; i < ticks; i++) {
            advance();
            boolean forward = rnd.nextDouble() < 0.85;
            boolean jump = rnd.nextDouble() < 0.06;
            if (i % 60 == 0) turn(rnd.nextGaussian() * 40, rnd.nextGaussian() * 8);
            else if (rnd.nextDouble() < 0.3) turn(rnd.nextGaussian() * 3, rnd.nextGaussian() * 1.5);
            move(forward, jump);
            if (on(Cheat.CLICK_TP) && rnd.nextDouble() < 0.004 * level(Cheat.CLICK_TP)) {
                // One packet 3–7 blocks ahead: the click-teleport of a hacked client.
                double d = 3 + 4 * rnd.nextDouble(), r = Math.toRadians(yaw);
                x += -Math.sin(r) * d;
                z += Math.cos(r) * d;
            }
            if (blink && --blinkIn <= 0) {
                blinkBurst();
                continue;
            }
            sendMove();
            endTick();
        }
    }

    /**
     * Blink: the client keeps moving but sends nothing — no moves, no tick ends —
     * while it still answers our pings, then flushes the held moves at once.
     */
    private void blinkBurst() {
        blinkIn = (int) (300 / Math.max(0.2, level(Cheat.BLINK))) + rnd.nextInt(200);
        int held = 15 + (int) (25 * level(Cheat.BLINK));
        double[][] path = new double[held][];
        for (int k = 0; k < held; k++) {
            clientClock += 50;
            tick++;
            if (k % 8 == 4) w.pong(Math.round(clientClock), (int) Math.max(1, p.pingMs + rnd.nextGaussian() * p.jitterMs));
            move(true, false);
            path[k] = new double[]{x, y, z};
        }
        long now = Math.round(clientClock);
        timerBalance = Math.min(1000, timerBalance + (now - t) - held * 50L);
        t = now;
        nextPing = t + 2000;
        for (double[] q : path) w.move(t, true, q[0], q[1], q[2], true, yaw, pitch, ground, false);
        endTick();
    }

    /** A mouse turn: whole sensitivity steps, as a hand on a mouse produces. */
    private void turn(double dYaw, double dPitch) {
        double s = p.step();
        yaw += (float) (Math.round(dYaw / s) * s);
        pitch = (float) Math.max(-90, Math.min(90, pitch + Math.round(dPitch / s) * s));
    }

    // ---------------------------------------------------------------- fighting

    /**
     * A duel. The target is a body in the world: it circles the player at the
     * distance the fight is at, jinks, closes in and backs off; the player
     * sprints at it and strafes. Everyone — honest or not — sees the target a
     * round trip plus the client's interpolation late (that is what the
     * server's clock makes of it), from where it stands now: its own strafing
     * it allows for at once. For the target's moves a hand needs its reaction
     * time on top and makes up part of it by leading; a module answers at once.
     */
    private void fight(int ticks) {
        boolean aura = on(Cheat.AURA), smooth = on(Cheat.AURA_SMOOTH), assist = on(Cheat.AIM_ASSIST);
        double fakeStep = 0.0013 + rnd.nextDouble() * 0.2;   // a GCD-fix module's chosen "sensitivity"
        long nextClick = t + 200 + rnd.nextInt(300);
        int net = (int) Math.round((p.pingMs + 100 + rnd.nextGaussian() * 15) / 50.0);
        int reaction = 3 + (int) Math.round((1 - p.aimSkill) * 2 + rnd.nextDouble());
        double lead = 0.5 + 0.45 * p.aimSkill;   // how much of its own lag a hand makes up by leading
        // A Reach user fights near the edge of the reach it bought, not at its old distance.
        double prefer = on(Cheat.REACH)
                ? Math.max(Math.min(2.95, p.reach), 3.0 + 0.9 * level(Cheat.REACH) * 0.85 - 0.2)
                : Math.min(2.95, p.reach);
        double start = Math.toRadians(yaw + rnd.nextGaussian() * 30);
        double tx = x - Math.sin(start) * (prefer + 0.3), tz = z + Math.cos(start) * (prefer + 0.3);
        double orbit = (rnd.nextBoolean() ? 1 : -1) * (0.06 + rnd.nextDouble() * 0.14);   // blocks a tick round the player
        double gapOff = 0, targetPitch = 4 + rnd.nextGaussian() * 3;
        java.util.Arrays.fill(histX, tx);
        java.util.Arrays.fill(histZ, tz);
        int strafe = 0, strafeLeft = 0;
        for (int i = 0; i < ticks; i++) {
            advance();
            // The target: round the player, holding the distance it wants.
            double rx = tx - x, rz = tz - z, d = Math.max(0.3, Math.hypot(rx, rz));
            double ux = rx / d, uz = rz / d;
            if (rnd.nextInt(40) == 0) orbit = -orbit;                // a jink: the strafe reverses
            gapOff = gapOff * 0.97 + rnd.nextGaussian() * 0.04;
            double pull = Math.max(-0.3, Math.min(0.3, (prefer + 0.3 + gapOff - d) * 0.6));
            tx += -uz * orbit + ux * pull + rnd.nextGaussian() * 0.03;
            tz += ux * orbit + uz * pull + rnd.nextGaussian() * 0.03;
            targetPitch += rnd.nextGaussian() * 0.3;
            histX[++hi % histX.length] = tx;
            histZ[hi % histZ.length] = tz;

            // What the player sees: the target net ticks old, from where it stands now.
            double seenNow = seenYaw(net), seenOld = seenYaw(net + reaction);
            double seenVel = wrap(seenOld - seenYaw(net + reaction + 4)) / 4.0;
            float dy, dp;
            if (aura && !smooth) {
                // Computed: straight onto the target it sees; in exact floats.
                dy = (float) wrap(seenNow - yaw);
                dp = (float) (targetPitch - pitch);
            } else if (smooth) {
                // Interpolated towards the target and snapped to a fake sensitivity grid.
                double k = 0.35 + 0.4 * level(Cheat.AURA_SMOOTH);
                double wantY = wrap(seenNow - yaw) * k + rnd.nextGaussian() * 0.15;
                double wantP = (targetPitch - pitch) * k;
                dy = (float) (Math.round(wantY / fakeStep) * fakeStep);
                dp = (float) (Math.round(wantP / fakeStep) * fakeStep);
            } else {
                // A hand: towards where it saw the target a reaction ago, led a little, with noise.
                double aim = seenOld + seenVel * reaction * lead;
                double gain = 0.25 + 0.35 * p.aimSkill + 0.15 * rnd.nextDouble();
                double noise = (1.6 - p.aimSkill) * 1.4;
                double wantY = wrap(aim - yaw) * gain + rnd.nextGaussian() * noise;
                double wantP = (targetPitch - pitch) * gain * 0.5 + rnd.nextGaussian() * noise * 0.4;
                if (assist) {
                    // The module pulls the hand towards the target as it is seen now.
                    double pullA = 0.25 + 0.5 * level(Cheat.AIM_ASSIST);
                    wantY += wrap(seenNow - yaw - wantY) * pullA;
                    wantP += (targetPitch - pitch - wantP) * pullA;
                }
                double st = p.step();
                dy = (float) (Math.round(wantY / st) * st);
                dp = (float) (Math.round(wantP / st) * st);
            }
            yaw += dy;
            pitch = (float) Math.max(-90, Math.min(90, pitch + dp));

            if (on(Cheat.BLINK) && --blinkIn <= 0) blinkBurst();

            // Sprinting at it, strafing left and right.
            if (--strafeLeft <= 0) {
                strafe = rnd.nextInt(3) - 1;
                strafeLeft = 8 + rnd.nextInt(30);
            }
            // Pressing in only when the target is farther than this player likes to hit from.
            boolean press = Math.hypot(tx - x, tz - z) - 0.3 > prefer - 0.1 ? rnd.nextDouble() < 0.9 : rnd.nextDouble() < 0.2;
            move(press, strafe, rnd.nextDouble() < 0.12);
            sendMove();
            double horiz = Math.hypot(tx - x, tz - z);
            double ty = -Math.tan(Math.toRadians(targetPitch)) * horiz;   // centre, from the eyes
            w.target(t, 7, (float) (tx - x), (float) ty, (float) (tz - z), 0.3f, 1.8f);

            if (t >= nextClick) {
                // The client swings at the target it sees — a round trip plus
                // its interpolation old — and a vanilla client only does when
                // that is within 3 blocks. The server measures to where the
                // target is now.
                int ago = (int) Math.round((p.pingMs + 100 * (0.6 + 0.6 * rnd.nextDouble())) / 50.0);
                int j = Math.max(0, hi - ago) % histX.length;
                double seenGap = Math.hypot(histX[j] - x, histZ[j] - z) - 0.3;
                nextClick = t + clickGap();
                // Nor does it hit what the crosshair is not on (a module that
                // aims for itself always is).
                double off = Math.abs(wrap(seenYaw(net) - yaw));
                double half = Math.toDegrees(Math.atan2(0.45, Math.max(0.5, seenGap + 0.3)));
                if (seenGap > 3.0 + 0.9 * level(Cheat.REACH) || (!aura && off > half)) {
                    endTick();   // swung at air: no attack packet
                    continue;
                }
                SessionWriter.Attack a = new SessionWriter.Attack();
                a.targetId = 7;
                a.flags = SessionWriter.Attack.F_PLAYER | SessionWriter.Attack.F_SPRINTING;
                a.distance = (float) Math.max(0.5, horiz - 0.3 + rnd.nextGaussian() * 0.02);
                a.eyeX = x;
                a.eyeY = y + 1.62;
                a.eyeZ = z;
                a.cx = tx;
                a.cy = y + 1.62 + ty;
                a.cz = tz;
                // Measured on the server, against where the target is now — for
                // a module too, which aimed at where it saw the target.
                double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
                double lx = -Math.sin(yr) * Math.cos(pr), ly = -Math.sin(pr), lz = Math.cos(yr) * Math.cos(pr);
                double cx = tx - x, cy = ty, cz = tz - z, cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
                a.aimError = (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (lx * cx + ly * cy + lz * cz) / cl))));
                a.yaw = yaw;
                a.pitch = pitch;
                a.cooldown = 1f;
                a.ping = p.pingMs;
                w.attack(t, a);
            }
            endTick();
        }
    }

    private final double[] histX = new double[64], histZ = new double[64];
    private int hi;

    /** The yaw from where the player stands now to where the target was {@code ago} ticks ago. */
    private double seenYaw(int ago) {
        int j = Math.max(0, hi - ago) % histX.length;
        return Math.toDegrees(Math.atan2(-(histX[j] - x), histZ[j] - z));
    }

    /** Milliseconds to the next click. */
    private long clickGap() {
        if (on(Cheat.AUTOCLICKER) || on(Cheat.AURA)) {
            double cps = 10 + 4 * Math.max(level(Cheat.AUTOCLICKER), level(Cheat.AURA));
            double spread = 0.12 * (1.2 - Math.max(level(Cheat.AUTOCLICKER), level(Cheat.AURA)));
            long g = Math.round(1000 / cps * (1 + (rnd.nextDouble() - 0.5) * spread));
            if (rnd.nextInt(30) == 0) g += 800;
            return g;
        }
        double mean = Math.log(1000 / p.cps) - p.clickSigma * p.clickSigma / 2;
        long g = Math.round(Math.exp(mean + rnd.nextGaussian() * p.clickSigma));
        if (rnd.nextInt(20) == 0) g += 600 + rnd.nextInt(1200);   // a pause between flurries
        return Math.max(40, g);
    }

    static double wrap(double d) {
        d %= 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    // ---------------------------------------------------------------- digging

    private int tunnelX, tunnelZ = 4;
    private final Set<Long> dug = new HashSet<>();

    /** A branch mine through the rock sample; X-ray detours to the ore it can see. */
    private void dig(int blocks) {
        if (rock == null) {
            roam(blocks * 6);
            return;
        }
        double xray = level(Cheat.XRAY);
        for (int b = 0; b < blocks && tunnelX < rock.sx - 2; b++) {
            digBlock(tunnelX, 10, tunnelZ);
            digBlock(tunnelX, 11, tunnelZ);
            if (xray > 0) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = -2; dy <= 3; dy++) {
                        if (dz == 0) continue;
                        int c = rock.get(tunnelX, 10 + dy, tunnelZ + dz);
                        if (!WorldSample.ore(c) || rnd.nextDouble() > xray) continue;
                        int st = dz > 0 ? 1 : -1;
                        for (int zz = tunnelZ + st; zz != tunnelZ + dz + st; zz += st) digBlock(tunnelX, 10 + dy, zz);
                    }
                }
            }
            tunnelX++;
        }
        if (tunnelX >= rock.sx - 2) {
            tunnelX = 0;
            tunnelZ += 8;
            if (tunnelZ > rock.sz - 5) tunnelZ = 4;
        }
    }

    private void digBlock(int bx, int by, int bz) {
        long pos = MiningFeatures.pack(bx, by, bz);
        if (!dug.add(pos)) return;
        int cat = rock.get(bx, by, bz);
        for (int k = 0; k < 6; k++) {   // ~0.3 s per block, the player keeps ticking
            advance();
            move(false, false);
            sendMove();
            endTick();
        }
        w.action(t, 0, pos, 1, 0, cat, 5f, 0, 1);
        w.broken(t + 1, pos, cat, 1);
    }

    /** A slab of stone with diamonds and other ore at fixed rates (shared by every miner of a corpus). */
    static WorldSample rock(long seed) {
        WorldSample w = new WorldSample(0, 0, 0, 160, 22, 48);
        Random r = new Random(seed);
        for (int i = 0; i < w.cells.length; i++) {
            int roll = r.nextInt(1000);
            w.cells[i] = (byte) (roll < 4 ? CTX_DIAMOND : roll < 25 ? CTX_ORE : CTX_STONE);
        }
        return w;
    }
}
