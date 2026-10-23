package com.blockland.client.hud;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/**
 * The hit marker of modern shooters, shared by every weapon HUD: marks around the crosshair that pop and fade —
 * one colour for a hit, one for a critical one (headshot, backstab), one (and larger) for a kill. Shape, colours,
 * size, thickness and duration follow the player's style from the settings.
 */
public final class HitMarker {
    public enum Shape {
        /** four diagonal ticks */
        X,
        /** four straight ticks */
        CROSS,
        /** a ring */
        RING;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Shape of(String id) {
            for (Shape shape : values()) if (shape.id().equals(id)) return shape;
            return X;
        }
    }

    /** JSON-friendly style (stored in the settings file). */
    public static final class Style {
        public String shape = Shape.X.id();
        /** RGB */
        public int hitColor = 0xF2F4F7;
        public int criticalColor = 0xFFA235;
        public int killColor = 0xFF3B3B;
        public float size = 1f;
        public int thickness = 1;
        /** Multiplier of how long a marker stays. */
        public float duration = 1f;

        public void sanitize() {
            shape = Shape.of(shape).id();
            hitColor &= 0xFFFFFF;
            criticalColor &= 0xFFFFFF;
            killColor &= 0xFFFFFF;
            size = clamp(size, 0.5f, 2f);
            thickness = Math.max(1, Math.min(3, thickness));
            duration = clamp(duration, 0.5f, 2f);
        }

        private static float clamp(float v, float min, float max) {
            return Float.isNaN(v) ? 1f : Math.max(min, Math.min(max, v));
        }
    }

    private static Style style = new Style();
    private static final AnimValue alpha = new AnimValue(0);
    private static final Spring scale = new Spring(1).config(420, 18);
    private static int color = 0xFFF2F4F7;
    private static boolean kill;

    private HitMarker() {
    }

    public static void setStyle(Style next) {
        style = next == null ? new Style() : next;
    }

    public static void show(boolean critical, boolean killed) {
        color = 0xFF000000 | (killed ? style.killColor : critical ? style.criticalColor : style.hitColor);
        kill = killed;
        alpha.snap(1);
        alpha.to(0, (kill ? 0.55 : 0.32) * style.duration, Ease.QUAD_IN).delay(0.08 * style.duration);
        scale.set(kill ? 1.8f : 1.45f);
        scale.setTarget(1);
    }

    /** Draws the marker centred on a {@code w}×{@code h} GUI. */
    public static void render(GuiGraphics g, int w, int h) {
        render(g, w / 2f, h / 2f);
    }

    public static void render(GuiGraphics g, float cx, float cy) {
        float a = alpha.get();
        if (a < 0.02f) return;
        float s = scale.get() * style.size;
        int c = ColorMath.fade(color, a);
        int shade = ColorMath.fade(0xFF000000, a * 0.5f);
        int t = style.thickness;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(s, s);
        int inner = kill ? 4 : 3, length = kill ? 6 : 4;
        Shape shape = Shape.of(style.shape);
        if (shape == Shape.RING) {
            float radius = inner + length / 2f;
            int steps = Math.max(16, Math.round((float) (Math.PI * 2 * radius * 2)));
            for (int i = 0; i < steps; i++) {
                double angle = Math.PI * 2 * i / steps;
                float x = (float) Math.cos(angle) * radius, y = (float) Math.sin(angle) * radius;
                g.fill(Math.round(x) - 1, Math.round(y) - 1, Math.round(x) + t, Math.round(y) + t, shade);
            }
            for (int i = 0; i < steps; i++) {
                double angle = Math.PI * 2 * i / steps;
                int x = Math.round((float) Math.cos(angle) * radius), y = Math.round((float) Math.sin(angle) * radius);
                g.fill(x, y, x + t, y + t, c);
            }
        } else {
            float base = shape == Shape.X ? 45 : 0;
            for (int i = 0; i < 4; i++) {
                g.pose().pushMatrix();
                g.pose().rotate((float) Math.toRadians(base + 90 * i));
                g.fill(inner, -t, inner + length, t, shade);
                g.fill(inner, -t / 2, inner + length, -t / 2 + t, c);
                g.pose().popMatrix();
            }
        }
        g.pose().popMatrix();
    }
}
