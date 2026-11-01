package com.blockland.client.hud.vanilla;

import com.blockland.anim.ColorMath;
import com.blockland.client.ui.Draw;
import net.minecraft.client.gui.GuiGraphics;

/** The bars' shared look (UI spec §3.2): a dark rounded track with a hairline rim, fills, and their colours. */
final class Bars {
    static final int TEXT = 0xFFF2F4F7, MUTED = 0xFF9AA4B5, TRACK = 0xA0101418, RIM = 0x30FFFFFF;
    static final int HEALTH = 0xFFE5484D, ABSORPTION = 0xFFF2C14E, POISON = 0xFF8DB43A, WITHER = 0xFF55555E,
            FROZEN = 0xFF8FD3F0, TRAIL = 0xFFF6D2D0, HEAL = 0xFF7CDB5A;
    static final int FOOD = 0xFFE09A3E, HUNGER = 0xFF8AA03A, SATURATION = 0xFFFFE07A, AIR = 0xFF5AA8F0;
    static final int ARMOR = 0xFFC8D0DA, TOUGHNESS = 0xFF7FB4E6;
    static final int EXPERIENCE = 0xFF8BE04A;
    /** Bar height and the gap between glyph, bar and number. */
    static final int HEIGHT = 5, GAP = 3;

    private Bars() {
    }

    /** The empty track of a bar. */
    static void track(GuiGraphics g, int x, int y, int w, int h) {
        Draw.rect(g, x - 1, y - 1, w + 2, h + 2, TRACK);
        Draw.outline(g, x - 1, y - 1, w + 2, h + 2, RIM);
    }

    /**
     * Fills {@code share} (0 … 1) of a bar from {@code from} (0 … 1 of its length) towards its end; {@code fromRight}
     * mirrors it (the food bar fills from the right, by its glyph). A lighter top row gives it a little shape.
     */
    static void fill(GuiGraphics g, int x, int y, int w, int h, float from, float share, int color, boolean fromRight) {
        int a = Math.round(w * clamp(from)), b = Math.round(w * clamp(from + share));
        if (b <= a) return;
        int x0 = fromRight ? x + w - b : x + a, x1 = fromRight ? x + w - a : x + b;
        g.fill(x0, y, x1, y + h, color);
        g.fill(x0, y, x1, y + 1, ColorMath.lighten(color, 0.25f));
    }

    /** A soft band of {@code width} pixels at {@code at} (0 … 1 along the filled part) brightening the fill. */
    static void sheen(GuiGraphics g, int x, int y, int filled, int h, float at, int width, boolean fromRight, float alpha) {
        if (filled <= 0 || alpha <= 0.01f) return;
        int centre = Math.round(filled * at);
        for (int i = -width; i <= width; i++) {
            int px = centre + i;
            if (px < 0 || px >= filled) continue;
            float a = alpha * (1 - Math.abs(i) / (float) (width + 1));
            int sx = fromRight ? x + filled - 1 - px : x + px;
            g.fill(sx, y, sx + 1, y + h, ColorMath.fade(0xFFFFFFFF, a * 0.55f));
        }
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
