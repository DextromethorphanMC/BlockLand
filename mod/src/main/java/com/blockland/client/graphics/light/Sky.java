package com.blockland.client.graphics.light;

import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.shadow.SunPath;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The light that casts the shadows (rendering spec §2): the sun by day, the moon by night, on the shadow map's
 * {@link SunPath}. Its colour warms towards the horizon, it fades out at sunrise and sunset (where the light swaps between
 * sun and moon unseen), rain and thunder weaken it, and dimensions without skylight have none.
 */
final class Sky {
    private static final float[] SUN_LOW = {1.0f, 0.50f, 0.26f};
    private static final float[] SUN_HIGH = {1.0f, 0.96f, 0.90f};
    private static final float[] MOON = {0.55f, 0.66f, 1.0f};
    /** Tints of light in full shadow (around 1): a cool sky by day, neutral by night. */
    private static final float[] SHADE_DAY = {0.88f, 0.94f, 1.08f};
    private static final float[] SHADE_NIGHT = {0.94f, 0.96f, 1.03f};
    /** Share of skylight left in full shadow by day and by night. */
    private static final float SHADE_DAY_SHARE = 0.6f, SHADE_NIGHT_SHARE = 0.72f;

    /** Towards the light (world space). */
    final Vector3f direction = new Vector3f(0, 1, 0);
    boolean sun = true;
    /** Direct light colour × strength, linear. */
    final float[] color = new float[3];
    /** Shadow strength 0..1. */
    float strength;
    final float[] shade = new float[3];
    float shadeShare = SHADE_DAY_SHARE;
    /** Sky colour for reflections, linear. */
    final float[] skyColor = new float[3];
    float wetness;

    void update(ClientLevel level, Vec3 camera, float partial, GraphicsSettings settings) {
        float rain = level.getRainLevel(partial), thunder = level.getThunderLevel(partial);
        int sky = level.getSkyColor(camera, partial);
        skyColor[0] = linear((sky >> 16 & 0xFF) / 255f);
        skyColor[1] = linear((sky >> 8 & 0xFF) / 255f);
        skyColor[2] = linear((sky & 0xFF) / 255f);
        wetness = rain;
        if (!level.dimensionType().hasSkyLight()) {
            direction.set(0, 1, 0);
            sun = true;
            color[0] = color[1] = color[2] = 0;
            strength = 0;
            set(shade, SHADE_DAY);
            shadeShare = 1;
            return;
        }
        // the same path and the same shadow strength as the shadow map, so lit faces and shadows always agree
        SunPath.light(level, partial, direction);
        sun = SunPath.isSun(level, partial);
        float weather = (1 - 0.8f * rain) * (1 - 0.5f * thunder);
        float strengthSetting = Math.min(1, settings.sunStrength);
        float fade = smooth(0f, 0.12f, direction.y);
        if (sun) {
            float warm = smooth(0f, 0.45f, direction.y);
            float intensity = fade * weather * settings.sunStrength;
            for (int i = 0; i < 3; i++) color[i] = Mth.lerp(warm, SUN_LOW[i], SUN_HIGH[i]) * intensity;
            strength = SunPath.shadowStrength(level, partial) * strengthSetting;
            set(shade, SHADE_DAY);
            shadeShare = SHADE_DAY_SHARE;
        } else {
            float moon = level.getMoonBrightness();
            float intensity = 0.22f * fade * (0.4f + 0.6f * moon) * weather * settings.sunStrength;
            for (int i = 0; i < 3; i++) color[i] = MOON[i] * intensity;
            // moonlight shadows follow the moon's phase (none at new moon)
            strength = SunPath.shadowStrength(level, partial) * (0.3f + 0.7f * moon) * strengthSetting;
            set(shade, SHADE_NIGHT);
            shadeShare = SHADE_NIGHT_SHARE;
        }
    }

    private static void set(float[] to, float[] from) {
        System.arraycopy(from, 0, to, 0, 3);
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    private static float linear(float display) {
        return (float) Math.pow(Math.max(0, display), 2.2);
    }
}
