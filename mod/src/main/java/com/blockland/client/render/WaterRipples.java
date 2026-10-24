package com.blockland.client.render;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Rings spreading on water where something disturbed it (rendering spec §15): features add them — rounds and splashes
 * (impacts), entities wading, swimming, landing and striking, blocks dropped in (water interactions) — and the lighting
 * pack's water bends its surface by them (the newest {@link #MAX} live at once, written into the scene uniforms each
 * frame). A ring may lean one way: {@code dirX, dirZ} (unit, or 0) is the disturber's heading, its bow wave stronger ahead.
 * Render and client thread only (both run on the same thread).
 */
public final class WaterRipples {
    public static final int MAX = 24;
    /** A ring is gone after this long (s). */
    public static final float LIFE = 3.5f;

    private static final double[] x = new double[MAX], y = new double[MAX], z = new double[MAX];
    private static final float[] strength = new float[MAX], dirX = new float[MAX], dirZ = new float[MAX];
    private static final long[] born = new long[MAX];
    private static int next;
    private static ClientLevel level;

    private WaterRipples() {
    }

    /**
     * A ring at a point of a water surface ({@code y} = the surface's height).
     *
     * @param strength 0.2 a ripple of a hand, 1 a body wading, 2 a heavy fall
     */
    public static void add(ClientLevel world, double px, double py, double pz, float power, float headingX, float headingZ) {
        if (world != level) {
            clear();
            level = world;
        }
        int i = next;
        next = (next + 1) % MAX;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        strength[i] = power;
        dirX[i] = headingX;
        dirZ[i] = headingZ;
        born[i] = System.nanoTime();
    }

    public static void clear() {
        for (int i = 0; i < MAX; i++) born[i] = 0;
        level = null;
    }

    /** One live ring, camera-relative. */
    @FunctionalInterface
    public interface Sink {
        void ring(float x, float y, float z, float age, float strength, float dirX, float dirZ);
    }

    /** Hands every live ring in {@code world} to {@code sink}; returns how many. */
    public static int forEach(ClientLevel world, double camX, double camY, double camZ, Sink sink) {
        if (world != level) return 0;
        long now = System.nanoTime();
        int count = 0;
        for (int i = 0; i < MAX; i++) {
            if (born[i] == 0) continue;
            float age = (now - born[i]) / 1e9f;
            if (age > LIFE) {
                born[i] = 0;
                continue;
            }
            sink.ring((float) (x[i] - camX), (float) (y[i] - camY), (float) (z[i] - camZ), age, strength[i], dirX[i], dirZ[i]);
            count++;
        }
        return count;
    }
}
