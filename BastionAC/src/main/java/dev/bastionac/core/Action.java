package dev.bastionac.core;

/** What the packet hook should do after a check pass. */
public enum Action {
    NONE,
    /** Drop the packet. */
    CANCEL,
    /** Drop the packet and teleport the player back to the last good spot. */
    SETBACK;

    public static Action max(Action a, Action b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
