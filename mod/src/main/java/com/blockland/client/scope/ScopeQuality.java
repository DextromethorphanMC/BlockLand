package com.blockland.client.scope;

import java.util.Locale;

/**
 * Resolution of a magnified scope's own picture (picture in picture), as a share of the pixels the eyepiece covers
 * on screen, and how much the eyepiece sharpens it back (a lower resolution is stretched further); {@link #OFF} zooms
 * the whole view instead (cheapest).
 */
public enum ScopeQuality {
    OFF(0, 0), LOW(0.6f, 0.45f), MEDIUM(0.85f, 0.3f), HIGH(1.15f, 0.15f);

    /** Side of the square picture as a share of the eyepiece's diameter in screen pixels. */
    public final float scale;
    /** Unsharp-mask strength the eyepiece applies to the picture. */
    public final float sharpen;

    ScopeQuality(float scale, float sharpen) {
        this.scale = scale;
        this.sharpen = sharpen;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ScopeQuality of(String id) {
        for (ScopeQuality quality : values()) if (quality.id().equals(id)) return quality;
        return MEDIUM;
    }
}
