package com.blockland.client.graphics.display;

import com.blockland.client.graphics.GraphicsSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The picture's shape and size (rendering spec §21): the virtual framebuffer Minecraft renders into, how it is presented
 * in the window, and where the mouse lands ({@link DisplayScaling}, driven by the {@code mixin.display} hooks). Wired
 * once from {@code Graphics.init}; a settings change takes effect at the start of the next frame.
 */
public final class Display {
    /** Heights the resolution presets are offered at (each at the chosen ratio's width). */
    private static final int[] HEIGHTS = {600, 720, 768, 900, 960, 1024, 1050, 1080, 1200, 1440, 1600, 1800, 2160, 2400, 2880, 4320};

    private Display() {
    }

    public static void init() {
        DisplayScaling.ready();
    }

    /** The window's real framebuffer {width, height} (0 before the window reported one). */
    public static int[] windowSize() {
        return new int[]{DisplayScaling.realWidth(), DisplayScaling.realHeight()};
    }

    /** The virtual framebuffer these settings give in the current window. */
    public static int[] virtualSize(GraphicsSettings settings) {
        return DisplayScaling.virtualSize(settings, DisplayScaling.realWidth(), DisplayScaling.realHeight());
    }

    /** The shape resolutions are offered in: the chosen ratio, or the window's for native. */
    public static float ratio(GraphicsSettings settings) {
        float ratio = settings.aspectRatioMode().ratio();
        if (ratio > 0) return ratio;
        int w = DisplayScaling.realWidth(), h = DisplayScaling.realHeight();
        return w > 0 && h > 0 ? w / (float) h : 16 / 9f;
    }

    /**
     * The resolution presets ({@code WIDTHxHEIGHT}) of a ratio: the common heights at that ratio (widths even), from the
     * smallest allowed up to twice the window per axis (above the window is supersampling).
     */
    public static List<String> presets(float ratio) {
        int windowW = Math.max(DisplayScaling.realWidth(), 1280), windowH = Math.max(DisplayScaling.realHeight(), 720);
        int maxW = Math.min(GraphicsSettings.MAX_WIDTH, windowW * 2), maxH = Math.min(GraphicsSettings.MAX_HEIGHT, windowH * 2);
        List<String> out = new ArrayList<>();
        for (int h : HEIGHTS) {
            int w = Math.round(h * ratio / 2f) * 2;
            if (w < GraphicsSettings.MIN_WIDTH || h < GraphicsSettings.MIN_HEIGHT || w > maxW || h > maxH) continue;
            String id = w + "x" + h;
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    /** A custom height for a width at a ratio (even, within the allowed range). */
    public static int heightFor(int width, float ratio) {
        int h = Math.round(width / ratio / 2f) * 2;
        return Math.max(GraphicsSettings.MIN_HEIGHT, Math.min(GraphicsSettings.MAX_HEIGHT, h));
    }
}
