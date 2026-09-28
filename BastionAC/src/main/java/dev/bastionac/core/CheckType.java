package dev.bastionac.core;

import java.util.Locale;

/** All checks known to the engine. Config keys are the lower-case names. */
public enum CheckType {
    SPEED,
    FLY,
    SPIDER,
    TIMER,
    BIGMOVE,
    GROUNDSPOOF,
    NOFALL,
    REACH,
    ANGLE,
    WALLS,
    MULTITARGET,
    USEATTACK,
    NOSWING,
    CPS,
    FASTBREAK,
    /** Breaking many blocks per second, or blocks the player is not looking at. */
    NUKER,
    FASTPLACE,
    /** Machine-even attack rhythm (autoclicker / aura timing). */
    AUTOCLICK,
    /** Machine-even block place/break rhythm (macro, auto-miner, bot). */
    AUTOACTION,
    /** Knockback/velocity suppression or reduction (Anti-KB). */
    VELOCITY,
    /** A block break armed or finished from outside interaction range. */
    PACKETMINE,
    /** A critical hit the server's own collision scan says had no fall behind it. */
    CRITICALS,
    /** Withheld movement packets flushed in a zero-variance burst (Blink). */
    BLINK,
    /** Moving at full speed while using an item (eating / bow / shield). */
    NOSLOW,
    /** Geometrically impossible block placement (look vector vs face normal). */
    SCAFFOLD,
    /** Rotation deltas off the client's sensitivity grid (GCD) — aim mod. */
    GCD,
    /** Statistical mining pattern (hidden ores, pure-ore sessions, high ratio) — observe-only X-ray heuristic. */
    XRAY,
    /** A ridden vehicle moving faster or higher than its own server-side physics allow (BoatFly, VehicleSpeed). */
    VEHICLE,
    /** A block placed against a target with no collision shape — the client could not have raycast it. */
    AIRPLACE,
    /** Inventory clicks while running: vanilla stops movement input whenever a screen is open. */
    INVMOVE,
    /** The client claims brand "vanilla" while speaking modded plugin channels. */
    BRANDSPOOF,
    /** More than one movement packet inside a single client tick (Blink/Criticals/NoFall packet tricks). */
    PACKETORDER,
    /** Aura aim: hits looking exactly through the target's hitbox centre, flicks there and back (Killaura, ClickAura). */
    AIM,
    /** Block clicks exactly at the geometric centre of a face — computed, not raycast (Scaffold, Bunker, crystal/anchor auras). */
    AUTOPLACE,
    /** Standing on water: onGround claimed over a liquid with nothing solid underneath. */
    JESUS,
    /** Step up in one tick through the intermediate +0.42/+0.753 packets (NCP step). */
    STEP,
    /** Totem back in the off hand faster than a hand can open the inventory. */
    AUTOTOTEM,
    /** Head pitch beyond ±90°, which a vanilla client clamps and can never send (Derp/Tired). */
    BADROTATION;

    public final String key = name().toLowerCase(Locale.ROOT);
}
