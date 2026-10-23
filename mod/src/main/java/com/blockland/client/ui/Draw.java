package com.blockland.client.ui;

import com.blockland.BlockLand;
import com.blockland.anim.ColorMath;
import com.blockland.client.ClientMedia;
import com.blockland.client.font.UiFont;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Drawing helpers for animated GUIs: soft glows and gradient fades (generated textures instead of hard-edged
 * rectangles), "cover"-fitted images, and float-positioned/scaled/faded text so animations can move things by
 * fractions of a pixel. Text goes through the mod's own font ({@link UiFont}).
 */
public final class Draw {
    private static final int RAMP = 256;
    private static ResourceLocation radial;
    private static ResourceLocation fadeRight;
    private static ResourceLocation fadeLeft;
    private static ResourceLocation fadeDown;
    private static ResourceLocation fadeUp;

    private Draw() {
    }

    // ---- shapes ---------------------------------------------------------------------------------

    /** Rectangle with 1px rounded corners. */
    public static void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || ColorMath.alpha(color) == 0) return;
        if (w < 3 || h < 3) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (ColorMath.alpha(color) == 0) return;
        g.fill(x + 1, y, x + w - 1, y + 1, color);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Soft elliptical glow centred on (cx, cy); {@code color}'s alpha is the peak intensity. */
    public static void glow(GuiGraphics g, float cx, float cy, float radiusX, float radiusY, int color) {
        if (ColorMath.alpha(color) == 0 || radiusX <= 0 || radiusY <= 0) return;
        ensureTextures();
        g.pose().pushMatrix();
        g.pose().translate(cx - radiusX, cy - radiusY);
        g.pose().scale(radiusX * 2 / 64f, radiusY * 2 / 64f);
        g.blit(RenderPipelines.GUI_TEXTURED, radial, 0, 0, 0, 0, 64, 64, 64, 64, 64, 64, color);
        g.pose().popMatrix();
    }

    /** Horizontal fade: {@code color} at the left edge to transparent at the right (or mirrored). */
    public static void fadeX(GuiGraphics g, int x, int y, int w, int h, int color, boolean solidLeft) {
        if (w <= 0 || h <= 0 || ColorMath.alpha(color) == 0) return;
        ensureTextures();
        g.blit(RenderPipelines.GUI_TEXTURED, solidLeft ? fadeRight : fadeLeft, x, y, 0, 0, w, h, RAMP, 1, RAMP, 1, color);
    }

    /** Vertical fade: {@code color} at the top to transparent at the bottom (or mirrored). */
    public static void fadeY(GuiGraphics g, int x, int y, int w, int h, int color, boolean solidTop) {
        if (w <= 0 || h <= 0 || ColorMath.alpha(color) == 0) return;
        ensureTextures();
        g.blit(RenderPipelines.GUI_TEXTURED, solidTop ? fadeDown : fadeUp, x, y, 0, 0, w, h, 1, RAMP, 1, RAMP, color);
    }

    // ---- images ---------------------------------------------------------------------------------

    /**
     * Draws an image filling the box like CSS {@code object-fit: cover} (cropped, never stretched).
     *
     * @param zoom  1 = exact cover, >1 zooms in around the centre (hover effects)
     * @param color tint/alpha, e.g. {@code 0xFFFFFFFF} for unchanged
     */
    public static void cover(GuiGraphics g, ClientMedia.Image image, int x, int y, int w, int h, float zoom, int color) {
        if (image == null || w <= 0 || h <= 0) return;
        float boxAspect = (float) w / h;
        float imageAspect = (float) image.width() / image.height();
        float regionW = imageAspect > boxAspect ? image.height() * boxAspect : image.width();
        float regionH = imageAspect > boxAspect ? image.height() : image.width() / boxAspect;
        regionW /= Math.max(1f, zoom);
        regionH /= Math.max(1f, zoom);
        float u = (image.width() - regionW) / 2f;
        float v = (image.height() - regionH) / 2f;
        g.blit(RenderPipelines.GUI_TEXTURED, image.texture(), x, y, u, v, w, h, Math.round(regionW), Math.round(regionH),
                image.width(), image.height(), color);
    }

    // ---- text -----------------------------------------------------------------------------------

    /** Text at a float position with scale and colour (alpha honoured). */
    public static void text(GuiGraphics g, String text, float x, float y, float scale, int color, boolean shadow) {
        if (ColorMath.alpha(color) < 4 || text.isEmpty()) return;
        UiFont.draw(g, text, x, y, scale, color, shadow);
    }

    /** A line from {@link #wrap}. */
    public static void text(GuiGraphics g, FormattedCharSequence text, float x, float y, int color) {
        if (ColorMath.alpha(color) < 4) return;
        UiFont.draw(g, text, x, y, color, false);
    }

    /** Truncates with an ellipsis so the text fits {@code maxWidth}. */
    public static String fit(String text, float maxWidth) {
        return UiFont.fit(text, maxWidth);
    }

    /** Wraps text into lines no wider than {@code maxWidth} (CJK-aware). */
    public static List<FormattedCharSequence> wrap(String text, float maxWidth) {
        return UiFont.split(text, maxWidth);
    }

    /** @deprecated the font is the mod's own now; use {@link #text(GuiGraphics, String, float, float, float, int, boolean)} */
    @Deprecated
    public static void text(GuiGraphics g, Font font, String text, float x, float y, float scale, int color, boolean shadow) {
        text(g, text, x, y, scale, color, shadow);
    }

    /** @deprecated use {@link #text(GuiGraphics, FormattedCharSequence, float, float, int)} */
    @Deprecated
    public static void text(GuiGraphics g, Font font, FormattedCharSequence text, float x, float y, int color) {
        text(g, text, x, y, color);
    }

    /** @deprecated use {@link #fit(String, float)} */
    @Deprecated
    public static String fit(Font font, String text, int maxWidth) {
        return fit(text, maxWidth);
    }

    /** @deprecated use {@link #wrap(String, float)} */
    @Deprecated
    public static List<FormattedCharSequence> wrap(Font font, String text, int maxWidth) {
        return wrap(text, maxWidth);
    }

    // ---- generated textures -------------------------------------------------------------------

    private static void ensureTextures() {
        if (radial != null) return;
        NativeImage glow = new NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                double dx = (x + 0.5) / 32.0 - 1, dy = (y + 0.5) / 32.0 - 1;
                double d = Math.min(1, Math.sqrt(dx * dx + dy * dy));
                double a = 1 - d;
                a = a * a * (3 - 2 * a); // smoothstep falloff
                glow.setPixel(x, y, ((int) Math.round(a * 255) << 24) | 0xFFFFFF);
            }
        }
        NativeImage right = new NativeImage(RAMP, 1, true);
        NativeImage left = new NativeImage(RAMP, 1, true);
        NativeImage down = new NativeImage(1, RAMP, true);
        NativeImage up = new NativeImage(1, RAMP, true);
        for (int i = 0; i < RAMP; i++) {
            double t = i / (RAMP - 1.0);
            int alpha = (int) Math.round((1 - t * t * (3 - 2 * t)) * 255);
            int pixel = (alpha << 24) | 0xFFFFFF;
            right.setPixel(i, 0, pixel);
            left.setPixel(RAMP - 1 - i, 0, pixel);
            down.setPixel(0, i, pixel);
            up.setPixel(0, RAMP - 1 - i, pixel);
        }
        radial = register("ui/radial", glow);
        fadeRight = register("ui/fade_right", right);
        fadeLeft = register("ui/fade_left", left);
        fadeDown = register("ui/fade_down", down);
        fadeUp = register("ui/fade_up", up);
    }

    private static ResourceLocation register(String path, NativeImage image) {
        ResourceLocation location = BlockLand.id(path);
        DynamicTexture texture = new DynamicTexture(location::toString, image);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return location;
    }
}
