package com.blockland.client.hud;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The hit marker of modern shooters, shared by every weapon HUD: four ticks around the crosshair that pop and fade —
 * white for a hit, orange for a critical one (headshot, backstab), red and larger for a kill.
 */
public final class HitMarker {
    private static final int HIT = 0xFFF2F4F7;
    private static final int CRITICAL = 0xFFFFA235;
    private static final int KILL = 0xFFFF3B3B;

    private static final AnimValue alpha = new AnimValue(0);
    private static final Spring scale = new Spring(1).config(420, 18);
    private static int color = HIT;
    private static boolean kill;

    private HitMarker() {
    }

    public static void show(boolean critical, boolean killed) {
        color = killed ? KILL : critical ? CRITICAL : HIT;
        kill = killed;
        alpha.snap(1);
        alpha.to(0, kill ? 0.55 : 0.32, Ease.QUAD_IN).delay(0.08);
        scale.set(kill ? 1.8f : 1.45f);
        scale.setTarget(1);
    }

    /** Draws the marker centred on a {@code w}×{@code h} GUI. */
    public static void render(GuiGraphics g, int w, int h) {
        float a = alpha.get();
        if (a < 0.02f) return;
        float s = scale.get();
        int c = ColorMath.fade(color, a);
        g.pose().pushMatrix();
        g.pose().translate(w / 2f, h / 2f);
        g.pose().scale(s, s);
        int inner = kill ? 4 : 3, length = kill ? 6 : 4;
        for (int i = 0; i < 4; i++) {
            g.pose().pushMatrix();
            g.pose().rotate((float) Math.toRadians(45 + 90 * i));
            g.fill(inner, -1, inner + length, 1, ColorMath.fade(0xFF000000, a * 0.5f));
            g.fill(inner, 0, inner + length, 1, c);
            g.pose().popMatrix();
        }
        g.pose().popMatrix();
    }
}
