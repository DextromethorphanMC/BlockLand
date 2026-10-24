package com.blockland.client.render;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Dynamic lights that features add to the world (rendering spec §5): short flashes (a muzzle flash, a blast) and
 * attached lights that live as long as their source (a burning patch of fire). The graphics module collects them every
 * frame, adds the lights it finds itself (held torches, glowing items) and lights the world and the weapons with the
 * strongest ones near the camera. Without world lighting nothing reads them, so adding lights is always cheap and safe.
 * Client thread only.
 */
public final class LightSources {
    /** Flashes that are still around are kept at most this long (seconds), whatever they asked for. */
    private static final float MAX_FLASH = 3f;
    private static final int MAX_FLASHES = 64;

    /**
     * One light.
     *
     * @param x,y,z     world position
     * @param r,g,b     display colour 0..1
     * @param intensity brightness multiplier (1 ≈ a torch at its own block)
     * @param radius    reach in blocks (the light fades to nothing there)
     */
    public record Light(double x, double y, double z, float r, float g, float b, float intensity, float radius) {
        public Light scaled(float k) {
            return new Light(x, y, z, r, g, b, intensity * k, radius);
        }
    }

    private record Flash(Light light, long start, float seconds) {
    }

    private static final List<Flash> flashes = new ArrayList<>();
    private static final List<Supplier<Light>> attached = new ArrayList<>();

    private LightSources() {
    }

    /**
     * A short light that fades out over {@code seconds} (muzzle flash ≈ 0.06, a grenade blast ≈ 0.4).
     *
     * @param rgb 0xRRGGBB display colour
     */
    public static void flash(double x, double y, double z, int rgb, float intensity, float radius, float seconds) {
        if (flashes.size() >= MAX_FLASHES) flashes.remove(0);
        Light light = new Light(x, y, z, (rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, intensity, radius);
        flashes.add(new Flash(light, System.nanoTime(), Math.max(0.01f, Math.min(MAX_FLASH, seconds))));
    }

    /** A light read every frame until the supplier returns null (then it is dropped). */
    public static void attach(Supplier<Light> source) {
        attached.add(source);
    }

    /** Whether this light is attached (it goes when it returns null, or when leaving a world). */
    public static boolean isAttached(Supplier<Light> source) {
        return attached.contains(source);
    }

    /** Graphics side: every light of this moment (flashes already faded by their age). */
    public static void collect(Consumer<Light> out) {
        long now = System.nanoTime();
        for (Iterator<Flash> it = flashes.iterator(); it.hasNext(); ) {
            Flash flash = it.next();
            float age = (now - flash.start) / 1e9f;
            if (age >= flash.seconds) {
                it.remove();
                continue;
            }
            float t = 1 - age / flash.seconds;
            out.accept(flash.light.scaled(t * t));
        }
        for (Iterator<Supplier<Light>> it = attached.iterator(); it.hasNext(); ) {
            Light light = get(it.next());
            if (light == null) it.remove();
            else out.accept(light);
        }
    }

    /** Leaving a world: every light goes. */
    public static void clear() {
        flashes.clear();
        attached.clear();
    }

    @Nullable
    private static Light get(Supplier<Light> source) {
        try {
            return source.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
