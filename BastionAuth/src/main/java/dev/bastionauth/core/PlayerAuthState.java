package dev.bastionauth.core;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-connection authentication state. Created on join, removed on
 * disconnect. Read from both the server thread and netty threads, hence the
 * volatile fields.
 */
public final class PlayerAuthState {
    public final UUID uuid;
    public final String name;
    public final String ip;

    public volatile boolean authenticated;
    /** The player object this state was created for — the disconnect handler removes only its own. */
    public volatile Object owner;
    /** True once the agreement is on file for this connection; the login clock starts then. */
    public volatile boolean agreementDone;
    public volatile long joinNanos;
    public volatile long lastReminderNanos;
    public volatile long lastWarnNanos;
    public volatile int wrongThisConnection;
    public volatile boolean invisApplied;
    /** Password accepted, second factor still owed; the login clock restarts for it. */
    public volatile boolean awaitingCode;
    public volatile int wrongCodes;
    /** What to run once the code is right: the rest of the login (set by tryLogin). */
    public volatile Runnable onCodeOk;
    /** This connection's device hash was never seen on this account before. */
    public volatile boolean newDevice;

    /**
     * Server tick at which the inventory mask should be re-sent, or 0 when it
     * is settled.
     *
     * <p>The mask has to be re-asserted a few times rather than sent once:
     * vanilla's own initial inventory sync lands after the join event, so a
     * single mask at join was overwritten a fraction of a second later — and
     * that fraction was measured on the wire, not guessed. It is a schedule
     * rather than a periodic task because the packet is large (the agreement
     * volumes are 140 pages of text) and re-sending it every second would cost
     * more bandwidth than the chunks around the player.
     */
    public volatile long maskDueTick;
    /** How many scheduled re-assertions are still to come. */
    public volatile int maskStep;

    /** Guards against parallel hash submissions for the same player. */
    public final AtomicBoolean busy = new AtomicBoolean();
    /** Guards against flooding the main thread with snap-back teleports. */
    public final AtomicBoolean snapPending = new AtomicBoolean();

    /** Freeze anchor — the position the player is held at until login. */
    public volatile double x;
    public volatile double y;
    public volatile double z;
    public volatile float yaw;
    public volatile float pitch;

    public PlayerAuthState(UUID uuid, String name, String ip) {
        this.uuid = uuid;
        this.name = name;
        this.ip = ip;
        long now = System.nanoTime();
        this.joinNanos = now;
        this.lastReminderNanos = now;
    }

    public void anchor(double x, double y, double z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }
}
