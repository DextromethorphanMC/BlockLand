package com.blockland.client.melee;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.client.font.UiFont;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.ui.Draw;
import com.blockland.melee.MeleeData;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import static com.blockland.client.ui.Tr.tr;

/**
 * Knife HUD, matching the gun HUD's layout: name and the two attacks with a thin readiness bar (a {@link Panel} of the
 * movable HUD, bottom right by default), the shared {@link HitMarker} and a short call-out for backstabs. The vanilla
 * crosshair stays.
 */
final class KnifeHud {
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;
    private static final int CALLOUT = 0xFFFFA235;

    private static String callout = "";
    private static final AnimValue calloutAlpha = new AnimValue(0);
    private static final AnimValue barAlpha = new AnimValue(0);

    private KnifeHud() {
    }

    static void flash(String text) {
        callout = text;
        calloutAlpha.snap(1);
        calloutAlpha.to(0, 0.5, Ease.QUAD_IN).delay(0.7);
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        MeleeData.Knife knife = ClientKnives.knife();
        if (mc.player == null || knife == null || !ClientKnives.holdingKnife() || mc.options.hideGui) return;
        int w = g.guiWidth(), h = g.guiHeight();
        HitMarker.render(g, w, h);
        float c = calloutAlpha.get();
        if (c > 0.02f && !callout.isEmpty()) {
            float scale = 1.4f;
            Draw.text(g, callout, w / 2f - UiFont.width(callout) * scale / 2f, h / 2f + 18, scale, ColorMath.fade(CALLOUT, c), true);
        }
    }

    /** The knife's name, its attacks and readiness drawn from the panel's right edge and bottom. */
    private static void panel(GuiGraphics g, int right, int bottom, String name, float ready) {
        Draw.glow(g, right - 60, bottom - 22, 100, 36, 0x66000000);
        Draw.text(g, name, right - UiFont.width(name), bottom - 26, 1f, TEXT, true);
        String attacks = tr("blockland.knife.hud.controls");
        Draw.text(g, attacks, right - UiFont.width(attacks), bottom - 14, 1f, MUTED, true);
        // readiness: shows only while the knife recovers
        barAlpha.toIfChanged(ready < 1 ? 1 : 0, 0.15, Ease.QUAD_OUT);
        float a = barAlpha.get();
        if (a > 0.02f) {
            int barW = UiFont.width(attacks), x0 = right - barW, y = bottom - 3;
            g.fill(x0, y, right, y + 1, ColorMath.fade(0x30FFFFFF, a));
            g.fill(x0, y, x0 + Math.round(barW * ready), y + 1, ColorMath.fade(0xFFFFFFFF, a));
        }
    }

    /** The knife's panel as an element of the movable HUD (UI spec §3); a sample in the editor. */
    static final class Panel implements HudElement {
        private static final int W = 200, H = 28;

        @Override
        public String id() {
            return "knife";
        }

        @Override
        public String nameKey() {
            return "blockland.hud.element.knife";
        }

        @Override
        public int width() {
            return W;
        }

        @Override
        public int height() {
            return H;
        }

        @Override
        public HudAnchor defaultAnchor() {
            return HudAnchor.BOTTOM_RIGHT;
        }

        @Override
        public int defaultX() {
            return -14;
        }

        @Override
        public int defaultY() {
            return -14;
        }

        @Override
        public boolean visible() {
            return Minecraft.getInstance().player != null && ClientKnives.knife() != null && ClientKnives.holdingKnife();
        }

        @Override
        public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
            MeleeData.Knife knife = ClientKnives.knife();
            if (knife != null && ClientKnives.holdingKnife()) {
                panel(g, x + W, y + H, knife.displayName().getString(), ClientKnives.readiness());
            } else if (editing) {
                panel(g, x + W, y + H, tr("blockland.hud.sample.knife"), 1);
            }
        }
    }
}
