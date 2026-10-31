package com.blockland.client.backpack;

/**
 * Moving an inventory screen by its header (UI spec §1): held down on the header's free space and dragged, the panel
 * (with the creative sidebar or the recipe book beside it) follows the mouse. The place is an offset from the centred
 * one, shared by the survival and creative screens and kept while the game runs; it is applied inside the screen, so a
 * smaller window or a taller panel (the backpack opening) pushes it back in view.
 */
final class PanelDrag {
    private static int offsetX, offsetY;
    /** The offset as last applied (inside the screen): where a release leaves the panel. */
    private static int shownX, shownY;

    private boolean dragging;
    private double grabX, grabY;
    private int startX, startY;

    /**
     * The shift for a box centred at ({@code x}, {@code y}) of {@code w} × {@code h}: the offset, kept inside the
     * screen (none on an axis the box does not fit).
     */
    static int[] apply(int x, int y, int w, int h, int screenW, int screenH) {
        shownX = clamp(offsetX, -x, screenW - w - x);
        shownY = clamp(offsetY, -y, screenH - h - y);
        return new int[]{shownX, shownY};
    }

    private static int clamp(int v, int lo, int hi) {
        return lo > hi ? 0 : Math.max(lo, Math.min(hi, v));
    }

    boolean dragging() {
        return dragging;
    }

    void press(double mouseX, double mouseY) {
        dragging = true;
        grabX = mouseX;
        grabY = mouseY;
        startX = shownX;
        startY = shownY;
    }

    void drag(double mouseX, double mouseY) {
        offsetX = startX + (int) Math.round(mouseX - grabX);
        offsetY = startY + (int) Math.round(mouseY - grabY);
    }

    void release() {
        dragging = false;
        offsetX = shownX;
        offsetY = shownY;
    }
}
