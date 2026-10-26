package com.blockland.client.scope;

import com.blockland.BlockLand;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import static com.blockland.client.ui.Tr.tr;

/**
 * The view through a hand-held optic ({@link HandOptics}; binoculars, asset spec §52), as a HUD layer: black around two
 * round apertures side by side that overlap in the middle — a soft edge tinted by the lens coating, darkening towards
 * the rim — a mil scale in the middle (marks at true angles for the power: 1 mil = 1 m at 1000 m), the power and the
 * compass bearing above, the rangefinder's distance below. While the optic comes up the screen darkens and the switch
 * itself is a short black blink, which hides the jump of the field of view.
 */
final class HandOpticOverlay {
    /** Height of the generated mask (its width follows the apertures' layout). */
    private static final int MASK_HEIGHT = 256;
    /** The blink at the switch (s). */
    private static final float BLINK = 0.14f;
    /** The power readout is bright this long after a step (s), then dims. */
    private static final float POWER_BRIGHT = 1.2f;
    private static ResourceLocation mask;
    private static int maskWidth;
    private static String maskKey = "";

    private HandOpticOverlay() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        HandOptic optic = HandOptics.optic();
        Minecraft mc = Minecraft.getInstance();
        if (optic == null || mc.player == null) return;
        int w = g.guiWidth(), h = g.guiHeight();
        if (!HandOptics.viewing()) {
            float dark = 0.85f * HandOptics.approach();
            if (HandOptics.sinceSwitch() < BLINK) dark = Math.max(dark, 1 - HandOptics.sinceSwitch() / BLINK);
            if (dark > 0.01f) g.fill(0, 0, w, h, ARGB.color(Math.round(255 * Math.min(1, dark)), 0, 0, 0));
            return;
        }
        drawMask(g, w, h, optic.overlay);
        drawReticle(g, w, h, optic.reticle);
        drawReadouts(g, w, h, optic);
        float blink = 1 - HandOptics.sinceSwitch() / BLINK;
        if (blink > 0.01f) g.fill(0, 0, w, h, ARGB.color(Math.round(255 * blink), 0, 0, 0));
    }

    /** The two apertures, black all round them. */
    private static void drawMask(GuiGraphics g, int w, int h, HandOptic.Overlay overlay) {
        ensureMask(overlay);
        float radius = Math.max(0.05f, overlay.radius) * h;
        float separation = Math.max(0, overlay.separation) * h;
        int maskW = Math.round(2 * (separation + radius)), maskH = Math.round(2 * radius);
        int x = w / 2 - maskW / 2, y = h / 2 - maskH / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, mask, x, y, 0, 0, maskW, maskH, maskWidth, MASK_HEIGHT, maskWidth, MASK_HEIGHT, -1);
        int black = ARGB.color(255, 0, 0, 0);
        g.fill(0, 0, w, y, black);
        g.fill(0, y + maskH, w, h, black);
        g.fill(0, y, x, y + maskH, black);
        g.fill(x + maskW, y, w, y + maskH, black);
    }

    /**
     * The mil scale: a horizontal line of ±{@code span} mils with a mark every 5 (longer every 10, numbered in tens), a
     * stadia down to {@code stadia} mils, a gap in the middle. The marks keep their angles in the world at every power.
     */
    private static void drawReticle(GuiGraphics g, int w, int h, HandOptic.Reticle reticle) {
        int colour = ARGB.color(Math.round(255 * Mth.clamp(reticle.alpha, 0, 1)), HandOptic.rgb(reticle.color, 0x0C0C0C));
        float tanHalf = (float) Math.tan(Math.toRadians(Optics.worldFov()) / 2);
        float perMil = h / 2f * (float) Math.tan(0.001) / Math.max(1e-4f, tanHalf);
        // a mark every 5 mils while they stay apart, else every 10
        int step = perMil * 5 >= 5 ? 5 : 10;
        int cx = w / 2, cy = h / 2;
        int gap = Math.max(3, Math.round(perMil * 2));
        int span = Math.max(step, reticle.span);
        int half = Math.round(perMil * span);
        g.fill(cx - half, cy, cx - gap, cy + 1, colour);
        g.fill(cx + gap, cy, cx + half, cy + 1, colour);
        for (int mil = step; mil <= span; mil += step) {
            int dx = Math.round(perMil * mil);
            boolean major = mil % 10 == 0;
            int len = major ? 4 : 2;
            g.fill(cx - dx, cy - len, cx - dx + 1, cy + 1 + len, colour);
            g.fill(cx + dx, cy - len, cx + dx + 1, cy + 1 + len, colour);
            if (major && perMil * 10 >= 14) {
                String label = String.valueOf(mil / 10);
                float lw = UiFont.width(label) * 0.6f;
                Draw.text(g, label, cx + dx - lw / 2, cy - len - 7, 0.6f, colour, false);
                Draw.text(g, label, cx - dx - lw / 2, cy - len - 7, 0.6f, colour, false);
            }
        }
        // the stadia below the middle
        int down = Math.round(perMil * Math.max(step, reticle.stadia));
        g.fill(cx, cy + gap, cx + 1, cy + down, colour);
        for (int mil = step; mil <= reticle.stadia; mil += step) {
            int dy = Math.round(perMil * mil);
            int len = mil % 10 == 0 ? 3 : 1;
            g.fill(cx - len, cy + dy, cx + 1 + len, cy + dy + 1, colour);
        }
        // a small aiming mark in the gap
        g.fill(cx, cy, cx + 1, cy + 1, colour);
    }

    /** The power and bearing above the reticle, the measured distance below it. */
    private static void drawReadouts(GuiGraphics g, int w, int h, HandOptic optic) {
        Minecraft mc = Minecraft.getInstance();
        float radius = Math.max(0.05f, optic.overlay.radius) * h;
        float cx = w / 2f;
        float top = h / 2f - radius * 0.78f;
        float since = HandOptics.sinceZoomChange();
        int powerAlpha = since < POWER_BRIGHT ? 235 : 170;
        float power = HandOptics.targetMagnification();
        String powerText = tr("blockland.binoculars.power", power == Math.round(power) ? String.valueOf(Math.round(power)) : String.format("%.1f", power));
        float bearing = Mth.positiveModulo(mc.gameRenderer.getMainCamera().getYRot() + 180f, 360f);
        String bearingText = tr("blockland.binoculars.bearing", String.format("%03d", Math.round(bearing) % 360));
        int white = ARGB.color(powerAlpha, 235, 240, 235);
        Draw.text(g, powerText, cx - UiFont.width(powerText) / 2f, top, 1f, white, true);
        Draw.text(g, bearingText, cx - UiFont.width(bearingText) * 0.8f / 2f, top + 11, 0.8f, ARGB.color(170, 235, 240, 235), true);
        if (optic.rangefinder == null) return;
        float range = HandOptics.range();
        String distance = Float.isNaN(range) ? tr("blockland.binoculars.range_none") : tr("blockland.binoculars.range", String.valueOf(Math.round(range)));
        float bottom = h / 2f + radius * 0.62f;
        int red = ARGB.color(230, 255, 92, 72);
        Draw.text(g, distance, cx - UiFont.width(distance) / 2f, bottom, 1f, red, true);
    }

    // ---- the generated mask (rgb: the lens edge's tint, black beyond; alpha: what it covers) ---------------------------

    private static void ensureMask(HandOptic.Overlay overlay) {
        float radius = Math.max(0.05f, overlay.radius), separation = Math.max(0, overlay.separation);
        float softness = Mth.clamp(overlay.softness, 0.005f, 0.5f);
        int fringe = HandOptic.rgb(overlay.fringe, 0x284664);
        String key = radius + "/" + separation + "/" + softness + "/" + overlay.vignette + "/" + fringe;
        if (mask != null && key.equals(maskKey)) return;
        maskKey = key;
        float s = separation / radius;
        int width = Math.max(MASK_HEIGHT, Math.round(MASK_HEIGHT * (s + 1)));
        NativeImage image = new NativeImage(width, MASK_HEIGHT, true);
        float fr = (fringe >> 16 & 255) / 255f, fg = (fringe >> 8 & 255) / 255f, fb = (fringe & 255) / 255f;
        for (int y = 0; y < MASK_HEIGHT; y++) {
            for (int x = 0; x < width; x++) {
                // in units of the radius: the apertures' centres at (±s, 0)
                double u = ((x + 0.5) / width * 2 - 1) * (s + 1), v = (y + 0.5) / MASK_HEIGHT * 2 - 1;
                float d = (float) Math.min(Math.hypot(u - s, v), Math.hypot(u + s, v));
                float edge = ScopeOptics.smoothstep(1 - softness, 1, d);
                float rim = 1 - ScopeOptics.vignette(d, overlay.vignette);
                float alpha = Math.max(edge, rim);
                // the coating's colour shows in the soft edge, going to black as it closes
                float tint = ScopeOptics.smoothstep(1 - softness * 1.8f, 1 - softness * 0.7f, d) * (1 - ScopeOptics.smoothstep(1 - softness * 0.4f, 1, d));
                int r = Math.round(255 * fr * tint), gr = Math.round(255 * fg * tint), b = Math.round(255 * fb * tint);
                image.setPixel(x, y, (Math.round(alpha * 255) << 24) | (r << 16) | (gr << 8) | b);
            }
        }
        ResourceLocation location = BlockLand.id("scope/hand_optic_mask");
        if (mask != null) Minecraft.getInstance().getTextureManager().release(mask);
        DynamicTexture texture = new DynamicTexture(location::toString, image);
        texture.setFilter(true, false);
        texture.setClamp(true);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        mask = location;
        maskWidth = width;
    }
}
