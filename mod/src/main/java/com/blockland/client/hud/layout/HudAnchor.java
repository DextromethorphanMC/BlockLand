package com.blockland.client.hud.layout;

/**
 * The nine points of the screen a HUD element holds on to (UI spec §3): a corner, the middle of an edge, or the centre.
 * An element keeps its own same point at a fixed offset from the screen's, so it stays in its corner (or centred)
 * whatever the window's size or the GUI scale.
 */
public enum HudAnchor {
    TOP_LEFT(0, 0), TOP(0.5f, 0), TOP_RIGHT(1, 0),
    LEFT(0, 0.5f), CENTER(0.5f, 0.5f), RIGHT(1, 0.5f),
    BOTTOM_LEFT(0, 1), BOTTOM(0.5f, 1), BOTTOM_RIGHT(1, 1);

    /** Where the point is across and down, 0 … 1. */
    public final float fx, fy;

    HudAnchor(float fx, float fy) {
        this.fx = fx;
        this.fy = fy;
    }

    /** The anchor of the third of the screen a box's centre is in (the editor picks it when an element is dropped). */
    public static HudAnchor nearest(float centreX, float centreY, int screenW, int screenH) {
        int col = centreX < screenW / 3f ? 0 : centreX > screenW * 2 / 3f ? 2 : 1;
        int row = centreY < screenH / 3f ? 0 : centreY > screenH * 2 / 3f ? 2 : 1;
        return values()[row * 3 + col];
    }

    public static HudAnchor of(String name, HudAnchor fallback) {
        if (name != null) {
            for (HudAnchor a : values()) if (a.name().equals(name)) return a;
        }
        return fallback;
    }
}
