package com.blockland.client.graphics.light;

import com.blockland.client.graphics.GraphicsSettings;

/**
 * The player's light colours (画质 → 光源颜色): colour temperature tints for sunlight, moonlight and block light, each a
 * multiplier of luminance 1 around white, so a tint changes the colour of a light and never its brightness. Used by the
 * scene uniforms, the ray tracer and the clouds, so every part of the picture sees the same lights.
 */
public final class LightColors {
    /** Tints at the ends of the scale (before normalising): candle-warm and overcast-cool. */
    private static final float[] WARM = {1.0f, 0.80f, 0.58f};
    private static final float[] COOL = {0.70f, 0.85f, 1.20f};

    private LightColors() {
    }

    /** A colour temperature −1 cool … +1 warm as an RGB multiplier of luminance 1 (0 = white). */
    public static void temperature(float t, float[] out) {
        float[] end = t >= 0 ? WARM : COOL;
        float k = Math.min(1, Math.abs(t));
        for (int i = 0; i < 3; i++) out[i] = 1 + (end[i] - 1) * k;
        float luminance = 0.2126f * out[0] + 0.7152f * out[1] + 0.0722f * out[2];
        for (int i = 0; i < 3; i++) out[i] /= Math.max(1e-4f, luminance);
    }

    /** Multiplies {@code color} by the sunlight's (or moonlight's) tint. */
    public static void tintSky(GraphicsSettings settings, boolean sun, float[] color) {
        float[] tint = new float[3];
        temperature(sun ? settings.sunTemperature : settings.moonTemperature, tint);
        for (int i = 0; i < 3; i++) color[i] *= tint[i];
    }

    /** Block light's tint × its brightness setting (1, 1, 1 = vanilla's block light). */
    public static void blockLight(GraphicsSettings settings, float[] out) {
        temperature(settings.blockLightTemperature, out);
        for (int i = 0; i < 3; i++) out[i] *= settings.blockLightStrength;
    }
}
