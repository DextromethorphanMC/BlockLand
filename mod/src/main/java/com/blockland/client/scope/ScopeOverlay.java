package com.blockland.client.scope;

import com.blockland.BlockLand;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.craft.CraftData;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ARGB;

import static com.blockland.client.ui.Tr.tr;

/**
 * The scope's HUD layer. With picture in picture the scope model and its eyepiece do the optics; this layer only adds
 * the readouts: the power a variable scope was just set to and the breath meter of a scope that can be steadied. With
 * classic zoom it is the scope itself: a black mask with the eyepiece's round window, the rim vignette and the
 * (mipmapped) reticle, and a short black blink that hides the jump of the view.
 */
final class ScopeOverlay {
    private static final int SIZE = 256;
    /** Radius of the zoomed window as a share of the screen height. */
    private static final float WINDOW = 0.46f;
    /** The power readout shows this long after the power changed or the scope came up (s). */
    private static final float POWER_SHOWN = 1.6f;
    /** The hold-breath hint shows for this long after the scope came up (s). */
    private static final float HINT_SHOWN = 3.5f;
    private static ResourceLocation mask;
    private static float maskVignette = -1;
    private static long raisedAt;
    private static boolean wasRaised;

    private ScopeOverlay() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int w = g.guiWidth(), h = g.guiHeight();
        CraftData.Attachment sight = Scopes.sight();
        if (Scopes.zoomed() && sight != null) drawZoomed(g, w, h, sight.scope);
        float blink = Scopes.blink();
        if (blink > 0.01f) g.fill(0, 0, w, h, ARGB.color(Math.round(255 * blink), 0, 0, 0));

        boolean raised = sight != null && Scopes.aim() > 0.9f;
        if (raised && !wasRaised) raisedAt = System.nanoTime();
        wasRaised = raised;
        if (!raised) return;
        // readouts sit under the eyepiece (or the zoomed window)
        float cx, bottom;
        if (Scopes.zoomed()) {
            cx = w / 2f;
            bottom = h / 2f + h * WINDOW;
        } else if (Scopes.lensOnScreen()) {
            cx = (Scopes.lensX() * 0.5f + 0.5f) * w;
            bottom = (0.5f - Scopes.lensY() * 0.5f) * h + Scopes.lensRadius() * 0.5f * h;
        } else {
            return;
        }
        float sinceRaised = (System.nanoTime() - raisedAt) / 1e9f;
        drawPower(g, cx, bottom, Math.min(sinceRaised, Scopes.sinceZoomChange()));
        if (sight.scope.holdBreath > 0) drawBreath(g, cx, Math.min(bottom + 6, h - 40), sinceRaised);
    }

    /** "6×" under the eyepiece for a moment after the power changed (variable scopes only). */
    private static void drawPower(GuiGraphics g, float cx, float y, float since) {
        if (!Scopes.variableZoom() || since > POWER_SHOWN) return;
        float alpha = Math.min(1, (POWER_SHOWN - since) / 0.4f);
        String text = tr("blockland.scope.power", String.format("%.1f", Scopes.magnification()));
        Draw.text(g, text, cx - UiFont.width(text) / 2f, y - 14, 1f, ARGB.color(Math.round(230 * alpha), 255, 255, 255), true);
    }

    /** The breath meter (and, at first, how to hold the breath). */
    private static void drawBreath(GuiGraphics g, float cx, float y, float sinceRaised) {
        ScopeSteady steady = Scopes.steady();
        float breath = steady.breath();
        boolean full = breath > 0.99f && !steady.holding();
        if (full && sinceRaised > HINT_SHOWN) return;
        int width = 64, height = 3;
        int x = Math.round(cx - width / 2f), top = Math.round(y);
        g.fill(x - 1, top - 1, x + width + 1, top + height + 1, ARGB.color(120, 0, 0, 0));
        int fill = steady.winded() ? ARGB.color(220, 235, 90, 70) : steady.holding() ? ARGB.color(230, 140, 220, 255) : ARGB.color(200, 255, 255, 255);
        g.fill(x, top, x + Math.round(width * breath), top + height, fill);
        if (full) {
            String key = Minecraft.getInstance().options.keySprint.getTranslatedKeyMessage().getString();
            String hint = tr("blockland.scope.hold_breath", key);
            float alpha = Math.min(1, (HINT_SHOWN - sinceRaised) / 0.6f);
            Draw.text(g, hint, cx - UiFont.width(hint) / 2f, top + 6, 1f, ARGB.color(Math.round(200 * alpha), 255, 255, 255), true);
        }
    }

    /** The scope filling the screen: black around a round window, the rim vignette, the reticle. */
    private static void drawZoomed(GuiGraphics g, int w, int h, CraftData.Scope scope) {
        ensureMask(scope.vignette);
        int radius = Math.round(h * WINDOW);
        int x = w / 2 - radius, y = h / 2 - radius, d = radius * 2;
        int black = ARGB.color(255, 0, 0, 0);
        g.blit(RenderPipelines.GUI_TEXTURED, mask, x, y, 0, 0, d, d, SIZE, SIZE, SIZE, SIZE, black);
        g.fill(0, 0, w, y, black);
        g.fill(0, y + d, w, h, black);
        g.fill(0, y, x, y + d, black);
        g.fill(x + d, y, w, y + d, black);
        ResourceLocation reticle = ReticleTextures.gui(scope.reticle);
        if (reticle != null) {
            int size = Math.round(d * Scopes.reticleSize());
            g.blit(RenderPipelines.GUI_TEXTURED, reticle, w / 2 - size / 2, h / 2 - size / 2, 0, 0, size, size, 1, 1, 1, 1, -1);
        }
    }

    // ---- generated textures (white, the colour comes from the blit) --------------------------------------------------

    private static void ensureMask(float vignette) {
        if (mask != null && maskVignette == vignette) return;
        maskVignette = vignette;
        NativeImage image = new NativeImage(SIZE, SIZE, true);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double dx = (x + 0.5) / (SIZE / 2.0) - 1, dy = (y + 0.5) / (SIZE / 2.0) - 1;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float edge = ScopeOptics.smoothstep(0.965f, 1.0f, r);
                float rim = 1 - ScopeOptics.vignette(r, vignette);
                image.setPixel(x, y, (Math.round(Math.max(edge, rim) * 255) << 24) | 0xFFFFFF);
            }
        }
        ResourceLocation location = BlockLand.id("scope/mask");
        if (mask != null) Minecraft.getInstance().getTextureManager().release(mask);
        DynamicTexture texture = new DynamicTexture(location::toString, image);
        texture.setFilter(true, false);
        texture.setClamp(true);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        mask = location;
    }
}
