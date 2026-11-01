package com.blockland.client.hud.layout;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * One movable piece of the HUD (UI spec §3): its module draws it wherever the layout puts it; the player moves it in
 * the HUD editor (设置 → HUD). Registered with {@link HudLayout#register}; the layout knows nothing else about it.
 */
public interface HudElement {
    /** Stable id, the key of its saved place: {@code health}, {@code hotbar}, {@code minimap}, … */
    String id();

    /** Language key of its name in the editor. */
    String nameKey();

    /** Its box now, GUI pixels (it may change with its settings: the chat's width, the minimap's size). */
    int width();

    int height();

    /** Where it sits until the player moves it: a point of the screen and the offset of its own same point from there. */
    HudAnchor defaultAnchor();

    int defaultX();

    int defaultY();

    /** Whether there is something to draw now (the food bar not in creative, the vehicle panel only riding). */
    boolean visible();

    /** Drawn with the late HUD (after titles, where the chat is) instead of with the hotbar. */
    default boolean late() {
        return false;
    }

    /**
     * A choice the editor offers for this element while it is selected (the hotbar's 横 / 竖): the first value is the
     * default; each value's name is the language key {@code nameKey + "." + value}. Read it with {@link HudLayout#option}.
     */
    record Option(String key, String nameKey, List<String> values) {
    }

    /** The element's choices (none by default). */
    default List<Option> options() {
        return List.of();
    }

    /**
     * How the player may resize it in the editor by its corner grip (UI spec §3.1): the box it starts at and the smallest
     * and largest it may become, GUI pixels; {@code square} keeps the width and height equal. The size the player chose
     * is {@link HudLayout#size}: a resizable element's {@link #width} / {@link #height} return it.
     */
    record Resize(int defaultW, int defaultH, int minW, int minH, int maxW, int maxH, boolean square) {
    }

    /** Its resizing limits, or null when its size is its own (the default). */
    default Resize resize() {
        return null;
    }

    /**
     * Draws it with its box's top-left corner at ({@code x}, {@code y}). {@code editing}: in the HUD editor, where every
     * element is drawn — with a sample of what it shows when there is nothing to show now.
     */
    void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing);
}
