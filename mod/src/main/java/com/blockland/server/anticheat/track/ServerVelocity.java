package com.blockland.server.anticheat.track;

/**
 * A velocity the server sent the player (anti-cheat spec §5, §6): a knockback that sets their motion
 * ({@code ClientboundSetEntityMotionPacket}) or an explosion that adds to it. From sending until one move after their
 * client acknowledged the ping that followed it, the move may or may not show it (the predictor allows both); over that
 * span the move that took it the furthest along its direction is remembered, and it must have taken most of it.
 */
public final class ServerVelocity {
    public final double x, y, z;
    /** Added to the player's own motion (explosion) rather than replacing it (knockback). */
    public final boolean additive;
    /** The ping sent after it (0 = none yet: one goes out at the end of the tick). */
    public int ping;
    public boolean acknowledged;
    /** Moves judged since the acknowledgement. */
    public int movesAfterAck;
    /** Moves judged since it was sent (gives up after too many: lost track). */
    public int moves;
    /** The furthest a move went along its horizontal direction, and the highest rise, since it was sent. */
    public double bestAlong = Double.NEGATIVE_INFINITY;
    public double bestRise = Double.NEGATIVE_INFINITY;
    /** The carried horizontal speed when it was sent (an added velocity may meet the player moving the other way). */
    public double carried;
    /**
     * A move in its span was under a module's own motion (a slide, a dive, a parachute) or in surroundings that eat
     * velocity (a cobweb, a ladder, a liquid): whether it was taken cannot be told.
     */
    public boolean immune;

    public ServerVelocity(double x, double y, double z, boolean additive) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.additive = additive;
    }

    public double horizontal() {
        return Math.sqrt(x * x + z * z);
    }

    /** Remembers how far one move went along this velocity. */
    public void observe(double dx, double dy, double dz) {
        moves++;
        double h = horizontal();
        if (h > 1e-6) bestAlong = Math.max(bestAlong, (dx * x + dz * z) / h);
        bestRise = Math.max(bestRise, dy);
        if (acknowledged) movesAfterAck++;
    }
}
