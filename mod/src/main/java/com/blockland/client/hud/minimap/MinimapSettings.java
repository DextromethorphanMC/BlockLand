package com.blockland.client.hud.minimap;

/** The minimap's options (UI spec §3.4), kept in {@code blockland-settings.json} as {@code minimap}. */
public final class MinimapSettings {
    /** Side of the map, GUI pixels. */
    public int size = 96;
    /** Blocks across the map. */
    public int range = 96;
    /** The map turns with the player (else north stays up). */
    public boolean rotate = true;

    public void sanitize() {
        size = Math.max(48, Math.min(192, size));
        range = Math.max(32, Math.min(256, range));
    }
}
