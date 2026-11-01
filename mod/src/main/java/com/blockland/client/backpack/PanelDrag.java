package com.blockland.client.backpack;

import com.blockland.client.settings.ClientSettings;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.IntFunction;

/**
 * Where an inventory screen's panel is and how big (UI spec §1): held on the header's free space and dragged, the
 * panel (with the creative sidebar or the recipe book beside it) follows the mouse; the grip in its bottom-right corner
 * resizes it — wider and taller, with bigger cells and items, its labels as they are ({@link InventoryLayout#setCell}) —
 * its top-left corner staying put. The place is an offset from the centred one, shared by the survival and creative
 * screens and kept while the game runs; the cell size is kept in the settings. Both are applied inside the screen, so a
 * smaller window or a taller panel (the backpack opening) pushes it back in view.
 */
final class PanelDrag {
    /** The grip's side (pixels). */
    static final int GRIP = 10;

    private static int offsetX, offsetY;
    /** The offset as last applied (inside the screen): where a release leaves the panel. */
    private static int shownX, shownY;

    private boolean moving, resizing;
    private double grabX, grabY;
    private int startX, startY;
    private int cornerX, cornerY, startW, startH, screenW, screenH;
    private IntFunction<int[]> sizeFor = c -> new int[]{0, 0};

    /** The cell size the player chose. */
    static int cell() {
        return ClientSettings.get().inventoryCell;
    }

    /**
     * Places a box of {@code w} × {@code h}: centred, shifted by the offset and kept inside the screen (not shifted on an
     * axis it does not fit). Returns its top-left corner.
     */
    static int[] place(int w, int h, int screenW, int screenH) {
        int cx = (screenW - w) / 2, cy = (screenH - h) / 2;
        shownX = clamp(offsetX, -cx, screenW - w - cx);
        shownY = clamp(offsetY, -cy, screenH - h - cy);
        return new int[]{cx + shownX, cy + shownY};
    }

    private static int clamp(int v, int lo, int hi) {
        return lo > hi ? 0 : Math.max(lo, Math.min(hi, v));
    }

    /** Whether (mx, my) is on the grip of a panel whose bottom-right corner is ({@code right}, {@code bottom}). */
    static boolean overGrip(double mx, double my, int right, int bottom) {
        return mx >= right - GRIP && mx < right && my >= bottom - GRIP && my < bottom;
    }

    /** The grip: three short diagonal strokes in the corner, brighter while hovered or held. */
    static void drawGrip(GuiGraphics g, int right, int bottom, float hover) {
        int color = InventoryStyle.blend(InventoryStyle.dim(), InventoryStyle.text(), hover);
        for (int i = 0; i < 3; i++) {
            int len = 3 + i * 3;
            for (int k = 0; k < len; k++) g.fill(right - 3 - k, bottom - 3 - len + 1 + k, right - 2 - k, bottom - 2 - len + 1 + k, color);
        }
    }

    boolean active() {
        return moving || resizing;
    }

    boolean resizing() {
        return resizing;
    }

    /** Starts moving the panel. */
    void press(double mouseX, double mouseY) {
        moving = true;
        grabX = mouseX;
        grabY = mouseY;
        startX = shownX;
        startY = shownY;
    }

    /**
     * Starts resizing the box whose top-left corner is at ({@code x}, {@code y}); {@code sizeFor} gives its size for a
     * cell size (laying it out for that size as it goes).
     */
    void pressGrip(double mouseX, double mouseY, int x, int y, int screenW, int screenH, IntFunction<int[]> sizeFor) {
        resizing = true;
        grabX = mouseX;
        grabY = mouseY;
        cornerX = x;
        cornerY = y;
        this.screenW = screenW;
        this.screenH = screenH;
        this.sizeFor = sizeFor;
        int[] size = sizeFor.apply(cell());
        startW = size[0];
        startH = size[1];
    }

    void drag(double mouseX, double mouseY) {
        if (resizing) {
            // the cell size whose bottom-right corner comes nearest the mouse, of those that fit on the screen
            double wantW = startW + (mouseX - grabX), wantH = startH + (mouseY - grabY);
            int best = cell();
            double bestMiss = Double.MAX_VALUE;
            for (int c = InventoryLayout.MIN_CELL; c <= InventoryLayout.MAX_CELL; c++) {
                int[] size = sizeFor.apply(c);
                if (c > InventoryLayout.MIN_CELL && (size[0] > screenW || size[1] > screenH)) continue;
                double miss = Math.abs(size[0] - wantW) + Math.abs(size[1] - wantH);
                if (miss < bestMiss) {
                    bestMiss = miss;
                    best = c;
                }
            }
            ClientSettings.get().inventoryCell = best;
            int[] size = sizeFor.apply(best);
            // the top-left corner stays where it is
            offsetX = cornerX - (screenW - size[0]) / 2;
            offsetY = cornerY - (screenH - size[1]) / 2;
            return;
        }
        offsetX = startX + (int) Math.round(mouseX - grabX);
        offsetY = startY + (int) Math.round(mouseY - grabY);
    }

    void release() {
        if (resizing) ClientSettings.save();
        moving = false;
        resizing = false;
        offsetX = shownX;
        offsetY = shownY;
    }
}
