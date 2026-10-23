package com.blockland.client.hud;

import com.blockland.anim.ColorMath;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/**
 * The weapon crosshair, shared by every weapon HUD and drawn in the player's own style (shape, colour, size, gap,
 * outline, whether it opens with the weapon's spread). The style comes from the settings; this class only draws.
 */
public final class Crosshair {
    public enum Shape {
        CROSS, CROSS_DOT, T, DOT, CIRCLE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Shape of(String id) {
            for (Shape shape : values()) if (shape.id().equals(id)) return shape;
            return CROSS_DOT;
        }
    }

    /** JSON-friendly style (stored in the settings file). */
    public static final class Style {
        public String shape = Shape.CROSS_DOT.id();
        /** RGB */
        public int color = 0xFFFFFF;
        public float opacity = 0.9f;
        /** Arm length in GUI pixels. */
        public int length = 5;
        public int thickness = 1;
        /** Extra gap added to the spread gap. */
        public int gap = 0;
        public boolean outline = true;
        /** The arms open with the current spread; otherwise they stay put. */
        public boolean dynamic = true;

        public void sanitize() {
            shape = Shape.of(shape).id();
            color &= 0xFFFFFF;
            opacity = Math.max(0.1f, Math.min(1f, Float.isNaN(opacity) ? 1f : opacity));
            length = Math.max(1, Math.min(14, length));
            thickness = Math.max(1, Math.min(4, thickness));
            gap = Math.max(0, Math.min(12, gap));
        }

        public Style copy() {
            Style s = new Style();
            s.shape = shape;
            s.color = color;
            s.opacity = opacity;
            s.length = length;
            s.thickness = thickness;
            s.gap = gap;
            s.outline = outline;
            s.dynamic = dynamic;
            return s;
        }
    }

    private static Style style = new Style();

    private Crosshair() {
    }

    public static void setStyle(Style next) {
        style = next == null ? new Style() : next;
    }

    public static Style style() {
        return style;
    }

    /** @param spreadGap where the weapon's spread cone meets the screen, in GUI pixels */
    public static void render(GuiGraphics g, float cx, float cy, float spreadGap, float alpha) {
        render(g, cx, cy, spreadGap, alpha, style);
    }

    public static void render(GuiGraphics g, float cx, float cy, float spreadGap, float alpha, Style s) {
        if (alpha < 0.02f) return;
        float a = alpha * s.opacity;
        int color = ColorMath.fade(0xFF000000 | s.color, a);
        int shadow = s.outline ? ColorMath.fade(0xFF000000, a * 0.55f) : 0;
        int t = s.thickness, len = s.length;
        float gap = (s.dynamic ? Math.max(3, spreadGap) : 3) + s.gap;
        float half = t / 2f;
        switch (Shape.of(s.shape)) {
            case CROSS, CROSS_DOT, T -> {
                Shape shape = Shape.of(s.shape);
                bar(g, cx - gap - len, cy - half, len, t, color, shadow);
                bar(g, cx + gap, cy - half, len, t, color, shadow);
                if (shape != Shape.T) bar(g, cx - half, cy - gap - len, t, len, color, shadow);
                bar(g, cx - half, cy + gap, t, len, color, shadow);
                if (shape == Shape.CROSS_DOT) bar(g, cx - half, cy - half, t, t, color, shadow);
            }
            case DOT -> {
                int size = t + 1;
                bar(g, cx - size / 2f, cy - size / 2f, size, size, color, shadow);
            }
            case CIRCLE -> {
                float radius = gap + len / 2f;
                if (shadow != 0) ring(g, cx, cy, radius, t + 2, shadow);
                ring(g, cx, cy, radius, t, color);
                bar(g, cx - half, cy - half, t, t, color, shadow);
            }
        }
    }

    private static void bar(GuiGraphics g, float x, float y, float w, float h, int color, int shadow) {
        int ix = Math.round(x), iy = Math.round(y), iw = Math.max(1, Math.round(w)), ih = Math.max(1, Math.round(h));
        if (ColorMath.alpha(shadow) > 0) g.fill(ix - 1, iy - 1, ix + iw + 1, iy + ih + 1, shadow);
        g.fill(ix, iy, ix + iw, iy + ih, color);
    }

    /** A ring of small squares, one per pixel of circumference. */
    private static void ring(GuiGraphics g, float cx, float cy, float radius, int thickness, int color) {
        int steps = Math.max(16, Math.round((float) (Math.PI * 2 * radius)));
        for (int i = 0; i < steps; i++) {
            double angle = Math.PI * 2 * i / steps;
            int x = Math.round(cx + (float) Math.cos(angle) * radius - thickness / 2f);
            int y = Math.round(cy + (float) Math.sin(angle) * radius - thickness / 2f);
            g.fill(x, y, x + thickness, y + thickness, color);
        }
    }
}
