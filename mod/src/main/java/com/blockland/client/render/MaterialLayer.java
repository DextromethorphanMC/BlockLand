package com.blockland.client.render;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * How a skin re-textures one region of a model (asset spec §21): a LabPBR texture set, its tiling, a tint and a shader
 * effect. Render-side and data-free: the craft module turns its skin data into these.
 *
 * @param pbr         texture set under {@code assets/blockland/} without {@code .png}; null keeps the material's own
 * @param uvScale     tiling of the texture set; 0 keeps the material's
 * @param tint        RGB multiplier on the albedo, or null
 * @param effect      one of {@link #NONE}, {@link #IRIDESCENT}, {@link #FLOW}, {@link #GALAXY}, {@link #CIRCUIT}
 * @param effectColor display RGB (0..1) of the effect's light (glow, stars), or null for white
 * @param params      the effect's four numbers (see the spec table)
 */
public record MaterialLayer(@Nullable String pbr, float uvScale, @Nullable float[] tint, int effect,
                            @Nullable float[] effectColor, float[] params) {
    public static final int NONE = 0;
    /** Thin-film coating: hue shifts with the viewing angle and across the surface. */
    public static final int IRIDESCENT = 1;
    /** The {@code _s} alpha mask glows; a pulse runs along the weapon towards the muzzle. */
    public static final int FLOW = 2;
    /** A starfield and nebula deep inside the surface (parallax). */
    public static final int GALAXY = 3;
    /**
     * Neon circuit (asset spec §30): glowing traces laid out over the weapon's rest-pose space (seamless across the gun
     * and its attachments), light pulses running along them, the hue drifting.
     */
    public static final int CIRCUIT = 4;
    /**
     * Not a skin effect: a spray decal (asset spec §29). Its vertices' AO byte carries the decal's facing fade, which
     * the shader applies to the alpha instead of shading with it.
     */
    public static final int DECAL = 5;

    public MaterialLayer {
        params = params == null ? new float[4] : params;
    }

    /** Effect id of a data name ("iridescent", "flow", "galaxy"); unknown names draw no effect. */
    public static int effect(@Nullable String name) {
        if (name == null) return NONE;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "iridescent" -> IRIDESCENT;
            case "flow" -> FLOW;
            case "galaxy" -> GALAXY;
            case "circuit" -> CIRCUIT;
            default -> NONE;
        };
    }

    /** Defaults for the effect's parameters where the data leaves them out (spec §21). */
    public static float[] defaultParams(int effect) {
        return switch (effect) {
            case IRIDESCENT -> new float[]{0.8f, 1.0f, 0.05f, 6.0f};
            case FLOW -> new float[]{0.35f, 0.06f, 0.25f, 1.0f};
            case GALAXY -> new float[]{0.02f, 1.0f, 1.0f, 0.6f};
            case CIRCUIT -> new float[]{0.25f, 40.0f, 0.8f, 0.05f};
            default -> new float[4];
        };
    }
}
