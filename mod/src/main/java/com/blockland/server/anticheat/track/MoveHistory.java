package com.blockland.server.anticheat.track;

import java.util.Locale;

/**
 * The last few movement packets of a player as the movement checks saw them (anti-cheat spec §7): kept for every
 * player (a handful of numbers per move), printed only for a traced player ({@code /bl ac trace}), so a false positive
 * can be followed move by move.
 */
public final class MoveHistory {
    private static final int SIZE = 8;

    private final double[] dx = new double[SIZE], dy = new double[SIZE], dz = new double[SIZE], slip = new double[SIZE];
    /** Where each move ended. */
    private final double[] x = new double[SIZE], y = new double[SIZE], z = new double[SIZE];
    private final int[] ticks = new int[SIZE];
    /** Bits: 1 claimed ground, 2 server ground, 4 started on the ground, 8 bouncy floor, 16 not judged. */
    private final int[] flags = new int[SIZE];
    private int next, count;

    public void add(int clientTicks, double endX, double endY, double endZ, double moveX, double moveY, double moveZ, boolean claimed,
                    boolean verified, boolean groundPrev, double slipperiness, boolean bouncy, boolean unjudged) {
        int i = next;
        x[i] = endX;
        y[i] = endY;
        z[i] = endZ;
        ticks[i] = clientTicks;
        dx[i] = moveX;
        dy[i] = moveY;
        dz[i] = moveZ;
        slip[i] = slipperiness;
        flags[i] = (claimed ? 1 : 0) | (verified ? 2 : 0) | (groundPrev ? 4 : 0) | (bouncy ? 8 : 0) | (unjudged ? 16 : 0);
        next = (next + 1) % SIZE;
        count = Math.min(SIZE, count + 1);
    }

    /**
     * Oldest first: per move its client ticks, horizontal and vertical step, where it ended, ground (claimed/server, at its
     * start) and floor.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (int k = count; k > 0; k--) {
            int i = Math.floorMod(next - k, SIZE);
            int f = flags[i];
            if (!text.isEmpty()) text.append(" | ");
            text.append(String.format(Locale.ROOT, "%dt h%.3f y%+.3f →(%.2f, %.3f, %.2f) %s%s起%s 滑%.2f%s%s", ticks[i],
                    Math.hypot(dx[i], dz[i]), dy[i], x[i], y[i], z[i],
                    (f & 1) != 0 ? "称地" : "称空", (f & 2) != 0 ? "/地" : "/空", (f & 4) != 0 ? "地" : "空", slip[i],
                    (f & 8) != 0 ? " 弹" : "", (f & 16) != 0 ? " 未判" : ""));
        }
        return text.toString();
    }
}
