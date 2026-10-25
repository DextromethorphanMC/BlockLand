package com.blockland.client.graphics.shadow;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * Where the sun and the moon are, as the shadows and the scene lighting both need it (one model, so lit faces and
 * shadows always agree). Minecraft's sky turns the sun about the east–west axis straight over the zenith; here the path
 * is tilted {@link #TILT} degrees towards the south, so noon shadows are not axis-aligned with the blocks (the same trick
 * shader packs use). The time of day is interpolated between ticks, so shadows glide instead of stepping.
 */
public final class SunPath {
    /** Tilt of the sun's path towards the south (degrees). */
    public static final float TILT = 25f;
    private static final float SIN_TILT = Mth.sin(TILT * Mth.DEG_TO_RAD);
    private static final float COS_TILT = Mth.cos(TILT * Mth.DEG_TO_RAD);
    /** ClientLevel's {@code tickDayTime}: whether its day time advances each tick (the daylight cycle). */
    @Nullable
    private static final Field TICK_DAY_TIME = tickDayTime();

    private SunPath() {
    }

    /**
     * Time of day 0..1 (0 = noon, 0.25 = sunset, 0.5 = midnight, 0.75 = sunrise), smooth between ticks — while the day
     * clock runs. A stopped clock (the daylight cycle off, the world frozen) stays where it is: interpolating towards a
     * next tick that never comes would swing the sun back and forth by a tick's worth twenty times a second.
     */
    public static float timeOfDay(Level level, float partialTick) {
        long day = level.dayTime();
        float a = level.dimensionType().timeOfDay(day);
        if (!dayTicks(level)) return a;
        float b = level.dimensionType().timeOfDay(day + 1);
        if (b < a) b += 1; // across the wrap
        float t = Mth.lerp(partialTick, a, b);
        return t - Mth.floor(t);
    }

    /** Whether the level's day time moves on with the next tick (the server says so; a frozen world does not tick). */
    private static boolean dayTicks(Level level) {
        if (!level.tickRateManager().runsNormally()) return false;
        if (TICK_DAY_TIME == null || !(level instanceof ClientLevel)) return true;
        try {
            return TICK_DAY_TIME.getBoolean(level);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    @Nullable
    private static Field tickDayTime() {
        try {
            Field f = ClientLevel.class.getDeclaredField("tickDayTime");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Unit vector from the camera towards the sun (y = sine of its elevation). */
    public static Vector3f sun(Level level, float partialTick, Vector3f out) {
        float angle = timeOfDay(level, partialTick) * Mth.TWO_PI;
        // vanilla's sky: rotate Y by −90°, then X by the angle, the sun at +Y → (−sin a, cos a, 0)
        float x = -Mth.sin(angle), y = Mth.cos(angle);
        return out.set(x, y * COS_TILT, y * SIN_TILT).normalize();
    }

    /** Unit vector towards the light that casts the shadows: the sun by day, the moon (opposite it) by night. */
    public static Vector3f light(Level level, float partialTick, Vector3f out) {
        sun(level, partialTick, out);
        return out.y >= 0 ? out : out.negate();
    }

    /** The shadow-casting light is the sun (false: the moon). */
    public static boolean isSun(Level level, float partialTick) {
        return sun(level, partialTick, new Vector3f()).y >= 0;
    }

    /**
     * How strong the light's shadows are, 0..1: full by day, weaker by moonlight, fading out while the light crosses the
     * horizon (so the switch from sun to moon never pops), less under rain and thunder, none without a sky.
     */
    public static float shadowStrength(Level level, float partialTick) {
        if (!level.dimensionType().hasSkyLight()) return 0;
        float elevation = sun(level, partialTick, new Vector3f()).y;
        float rise = smoothstep(0.02f, 0.16f, Math.abs(elevation));
        float strength = elevation >= 0 ? rise : rise * 0.45f;
        strength *= 1 - 0.65f * level.getRainLevel(partialTick);
        strength *= 1 - 0.35f * level.getThunderLevel(partialTick);
        return Mth.clamp(strength, 0, 1);
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
