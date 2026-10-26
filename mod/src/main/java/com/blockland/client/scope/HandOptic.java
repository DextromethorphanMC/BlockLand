package com.blockland.client.scope;

import javax.annotation.Nullable;

/**
 * A hand-held optic that is not on a weapon — binoculars (asset spec §52): what {@link Optics#raiseHandOptic} needs to
 * zoom, slow the turning, sway the view and draw the view through it. Plain data, read by Gson from the owning module's
 * file (the binoculars: {@code assets/blockland/binoculars/binoculars.json}, key {@code optic}).
 */
public final class HandOptic {
    /** Remembers the chosen power per id for the session. */
    public String id = "hand";
    /** Magnifications the mouse wheel steps through, low to high. */
    public float[] powers = {4};
    /** Index into {@link #powers} the optic starts at. */
    public int startPower;
    /** How quickly the picture follows a new power (1/s, in log space). */
    public float zoomEase = 12;
    /** Breathing sway of the view at full power (degrees; scales with the power's share of the highest). */
    public float sway = 0.1f;
    /** Sway multiplier while walking at full speed, and while sneaking (braced). */
    public float swayMoving = 3;
    public float swaySneaking = 0.4f;
    /** Played (UI) on every power step; null = none. */
    @Nullable
    public String zoomSound;
    public Overlay overlay = new Overlay();
    public Reticle reticle = new Reticle();
    /** A laser rangefinder's readout; null = none. */
    @Nullable
    public Rangefinder rangefinder = new Rangefinder();

    /** The view through two eyepieces: two round apertures side by side, overlapping in the middle. */
    public static final class Overlay {
        /** Each aperture's radius, as a share of the screen height. */
        public float radius = 0.44f;
        /** Each aperture's centre off the screen's middle, as a share of the screen height. */
        public float separation = 0.3f;
        /** Width of the soft edge, as a share of the radius. */
        public float softness = 0.06f;
        /** Darkening towards the rim (0..1). */
        public float vignette = 0.35f;
        /** The lens edge's colour (sRGB 0..255) in the soft edge. */
        public int[] fringe = {40, 70, 100};
    }

    /** A mil scale: a horizontal line with ticks, a stadia below the middle. */
    public static final class Reticle {
        /** sRGB 0..255 and opacity. */
        public int[] color = {12, 12, 12};
        public float alpha = 0.85f;
        /** Half the horizontal scale's length and the stadia's length (mils). */
        public int span = 40;
        public int stadia = 20;
    }

    public static final class Rangefinder {
        /** Farthest distance it measures (blocks); past it, or into the sky, it reads dashes. */
        public float max = 1000;
        /** Seconds between measurements. */
        public float refresh = 0.15f;
    }

    /** The power at an index, clamped to the list (1 for an empty list). */
    float power(int index) {
        if (powers == null || powers.length == 0) return 1;
        return Math.max(1, powers[Math.max(0, Math.min(powers.length - 1, index))]);
    }

    int powerCount() {
        return powers == null || powers.length == 0 ? 1 : powers.length;
    }

    static int rgb(int[] c, int fallback) {
        if (c == null || c.length < 3) return fallback;
        return (clamp(c[0]) << 16) | (clamp(c[1]) << 8) | clamp(c[2]);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
