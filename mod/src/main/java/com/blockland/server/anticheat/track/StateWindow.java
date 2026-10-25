package com.blockland.server.anticheat.track;

import com.blockland.server.anticheat.predict.Physics;

/**
 * The movement-relevant numbers of a player over the last ticks, one sample per server tick (anti-cheat spec §5): a move
 * is judged against the most lenient value within the player's latency window, because a change on the server (an
 * effect ending, a sprint stopping) reaches their client only a round trip later. Fixed-size primitive rings, no
 * allocation.
 */
public final class StateWindow {
    /** Ticks kept: the largest window ever asked for. */
    public static final int SIZE = 128;

    public static final int SLOW_FALLING = 1;
    public static final int LEVITATION = 1 << 1;
    public static final int DOLPHINS_GRACE = 1 << 2;
    /** Anything that puts movement out of reach of the physics (flying, gliding, riding, spin attack, spectating…). */
    public static final int EXEMPT = 1 << 3;
    /** No gravity (a game holding the player in place, e.g. before boarding the battle royale plane). */
    public static final int NO_GRAVITY = 1 << 4;
    /** Sneaking (an edge may hold its steps back while its velocity stays). */
    public static final int SNEAKING = 1 << 5;

    private final double[] speed = new double[SIZE];
    private final double[] jumpStrength = new double[SIZE];
    private final double[] stepHeight = new double[SIZE];
    private final double[] gravity = new double[SIZE];
    private final double[] waterEfficiency = new double[SIZE];
    private final int[] jumpBoost = new int[SIZE];
    private final int[] flags = new int[SIZE];
    private int head = -1;
    private int count;

    /** One server tick's sample. */
    public void push(double speedValue, double jump, double step, double gravityValue, double water, int boost, int flagBits) {
        head = (head + 1) % SIZE;
        speed[head] = speedValue;
        jumpStrength[head] = jump;
        stepHeight[head] = step;
        gravity[head] = gravityValue;
        waterEfficiency[head] = water;
        jumpBoost[head] = boost;
        flags[head] = flagBits;
        count = Math.min(SIZE, count + 1);
    }

    public void clear() {
        head = -1;
        count = 0;
    }

    private int span(int window) {
        return Math.max(1, Math.min(count, Math.min(SIZE, window + 1)));
    }

    private int at(int back) {
        return (head - back + SIZE) % SIZE;
    }

    public double maxSpeed(int window) {
        if (count == 0) return 0.1 * Physics.SPRINT;
        double max = 0;
        for (int i = 0, n = span(window); i < n; i++) max = Math.max(max, speed[at(i)]);
        return max;
    }

    public double maxJumpStrength(int window) {
        if (count == 0) return Physics.DEFAULT_JUMP;
        double max = 0;
        for (int i = 0, n = span(window); i < n; i++) max = Math.max(max, jumpStrength[at(i)]);
        return max;
    }

    public double maxStepHeight(int window) {
        if (count == 0) return Physics.DEFAULT_STEP;
        double max = 0;
        for (int i = 0, n = span(window); i < n; i++) max = Math.max(max, stepHeight[at(i)]);
        return max;
    }

    /** Smallest gravity: Slow Falling anywhere in the window counts as 0.01 (it only acts while falling). */
    public double minGravity(int window) {
        if (count == 0) return Physics.DEFAULT_GRAVITY;
        double min = Double.MAX_VALUE;
        for (int i = 0, n = span(window); i < n; i++) {
            int k = at(i);
            min = Math.min(min, (flags[k] & SLOW_FALLING) != 0 ? Math.min(gravity[k], Physics.SLOW_FALL_GRAVITY) : gravity[k]);
        }
        return min;
    }

    public double maxWaterEfficiency(int window) {
        double max = 0;
        for (int i = 0, n = count == 0 ? 0 : span(window); i < n; i++) max = Math.max(max, waterEfficiency[at(i)]);
        return max;
    }

    public int maxJumpBoost(int window) {
        int max = -1;
        for (int i = 0, n = count == 0 ? 0 : span(window); i < n; i++) max = Math.max(max, jumpBoost[at(i)]);
        return max;
    }

    /** Whether any sample in the window has one of {@code bits}. */
    public boolean any(int bits, int window) {
        for (int i = 0, n = count == 0 ? 0 : span(window); i < n; i++) if ((flags[at(i)] & bits) != 0) return true;
        return false;
    }
}
