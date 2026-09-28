package dev.bastionac.core;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * Per-connection anti-cheat state. Mutated only on the server thread, except
 * the velocity-allowance fields which the network thread may touch (volatile,
 * benign races).
 */
public final class PlayerData {
    public static final int TIMER_WINDOW = 30;

    public final UUID uuid;
    public final String name;
    public final VlTracker vl = new VlTracker();

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ------------------------------------------------------------------
    // Exemptions
    // ------------------------------------------------------------------
    public long joinGraceUntil;
    public long respawnGraceUntil;
    /**
     * Another mod announced it is moving this player on purpose (the duel
     * arena, in and out). BigMove re-anchors instead of flagging until then.
     */
    public long serverMoveGraceUntil;
    public long vehicleGraceUntil;
    public long elytraGraceUntil;
    public long riptideGraceUntil;
    public long bounceGraceUntil;

    public volatile long velocityGraceUntil;
    public volatile double velocityBudget;

    public World lastWorld;

    /** Set once the player is being kicked, to stop piling on more checks. */
    public volatile boolean punished;
    /** Set right before the anti-cheat disconnects the player itself, so the disconnect hook can tell our kick from a dodge. */
    public volatile boolean kickedByAc;
    /** Server tick of the last game-mode change (PacketGuard grace for in-flight creative packets). */
    public volatile long gameModeChangedTick = -1_000_000L;

    // ---------------- NoFall by outcome ----------------
    /** Server tick at which vanilla last tried to apply fall damage to this player. */
    public volatile long lastFallDamageTick = -1_000_000L;
    /** Server tick of the last accepted movement packet (fall allowance scales with the gap). */
    public long lastMoveTick = -1_000_000L;
    /** True when the previous packet's position rested on real block support. */
    public boolean lastEnvSupport = true;
    /** Landing awaiting a damage verdict: the tick it happened, or -1. */
    public long pendingFallTick = -1;
    /** Height of the descent that landing ended. */
    public double pendingFallDescent;
    public double noFallBuffer;

    // ---------------- vehicle movement (BoatFly / VehicleSpeed) ----------------
    public boolean vehicleInit;
    public double lastVehX, lastVehY, lastVehZ;
    public int lastVehicleId = -1;
    public double vehicleBuffer;
    public double vehicleAirBuffer;
    public int vehicleAirTicks;
    /** Tick the player mounted; physics needs a moment to settle after boarding. */
    public long vehicleSinceTick = -1_000_000L;

    // ---------------- air place ----------------
    public double airPlaceBuffer;

    // ---------------- inventory move ----------------
    /** Server tick of the last inventory click. */
    public long lastSlotClickTick = -1_000_000L;
    /** Consecutive ticks with both an inventory click and real movement. */
    public double invMoveBuffer;

    // ---------------- packet order (one move per client tick) ----------------
    public int movesThisClientTick;
    public double packetOrderBuffer;
    /** True once the client has proven it sends ClientTickEnd at all (1.21.2+ vanilla does). */
    public boolean sendsTickEnd;
    /** Server Y before the first position packet of the current client tick (NaN until one arrives). */
    public double tickBaseY = Double.NaN;
    /** Y offsets of this client tick's position packets from {@link #tickBaseY}, in arrival order. */
    public final double[] tickDys = new double[8];
    public int tickDyCount;
    /** Position packets in this client tick that did not move at all (MaceDMG/ArrowDMG pad the tick with them). */
    public int zeroMovesThisClientTick;

    // ---------------- aim (hitbox-centre lock, snap-back) ----------------
    /** Last move samples, newest first: position claimed by the packet and the rotation after it. */
    public final double[] sampleX = new double[3], sampleY = new double[3], sampleZ = new double[3];
    public int samples;
    /** Last rotation-bearing samples, newest first. */
    public final float[] rotYaw = new float[3], rotPitch = new float[3];
    public int rotSamples;
    public double aimBuffer;
    public double snapBuffer;
    /** Recent attacks: true when the look went through the target's hitbox centre. */
    public final boolean[] aimRing = new boolean[8];
    public int aimRingPos, aimRingFill;
    /** A flick to the target's centre is waiting for the next rotation to see whether it snaps back. */
    public boolean snapPending;
    /** The previous judged attack: its look and the target's centre, to tell tracking from a parked crosshair. */
    public boolean hasLastAim;
    public float lastAimYaw, lastAimPitch;
    public double lastAimCx, lastAimCy, lastAimCz;
    public float snapPreYaw, snapPrePitch;
    public double snapAngle, snapErr;
    public long snapTick;

    // ---------------- placement point ----------------
    /** Consecutive block clicks exactly at the geometric centre of the clicked face. */
    public int placeCenterStreak;

    // ---------------- movement signatures (Wurst & co.) ----------------
    public double jesusBuffer;
    public int ladderFastStreak;
    public int webFastStreak;
    /** Position packets in a row claiming air while standing still on a floor (AntiHunger). */
    public int falseAirStreak;
    /** The take-off height a HighJump was already reported for, so one jump flags once. */
    public double highJumpBase = Double.NaN;
    public long lastBadRotationFlagTick = -1_000_000L;

    // ---------------- auto totem ----------------
    /** Totems this player has used, as the server's statistic last read. */
    public int totemsUsed = -1;
    /** When the last totem popped (ms), or -1. */
    public long totemPopMs = -1;
    public boolean offhandTotemBeforeClick;
    public double autoTotemBuffer;

    // ---------------- client identity ----------------
    /** Brand string from the minecraft:brand payload, lower-case; null until it arrives. */
    public volatile String brand;
    /** Tick at which the brand/channel consistency check should run (once, a few seconds after join). */
    public long brandCheckTick = -1;
    public boolean brandChecked;

    // Server-initiated teleport handshake: while pending, vanilla ignores the
    // client's movement, so checks pause without giving cheats a window.
    public boolean teleportPending;
    public double tpX, tpY, tpZ;
    public long teleportPendingSince;

    /** Full exemption that pauses every check (join/respawn/teleport). */
    public boolean isFullyExempt(long tick) {
        return tick < joinGraceUntil || tick < respawnGraceUntil || teleportPending;
    }

    /** Movement-only exemption (vehicle, elytra, riptide and their tails). */
    public boolean isMovementExempt(long tick) {
        return tick < vehicleGraceUntil || tick < elytraGraceUntil || tick < riptideGraceUntil;
    }

    /**
     * Transition-only vehicle grace: armed once at the mount/dismount instant
     * (dismount-fling protection), never extended while the state persists.
     * Sustained riding is instead covered by the server-authoritative vehicle
     * physics: BigMove already accepts any jump the server itself made, and
     * speed while mounted is the vehicle's own movement, not the rider's.
     */
    private boolean wasOnVehicle;
    public void noteVehicleState(boolean onVehicle, long tick) {
        if (onVehicle != wasOnVehicle) {
            wasOnVehicle = onVehicle;
            vehicleGraceUntil = tick + 20;
        }
    }

    /** Same policy for elytra: grace at the glide start, not while gliding. */
    private boolean wasGliding;
    public void noteGlideState(boolean gliding, long tick) {
        if (gliding != wasGliding) {
            wasGliding = gliding;
            elytraGraceUntil = tick + 20;
        }
    }

    public void beginTeleport(double x, double y, double z, long tick) {
        teleportPending = true;
        tpX = x;
        tpY = y;
        tpZ = z;
        teleportPendingSince = tick;
        // A teleport repositions the player far outside any knockback window, so an
        // armed Anti-KB transaction would measure the teleport itself as "no
        // knockback movement" and convict. Drop it; the next knockback re-arms.
        clearKbVerify();
    }

    /** Resets movement tracking to the given point (after teleport confirm). */
    public void resetMovement(double x, double y, double z) {
        moveInitialized = true;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastDy = 0;
        fallPeakY = y;
        airTicks = 0;
        hoverStreak = 0;
        glideStreak = 0;
        speedBuffer = 0;
        groundSpeedBuffer = 0;
        flyBuffer = 0;
        groundBuffer = 0;
        spiderBuffer = 0;
        climbBaseY = y;
        resetSpeedSamples();
        lastSupport = true;
        // Seed a valid setback anchor at the reset point (join / respawn /
        // teleport-confirm / world-change / resync are all server-authoritative
        // positions). Ground ticks refine it to the last solid footing. Without
        // this the anchor stayed "invalid" for anyone who took off from air/water,
        // and a Fly setback then teleported them to their CURRENT (flying) spot —
        // a no-op that let the flight continue.
        anchorValid = true;
        anchorX = x;
        anchorY = y;
        anchorZ = z;
        // Movement was re-anchored (join/respawn/teleport/setback): any armed
        // Anti-KB transaction now measures from a stale anchor and must restart.
        clearKbVerify();
    }

    // ------------------------------------------------------------------
    // GCD (aim-grid) analysis
    // ------------------------------------------------------------------
    /** Previous absolute rotation (for delta computation in the check hook). */
    public float lastYawDelta2, lastPitchDelta2;
    public boolean hasLastRotation;
    /** Rolling GCD evidence window. */
    public double gcdOffGridRatio;
    public int gcdSamples;
    public int gcdOffGrid;
    public double gcdBuffer;

    // Ring of |yaw|+|pitch| rotation deltas, oldest discarded, for the GCD
    // window. Fixed 40 samples — enough grid evidence, bounded memory.
    private final float[] rotDeltaRing = new float[40];
    private int rotDeltaIdx;
    private int rotDeltaCount;

    /** Absolute rotation of the previous packet (set by the move hook). */
    public float prevYaw, prevPitch;

    /** Records one rotation delta pair into the GCD window. */
    public void pushRotationDelta(float dy, float dp) {
        // Two floats per slot would need a second ring; the GCD treats yaw
        // and pitch deltas as one population (they share the sensitivity
        // step), so interleave both into the single ring.
        rotDeltaRing[rotDeltaIdx] = Math.abs(dy);
        rotDeltaIdx = (rotDeltaIdx + 1) % rotDeltaRing.length;
        if (rotDeltaCount < rotDeltaRing.length) rotDeltaCount++;
        rotDeltaRing[rotDeltaIdx] = Math.abs(dp);
        rotDeltaIdx = (rotDeltaIdx + 1) % rotDeltaRing.length;
        if (rotDeltaCount < rotDeltaRing.length) rotDeltaCount++;
        gcdSamples = rotDeltaCount;
    }

    /** Snapshot of the collected rotation deltas (degrees). */
    public double[] rotationDeltas() {
        double[] out = new double[rotDeltaCount];
        for (int i = 0; i < rotDeltaCount; i++) {
            out[i] = rotDeltaRing[i];
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Movement
    // ------------------------------------------------------------------
    public boolean moveInitialized;
    public double lastX, lastY, lastZ;
    public double lastDy;
    public boolean lastSupport = true;
    public int airTicks;
    public int hoverStreak;
    public int glideStreak;
    public int iceMemoryTicks;
    /** Highest Y reached during the current fall — for NoFall damage. */
    public double fallPeakY;

    public double speedBuffer;
    public double groundSpeedBuffer;
    public double flyBuffer;
    public double groundBuffer;
    public double spiderBuffer;
    /** Y at the last moment the player had legal support (ground/climbable/liquid);
     *  Spider compares current height against it — a jump can only gain ~1.25 blocks. */
    public double climbBaseY;

    // Sliding window of horizontal speed for the sustained-average Speed check.
    private final double[] speedSamples = new double[20];
    private int speedSampleIdx;
    private int speedSampleCount;
    private double speedSampleSum;

    /** Records a horizontal-distance sample and returns the window average. */
    public double pushSpeedSampleAndAvg(double hDist) {
        if (speedSampleCount == speedSamples.length) {
            speedSampleSum -= speedSamples[speedSampleIdx];
        } else {
            speedSampleCount++;
        }
        speedSamples[speedSampleIdx] = hDist;
        speedSampleSum += hDist;
        speedSampleIdx = (speedSampleIdx + 1) % speedSamples.length;
        return speedSampleSum / speedSampleCount;
    }

    public void resetSpeedSamples() {
        speedSampleIdx = 0;
        speedSampleCount = 0;
        speedSampleSum = 0;
    }

    public boolean anchorValid;
    public double anchorX, anchorY, anchorZ;
    public float anchorYaw, anchorPitch;
    public long lastSetbackTick = -1000000L;

    /** Timestamps (ms) of recent movement packets, for the Timer check. */
    private final long[] moveTimes = new long[TIMER_WINDOW];
    private int moveIdx;
    private int moveCount;

    public void recordMovePacket(long nowMillis) {
        moveTimes[moveIdx] = nowMillis;
        moveIdx = (moveIdx + 1) % TIMER_WINDOW;
        if (moveCount < TIMER_WINDOW) moveCount++;
    }

    /** Milliseconds spanned by the last {@link #TIMER_WINDOW} packets, or -1. */
    public long movePacketSpan(long nowMillis) {
        if (moveCount < TIMER_WINDOW) return -1;
        long oldest = moveTimes[moveIdx];
        return nowMillis - oldest;
    }

    public void resetMovePackets() {
        moveCount = 0;
        moveIdx = 0;
    }

    // ------------------------------------------------------------------
    // Combat
    // ------------------------------------------------------------------
    private final long[] attackTimes = new long[64];
    private int attackIdx;
    private int attackCount;

    public int recordAttackAndCountLastSecond(long nowMillis) {
        attackTimes[attackIdx] = nowMillis;
        attackIdx = (attackIdx + 1) % attackTimes.length;
        if (attackCount < attackTimes.length) attackCount++;
        int n = 0;
        for (int i = 0; i < attackCount; i++) {
            if (nowMillis - attackTimes[i] <= 1000) n++;
        }
        return n;
    }

    /** Rate-limits CPS violations so a locked-out clicker cannot flag 20x/second. */
    public long lastCpsFlagMs;

    public long lastSwingTick = -1000000L;
    public double noSwingBuffer;
    public int useAttackStreak;
    public double reachBuffer;
    public double angleBuffer;
    public double wallsBuffer;

    // Swing packets, counted the same way attacks are: NoSwing compares the two
    // rates instead of asking "was there a swing recently", which a single
    // swing per second used to satisfy forever.
    private final long[] swingTimes = new long[64];
    private int swingIdx;
    private int swingCount;

    public void recordSwing(long nowMillis) {
        swingTimes[swingIdx] = nowMillis;
        swingIdx = (swingIdx + 1) % swingTimes.length;
        if (swingCount < swingTimes.length) swingCount++;
    }

    public int countSwingsLastSecond(long nowMillis) {
        int n = 0;
        for (int i = 0; i < swingCount; i++) {
            if (nowMillis - swingTimes[i] <= 1000) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Rhythm heuristics (autoclicker / macro detection)
    // ------------------------------------------------------------------
    /**
     * Attack spacing. 16 gaps ≈ 1.3 s at 12 CPS. It used to take 24 gaps, and
     * with three windows needed per flag and three flags per alert that was
     * over 200 uninterrupted clicks at a steady rate before staff heard
     * anything — long enough that nobody ever saw this check fire.
     */
    public final RhythmTracker attackRhythm = new RhythmTracker(16, 1500);
    /** Block place/break spacing. Slower and burstier, so a wider pause window. */
    public final RhythmTracker actionRhythm = new RhythmTracker(16, 3000);
    public double autoClickBuffer;
    public double autoActionBuffer;

    /** Tick on which the last attack was ruled a violation — read by the damage veto. */
    public volatile long attackVetoTick = Long.MIN_VALUE;

    /**
     * One-shot contextual note for the next ordinary alert record. It is populated
     * by a shadow-only path such as BigMove and consumed when the alert is persisted.
     */
    private String shadowDetail;

    public void setShadowDetail(String value) {
        shadowDetail = value;
    }

    public String consumeShadowDetail() {
        String value = shadowDetail;
        shadowDetail = null;
        return value;
    }

    public void clearShadowDetail() {
        shadowDetail = null;
    }

    private final int[] recentTargetIds = new int[8];
    private final long[] recentTargetTicks = new long[8];
    private int targetIdx;

    /** Records the target and returns distinct targets hit in the window. */
    public int recordTargetAndCountDistinct(int entityId, long tick, int windowTicks) {
        recentTargetIds[targetIdx] = entityId;
        recentTargetTicks[targetIdx] = tick;
        targetIdx = (targetIdx + 1) % recentTargetIds.length;
        int distinct = 0;
        int[] seen = new int[recentTargetIds.length];
        for (int i = 0; i < recentTargetIds.length; i++) {
            if (tick - recentTargetTicks[i] > windowTicks || recentTargetTicks[i] == 0) continue;
            int id = recentTargetIds[i];
            boolean dup = false;
            for (int j = 0; j < distinct; j++) {
                if (seen[j] == id) {
                    dup = true;
                    break;
                }
            }
            if (!dup) seen[distinct++] = id;
        }
        return distinct;
    }

    // ------------------------------------------------------------------
    // Anti-KB (velocity verification)
    // ------------------------------------------------------------------
    /**
     * One outstanding knockback transaction. When the server sends a velocity
     * packet we anchor the player's position and integrate horizontal
     * displacement over {@code verifyTicks} ticks, then compare it against the
     * minimum the impulse must produce. A cheat that suppresses or scales
     * knockback falls short; genuine lag only delays the start, not the total.
     */
    public boolean kbPending;
    public double kbAnchorX, kbAnchorZ;
    /** Unit direction of the impulse — used to look for a wall in its path. */
    public double kbDirX, kbDirZ;
    public double kbExpectedMin;
    public long kbDeadlineTick;
    /** Consecutive suppressed transactions — one lag spike must not convict. */
    public int kbFailStreak;
    /**
     * Set while the window is open if anything made the impulse physically
     * un-travelable: a wall the player was thrown into, water/lava, a cobweb,
     * a climbable. The transaction is then dropped instead of counted as a
     * failure — being cornered against a block is ordinary PvP, not Velocity.
     */
    public boolean kbBlocked;

    public void beginKbVerify(double x, double z, double dirX, double dirZ,
                              double expectedMin, long deadlineTick) {
        kbPending = true;
        kbBlocked = false;
        kbAnchorX = x;
        kbAnchorZ = z;
        kbDirX = dirX;
        kbDirZ = dirZ;
        kbExpectedMin = expectedMin;
        kbDeadlineTick = deadlineTick;
    }

    public void clearKbVerify() {
        kbPending = false;
        kbBlocked = false;
        kbExpectedMin = 0;
    }

    // ------------------------------------------------------------------
    // Criticals (a claimed fall the server's own collision scan contradicts)
    // ------------------------------------------------------------------
    /** Vertical delta of the last position packet. */
    public double lastDy1;
    /** Confirmed-violation buffer so one lag-batched jump never convicts. */
    public double critBuffer;

    // ------------------------------------------------------------------
    // Blink (token bucket + inter-arrival-time variance)
    // ------------------------------------------------------------------
    /** Token-bucket balance: +1 per 50ms of real time, -1 per move packet. */
    public double blinkTokens;
    /** Real time (ms) the bucket was last refilled. */
    public long blinkLastRefillMs;
    /** Confirmed Blink bursts in a row — one OS-level flush must not convict. */
    public int blinkStreak;
    /**
     * Arrival timestamps of the most recent move packets, for the IAT-variance
     * verdict. A RING: it always holds the newest {@code burstTimes.length}
     * arrivals. It used to be a fill-once array that stopped recording at 64 and
     * was only emptied when a verdict fired, so by the time a burst actually
     * arrived the variance was measured over the first three seconds of the
     * player's session instead of over the burst — the window the check reads
     * has to be the window the check is judging.
     */
    private final long[] burstTimes = new long[64];
    private int burstIdx;
    private int burstCount;

    /** Records a move-packet arrival into the rolling window. */
    public void recordBurstPacket(long nowMillis) {
        burstTimes[burstIdx] = nowMillis;
        burstIdx = (burstIdx + 1) % burstTimes.length;
        if (burstCount < burstTimes.length) burstCount++;
    }

    /** IAT standard deviation over every recorded arrival. */
    public double burstIatStd() {
        return burstIatStd(burstTimes.length);
    }

    /**
     * Standard deviation of the inter-arrival times of the last
     * {@code maxSamples} arrivals, in milliseconds. A synthetic client-side
     * flush arrives in one or two TCP frames, so its IAT variance collapses
     * toward zero; a genuine bufferbloat burst keeps measurable jitter from the
     * network stack.
     *
     * <p>Bounding the sample count matters: the verdict fires the moment the
     * token bucket is overdrawn, so only the packets that caused the overdraw
     * carry the signal. Averaging older, normally-paced arrivals into it would
     * hide exactly the collapse we are looking for.
     */
    public double burstIatStd(int maxSamples) {
        int n = Math.min(burstCount, Math.max(3, maxSamples));
        if (n < 3) return Double.MAX_VALUE; // too few to judge
        // Walk the ring backwards from the newest arrival.
        double sum = 0, sumSq = 0;
        int gaps = 0;
        for (int i = 1; i < n; i++) {
            long newer = burstTimes[Math.floorMod(burstIdx - i, burstTimes.length)];
            long older = burstTimes[Math.floorMod(burstIdx - i - 1, burstTimes.length)];
            double gap = newer - older;
            sum += gap;
            sumSq += gap * gap;
            gaps++;
        }
        double mean = sum / gaps;
        double var = sumSq / gaps - mean * mean;
        return var <= 0 ? 0 : Math.sqrt(var);
    }

    public void resetBurst() {
        burstCount = 0;
        burstIdx = 0;
    }

    // ------------------------------------------------------------------
    // NoSlow (full speed while using an item)
    // ------------------------------------------------------------------
    /** Tick the player last started using an item (-1 = not using). */
    public long usingItemSinceTick = -1;
    /** Confirmed-violation buffer so a single fast tick never convicts. */
    public double noSlowBuffer;

    // ------------------------------------------------------------------
    // World
    // ------------------------------------------------------------------
    public BlockPos miningPos;
    public long miningStartTick;
    public double miningStartDelta;
    /** Hotbar slot the armed mining session started with. */
    public int miningSlot = -1;

    /**
     * Drops the armed mining session without judging it. Used everywhere the
     * session can only be stale: an insta-mined block (the client never sends
     * STOP or ABORT for those), a respawn, a world change, or any event that
     * makes the client's and the server's idea of "currently mining" diverge.
     */
    public void clearMining() {
        miningPos = null;
        miningSlot = -1;
    }

    /** Confirmed-violation buffers for the world checks. */
    public double packetMineBuffer;
    public double fastBreakBuffer;
    public double scaffoldBuffer;
    public double scaffoldAimBuffer;
    public double nukerBuffer;
    public double nukerAimBuffer;

    // Distinct block positions a break was started on, for the Nuker rate rule.
    private final long[] breakPositions = new long[48];
    private final long[] breakTimes = new long[48];
    private int breakIdx;
    private int breakCount;

    /**
     * Records a START_DESTROY_BLOCK and returns how many DISTINCT blocks were
     * started in the last second. Vanilla holds one break at a time and puts a
     * 5-tick cooldown after each one, so even insta-mining tops out around four
     * blocks a second held down, or the player's click rate by hand.
     */
    public int recordBreakAndCountDistinct(long posLong, long nowMillis) {
        breakPositions[breakIdx] = posLong;
        breakTimes[breakIdx] = nowMillis;
        breakIdx = (breakIdx + 1) % breakPositions.length;
        if (breakCount < breakPositions.length) breakCount++;
        int distinct = 0;
        long[] seen = new long[breakPositions.length];
        for (int i = 0; i < breakCount; i++) {
            if (nowMillis - breakTimes[i] > 1000) continue;
            long p = breakPositions[i];
            boolean dup = false;
            for (int j = 0; j < distinct; j++) {
                if (seen[j] == p) {
                    dup = true;
                    break;
                }
            }
            if (!dup) seen[distinct++] = p;
        }
        return distinct;
    }
    /** Rate-limits FastPlace violations so one burst cannot spike VL per packet. */
    public long lastFastPlaceFlagMs;

    // ------------------------------------------------------------------
    // X-ray session statistics (XrayTracker — observe-only, EDR-fed)
    // ------------------------------------------------------------------
    /** Stone-like blocks mined this session (the honest miner's overhead). */
    public int xrayStoneLike;
    /** Valuable ores (all tracked families) broken this session. */
    public int xrayValuable;
    /** Of those, ores that had no adjacent air at break time — the hidden signal. */
    public int xrayHidden;
    /** Family of the most recent hidden ore, for detail lines. */
    public XrayTracker.OreFamily xrayHiddenOreFamily;
    /** True once 60+ stone-like were mined — freezes the honest prefix for the drop verdict. */
    public boolean xrayPrefixFrozen;
    /** Stone-like blocks inside the purity-drop window (since the freeze). */
    public int xrayWindowStone;
    /** Valuable ores inside the purity-drop window (since the freeze). */
    public int xrayWindowOres;

    /** Session summary for the player card UI. */
    public int xrayDiamonds() {
        return xrayValuable;
    }

    /** Last server tick that emitted a shadow packet-pressure note for this player. */
    public long lastShadowMoveWarnTick = Long.MIN_VALUE;

    /**
     * Server tick of the last counted block interaction. A single right-click
     * can produce two packets (main hand result not accepted, then off hand),
     * and both are real interactions the server acts on — counting per tick
     * folds the pair into one without letting an off-hand-only cheat client
     * slip past the rate check entirely.
     */
    public long lastInteractTick = Long.MIN_VALUE;

    private final long[] placeTimes = new long[40];
    private int placeIdx;
    private int placeCount;

    public int recordPlaceAndCountLastSecond(long nowMillis) {
        placeTimes[placeIdx] = nowMillis;
        placeIdx = (placeIdx + 1) % placeTimes.length;
        if (placeCount < placeTimes.length) placeCount++;
        int n = 0;
        for (int i = 0; i < placeCount; i++) {
            if (nowMillis - placeTimes[i] <= 1000) n++;
        }
        return n;
    }
}
