package com.blockland.server.anticheat.api;

/**
 * What the {@link MotionAllowance}s grant one move on top of vanilla physics. Reset before every move; allowances only
 * ever widen it (take the larger value, set an exemption), so their order does not matter.
 */
public final class MotionBudget {
    /**
     * Fastest horizontal velocity the mechanic may set, blocks per tick (0 = none). The player's input and a jump out of it
     * still add to it, as they do in vanilla (a jump out of a slide keeps the slide's speed and gets the sprint push).
     */
    public double horizontal;
    /** Fastest rise the mechanic may give this move, blocks per tick (0 = none; physics may allow more). */
    public double up;
    /** The module judges this axis itself (e.g. a parachute flight check): the anti-cheat leaves it alone. */
    public boolean exemptHorizontal;
    public boolean exemptVertical;
    /** Why (for the evidence), the last allowance that widened anything. */
    public String reason = "";

    public void reset() {
        horizontal = 0;
        up = 0;
        exemptHorizontal = false;
        exemptVertical = false;
        reason = "";
    }

    public void allowHorizontal(double speed, String why) {
        if (speed > horizontal) {
            horizontal = speed;
            reason = why;
        }
    }

    public void allowUp(double rise, String why) {
        if (rise > up) {
            up = rise;
            reason = why;
        }
    }

    public void exempt(boolean horizontalAxis, boolean verticalAxis, String why) {
        if (horizontalAxis) exemptHorizontal = true;
        if (verticalAxis) exemptVertical = true;
        if (horizontalAxis || verticalAxis) reason = why;
    }
}
