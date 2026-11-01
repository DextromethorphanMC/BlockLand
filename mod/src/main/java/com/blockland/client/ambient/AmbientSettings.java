package com.blockland.client.ambient;

/** The ambient props' settings (画质 → 环境, rendering spec §29), kept in {@code blockland-settings.json} as {@code ambient}. */
public final class AmbientSettings {
    /** Tumbleweeds roll over deserts and the maps that ask for them. */
    public boolean tumbleweeds = true;
    /** How many of them, a share of the data's count (0.25 … 2). */
    public float tumbleweedDensity = 1;

    public void sanitize() {
        tumbleweedDensity = Float.isNaN(tumbleweedDensity) ? 1 : Math.max(0.25f, Math.min(2f, tumbleweedDensity));
    }
}
