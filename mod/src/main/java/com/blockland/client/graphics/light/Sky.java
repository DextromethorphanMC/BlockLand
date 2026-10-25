package com.blockland.client.graphics.light;

import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.shadow.SunPath;
import com.blockland.client.render.RenderClock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The light that casts the shadows (rendering spec §2): the sun by day, the moon by night, on the shadow map's
 * {@link SunPath}. Its colour warms towards the horizon, it fades out at sunrise and sunset (where the light swaps between
 * sun and moon unseen), rain and thunder weaken it, and dimensions without skylight have none. The player's colour
 * temperatures tint it ({@link LightColors}). Also the frame's block light tint and BlockLand's haze (rendering spec
 * §14): its density where the camera is, thicker low down, at dawn and dusk and in rain, none under ground or water.
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
    /** Haze extinction at sea level in clear weather (per block, at fog density 1) and how fast it thins with height. */
    private static final float HAZE_SEA_LEVEL = 0.002f, HAZE_FALLOFF = 1 / 48f;

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
    /** Block light tint × strength (1 = vanilla's). */
    final float[] blockLight = {1, 1, 1};
    /** BlFog: haze density at the camera's height (per block), height falloff (per block), sun in-scatter, 1 = on. */
    final float[] fog = new float[4];
    /** How open the camera stands to the sky, eased (walking out of a cave brings the haze in over a second or so). */
    private float open = 1;
    /** BlWeather: wetness (eased), rain falling now, the camera's openness to the sky (ripple count: the uniforms). */
    final float[] weather = new float[3];
    private float wet;
    private long lastNanos;

    /** @param openAir the camera is in the open air (not in a fluid, not blinded): BlockLand's haze may apply */
    void update(ClientLevel level, Vec3 camera, float partial, GraphicsSettings settings, boolean openAir) {
        float rain = level.getRainLevel(partial), thunder = level.getThunderLevel(partial);
        LightColors.blockLight(settings, blockLight);
        fog[0] = fog[1] = fog[2] = fog[3] = 0;
        wetness(level, camera, rain, settings);
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
        LightColors.tintSky(settings, sun, color);
        if (openAir && settings.fog && settings.fogDensity > 0.001f) haze(level, camera, partial, settings, rain);
    }

    /**
     * Rain wets what it falls on only where the biome rains (not in snow or deserts); surfaces take a quarter of a minute
     * to soak and a minute and a half to dry, so a shower leaves the world wet for a while.
     */
    private void wetness(ClientLevel level, Vec3 camera, float rain, GraphicsSettings settings) {
        BlockPos at = BlockPos.containing(camera);
        boolean raining = level.dimensionType().hasSkyLight()
                && level.getBiome(at).value().getPrecipitationAt(at, level.getSeaLevel()) == Biome.Precipitation.RAIN;
        float falling = raining ? rain : 0;
        long now = RenderClock.nanos();
        float seconds = lastNanos == 0 ? 0 : Math.min(0.5f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float tau = falling > wet ? 15f : 90f;
        wet += (falling - wet) * (1 - (float) Math.exp(-seconds / tau));
        float sky = Mth.clamp((level.getBrightness(LightLayer.SKY, at) - 6) / 9f, 0, 1);
        weather[0] = wet * settings.rainWetness;
        weather[1] = falling * settings.rainWetness;
        weather[2] = sky;
    }

    private void haze(ClientLevel level, Vec3 camera, float partial, GraphicsSettings settings, float rain) {
        // like vanilla's rain fog: only where the sky reaches (none in caves, where the fog colour is the sky's)
        BlockPos at = BlockPos.containing(camera);
        float target = Mth.clamp((level.getBrightness(LightLayer.SKY, at) - 8) / 7f, 0, 1);
        open += (target - open) * 0.04f;
        if (open <= 0.001f) return;
        float low = 1 - smooth(0f, 0.3f, Math.abs(direction.y));
        float weather = 1 + 2.5f * rain;
        float height = (float) Math.exp(-HAZE_FALLOFF * Math.max(-48, camera.y - level.getSeaLevel()));
        fog[0] = HAZE_SEA_LEVEL * settings.fogDensity * open * (1 + 1.5f * low) * weather * height;
        fog[1] = HAZE_FALLOFF;
        fog[2] = (sun ? 0.7f : 0.3f) * (1 - 0.7f * rain);
        fog[3] = 1;
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
