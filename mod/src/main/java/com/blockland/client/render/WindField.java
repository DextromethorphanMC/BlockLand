package com.blockland.client.render;

/**
 * The wind at a point of the world (rendering spec §18) for what moves with it besides the plants — the tumbleweeds
 * (§29): its direction across the ground and its strength, the same steady wind, slow turbulence and travelling gusts
 * the plants bend by. The graphics module's wind publishes its field; until then a light breeze. Client thread only.
 */
public final class WindField {
    /** Fills {@code out} with the wind at (x, z): direction x, direction z (a unit vector), strength (about 0 … 2). */
    @FunctionalInterface
    public interface Field {
        void sample(double x, double z, float[] out);
    }

    private static Field field = (x, z, out) -> {
        out[0] = 1;
        out[1] = 0;
        out[2] = 0.2f;
    };

    private WindField() {
    }

    public static void publish(Field source) {
        if (source != null) field = source;
    }

    /** The wind at world (x, z) into {@code out}: direction x, direction z, strength. */
    public static void sample(double x, double z, float[] out) {
        field.sample(x, z, out);
    }
}
