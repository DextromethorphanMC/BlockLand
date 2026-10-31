package com.blockland.games.uhc;

import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;

/**
 * One match's square border, on the match clock (ticks): a fixed half-width, or shrinking from one to another between
 * two ticks. Several matches share the UHC dimension, so the level's own border is never touched: each player of the
 * match is sent this border as theirs (vanilla's border packets — the client draws the wall, warns near it and keeps
 * its player inside), and the match hurts whoever is outside it itself.
 */
final class UhcBorder {
    /** Vanilla's defaults for the red screen edge: this many blocks before the wall, this many seconds before it arrives. */
    private static final int WARNING_BLOCKS = 5, WARNING_SECONDS = 15;

    private final double centreX, centreZ;
    private double from, to;
    private int startTick, endTick;

    UhcBorder(double centreX, double centreZ, double radius) {
        this.centreX = centreX;
        this.centreZ = centreZ;
        set(radius);
    }

    void set(double radius) {
        from = to = radius;
        startTick = endTick = 0;
    }

    /** Shrinks (or grows) from the half-width at {@code start} to {@code radius} at {@code end}. */
    void lerp(double fromRadius, double radius, int start, int end) {
        from = fromRadius;
        to = radius;
        startTick = start;
        endTick = Math.max(start, end);
    }

    double radius(int tick) {
        if (tick <= startTick) return from;
        if (tick >= endTick) return to;
        return from + (to - from) * (tick - startTick) / (double) (endTick - startTick);
    }

    boolean shrinking(int tick) {
        return tick >= startTick && tick < endTick && from != to;
    }

    /** Blocks beyond the border at (x, z) (0 or less inside). */
    double outside(double x, double z, int tick) {
        return Math.max(Math.abs(x - centreX), Math.abs(z - centreZ)) - radius(tick);
    }

    double centreX() {
        return centreX;
    }

    double centreZ() {
        return centreZ;
    }

    /** Tells the player their border as it is at {@code tick}, moving on by itself on the client while it shrinks. */
    void send(ServerPlayer player, int tick) {
        WorldBorder border = new WorldBorder();
        border.setCenter(centreX, centreZ);
        border.setWarningBlocks(WARNING_BLOCKS);
        border.setWarningTime(WARNING_SECONDS);
        double size = radius(tick) * 2;
        if (shrinking(tick)) {
            border.lerpSizeBetween(size, to * 2, (endTick - tick) * 50L);
        } else {
            // before a shrink starts the client holds the size; the match sends the border again when it begins
            border.setSize(size);
        }
        player.connection.send(new ClientboundInitializeBorderPacket(border));
    }
}
