package dev.bastionac.core;

import java.util.List;

/**
 * Aura aim, judged on the attack: where the look pointed and how it got there.
 *
 * <h2>Centre lock</h2>
 * A combat module computes its rotation instead of steering a mouse. Wurst's
 * Killaura, ClickAura, MultiAura and TpAura all aim at
 * {@code target.getBoundingBox().getCenter()} from the eye; Meteor's KillAura
 * aims at the body centre the same way. A hand never lands the crosshair on
 * the geometric centre of a moving box to a fraction of a degree, hit after
 * hit — players aim at the head, the chest, wherever the box is widest.
 *
 * <p>The server does not see what the client saw, so the test is generous
 * about <em>when</em>: the eye is tried at the last few claimed positions
 * (Wurst aims before it moves, the packet carries the position after), the
 * target at every recorded box in the ping window and at the thirds between
 * consecutive ones (the client draws other entities interpolated). The
 * smallest angle wins. Only the angle itself has to be tiny.
 *
 * <h2>Snap-back</h2>
 * Wurst's RotationFaker sends the aimed rotation in one packet and the real
 * one in the next: the head flicks to the target for the hit and returns to
 * where the player was looking. A flick of ten-plus degrees that lands dead
 * centre and comes straight back is not a hand.
 */
public final class AimTracker {

    /** An angle to the centre this small (degrees) counts as a locked hit. */
    public static final double LOCK_DEG = 1.0;
    /** Below this distance the centre subtends too wide a cone to mean anything. */
    public static final double MIN_DIST = 1.5;
    /** Snap-back: a flick at least this large… */
    public static final double MIN_SNAP_DEG = 10.0;

    /** Unit look vector for a Minecraft yaw/pitch (degrees). */
    public static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double cp = Math.cos(p);
        return new double[]{-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp};
    }

    /** Angle (degrees) between a look vector and the direction eye → point. */
    public static double angleTo(double[] look, double ex, double ey, double ez, double px, double py, double pz) {
        double dx = px - ex, dy = py - ey, dz = pz - ez;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-9) return 180;
        double dot = (look[0] * dx + look[1] * dy + look[2] * dz) / len;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    /** Angle (degrees) between two rotations, both wrapped. */
    public static double rotationDelta(float yaw1, float pitch1, float yaw2, float pitch2) {
        double dy = wrap(yaw1 - yaw2), dp = wrap(pitch1 - pitch2);
        return Math.sqrt(dy * dy + dp * dp);
    }

    static double wrap(double deg) {
        deg %= 360.0;
        if (deg >= 180.0) deg -= 360.0;
        if (deg < -180.0) deg += 360.0;
        return deg;
    }

    /**
     * Smallest angle between the look and any candidate centre, from any
     * candidate eye. {@code eyes} and {@code centres} are flat xyz triples.
     */
    public static double minError(double[] look, List<double[]> eyes, List<double[]> centres) {
        double best = 180;
        for (double[] e : eyes) {
            for (double[] c : centres) {
                double a = angleTo(look, e[0], e[1], e[2], c[0], c[1], c[2]);
                if (a < best) best = a;
            }
        }
        return best;
    }

    /**
     * Records one judged attack. Returns a message when the centre lock is
     * proven (enough locked hits in the recent window), else null.
     */
    public static String recordLock(PlayerData d, double err) {
        boolean locked = err < LOCK_DEG;
        d.aimRing[d.aimRingPos] = locked;
        d.aimRingPos = (d.aimRingPos + 1) % d.aimRing.length;
        if (d.aimRingFill < d.aimRing.length) d.aimRingFill++;
        if (locked) {
            d.aimBuffer += 1;
        } else {
            d.aimBuffer = Math.max(0, d.aimBuffer - 0.5);
        }
        if (d.aimBuffer < 6) return null;
        d.aimBuffer = 3;
        int hits = 0;
        for (int i = 0; i < d.aimRingFill; i++) if (d.aimRing[i]) hits++;
        return String.format(java.util.Locale.ROOT,
                "взгляд точно в центр хитбокса в %d из %d ударов (сейчас %.2f°)", hits, d.aimRingFill, err);
    }

    /** Whether a new rotation, after a flick to the target, came back to where it was. */
    public static boolean snappedBack(double snapAngle, float preYaw, float prePitch, float yaw, float pitch) {
        double back = rotationDelta(yaw, pitch, preYaw, prePitch);
        return back <= Math.max(3.0, snapAngle * 0.25);
    }

    private AimTracker() {}
}
