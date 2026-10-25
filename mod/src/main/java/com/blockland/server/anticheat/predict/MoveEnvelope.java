package com.blockland.server.anticheat.predict;

/** The predictor's answer for one move: how far it may go, and how far it went. */
public final class MoveEnvelope {
    /** Largest horizontal step physics (and the allowances) explain, tolerance included. */
    public double maxHorizontal;
    /** The same without the tolerance: what the next move may carry on from at most. */
    public double rawHorizontal;
    public double horizontal;
    /** Largest rise physics explains, tolerance included (negative in a fall: the player must sink at least that). */
    public double maxUp;
    public double rawUp;
    public double up;
    /** Input acceleration of this tick (what a player can steer against a pushed velocity). */
    public double accel;
    /** Friction carried over from the tick before. */
    public double friction;
    public boolean checkHorizontal, checkVertical;

    /** By how much the step was too long, 0 when it was fine. */
    public double horizontalExcess() {
        return checkHorizontal ? Math.max(0, horizontal - maxHorizontal) : 0;
    }

    /** By how much the rise was too high, 0 when it was fine. */
    public double verticalExcess() {
        return checkVertical ? Math.max(0, up - maxUp) : 0;
    }
}
