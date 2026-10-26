package com.blockland.client.graphics.display;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.GraphicsStatus;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The virtual framebuffer (rendering spec §21): what Minecraft takes for the window's framebuffer is the size the player
 * chose (a resolution, or the largest box of a chosen aspect ratio inside the window), while the window's real
 * framebuffer is kept here from GLFW's callbacks. The finished picture is blitted into the window — stretched over it, or
 * whole and centred with black bars — and GUI mouse positions go through the same rectangle. With the native ratio and
 * resolution the virtual size is the real one and nothing differs from vanilla: no extra blit, the same numbers.
 * Render thread only (the window's callbacks and the mouse's tasks run there).
 */
public final class DisplayScaling {
    /** The window's real framebuffer, as GLFW reported it last. */
    private static int realWidth, realHeight;
    /** {@link Display#init} ran: the settings may be read (before it the virtual size is the real one). */
    private static boolean ready;
    /** Destination of the present being drawn (framebuffer pixels from the bottom left), set right before its blit. */
    private static int rectX, rectY, rectW, rectH;
    private static boolean presentScaled, presentSameSize;

    private DisplayScaling() {
    }

    static void ready() {
        ready = true;
    }

    // ---- the window's framebuffer (WindowMixin) ---------------------------------------------------------------------

    /** GLFW reported a framebuffer size (0 × 0 = minimised: kept as it was). */
    public static void framebufferResized(int width, int height) {
        if (width > 0 && height > 0) {
            realWidth = width;
            realHeight = height;
        }
    }

    /** The window read its framebuffer size itself (at creation): that is the real size. */
    public static void framebufferRefreshed(Window window) {
        realWidth = window.getWidth();
        realHeight = window.getHeight();
    }

    /** What the window stores as its framebuffer width when GLFW reports {@code real}: the virtual width. */
    public static int storedWidth(int real) {
        if (!ready || realWidth <= 0) return real;
        return virtualSize(Graphics.settings(), realWidth, realHeight)[0];
    }

    public static int storedHeight(int real) {
        if (!ready || realHeight <= 0) return real;
        return virtualSize(Graphics.settings(), realWidth, realHeight)[1];
    }

    /**
     * The frame starts (before anything of it is drawn, the main target is Minecraft's own): a settings change takes
     * effect — the window's framebuffer becomes the new virtual size and Minecraft resizes its targets and screens.
     */
    public static void beginFrame() {
        if (!ready) return;
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        if (realWidth <= 0 || realHeight <= 0) framebufferRefreshed(window);
        int[] size = virtualSize(Graphics.settings(), realWidth, realHeight);
        GraphicsStatus status = Graphics.status();
        status.displayWidth = size[0];
        status.displayHeight = size[1];
        status.windowWidth = realWidth;
        status.windowHeight = realHeight;
        if (window.isMinimized() || window.getWidth() == size[0] && window.getHeight() == size[1]) return;
        window.setWidth(size[0]);
        window.setHeight(size[1]);
        mc.resizeDisplay();
    }

    /**
     * The virtual framebuffer for the settings and a real framebuffer: the chosen resolution; for the native resolution
     * the largest box of the chosen ratio inside the window (the window itself for the native ratio).
     */
    public static int[] virtualSize(GraphicsSettings settings, int realW, int realH) {
        int[] chosen = settings.resolutionSize();
        if (chosen != null) return chosen;
        float ratio = settings.aspectRatioMode().ratio();
        if (ratio <= 0 || realW <= 0 || realH <= 0) return new int[]{realW, realH};
        if (realW / (float) realH > ratio) {
            return new int[]{Math.max(1, Math.round(realH * ratio)), realH};
        }
        return new int[]{realW, Math.max(1, Math.round(realW / ratio))};
    }

    /**
     * Where a picture of {@code srcW × srcH} goes in a {@code targetW × targetH} framebuffer: {x, y, width, height}, the
     * whole target when stretched, else the largest box of the picture's shape, centred.
     */
    public static int[] destination(int targetW, int targetH, int srcW, int srcH, boolean bars) {
        if (!bars || srcW <= 0 || srcH <= 0) return new int[]{0, 0, targetW, targetH};
        double scale = Math.min(targetW / (double) srcW, targetH / (double) srcH);
        int w = Math.min(targetW, Math.max(1, (int) Math.round(srcW * scale)));
        int h = Math.min(targetH, Math.max(1, (int) Math.round(srcH * scale)));
        return new int[]{(targetW - w) / 2, (targetH - h) / 2, w, h};
    }

    /** The picture keeps its shape with black bars around it (else it is stretched over the window). */
    public static boolean bars() {
        return ready && Graphics.settings().aspectFitMode() == GraphicsSettings.AspectFit.BARS;
    }

    public static int realWidth() {
        return realWidth;
    }

    public static int realHeight() {
        return realHeight;
    }

    // ---- presentation (GlCommandEncoderPresentMixin) ----------------------------------------------------------------

    /**
     * A texture of {@code width × height} is about to be presented: unless it is the window's size (vanilla's blit, as it
     * is) the destination becomes the stretched or barred rectangle; with bars the window is cleared black first.
     * Called right before the blit, after vanilla disabled the scissor test and opened the colour mask.
     */
    public static void beforePresentBlit(int width, int height) {
        presentScaled = ready && realWidth > 0 && realHeight > 0 && (width != realWidth || height != realHeight);
        if (!presentScaled) return;
        int[] rect = destination(realWidth, realHeight, width, height, bars());
        rectX = rect[0];
        rectY = rect[1];
        rectW = rect[2];
        rectH = rect[3];
        presentSameSize = rectW == width && rectH == height;
        if (rectW < realWidth || rectH < realHeight) {
            // the bars: the default framebuffer, through Blaze3D's own binding cache (the binding is put back)
            int previous = GlStateManager.getFrameBuffer(GL30.GL_DRAW_FRAMEBUFFER);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL11.glClearColor(0, 0, 0, 1);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
        }
    }

    public static int presentX0(int vanilla) {
        return presentScaled ? rectX : vanilla;
    }

    public static int presentY0(int vanilla) {
        return presentScaled ? rectY : vanilla;
    }

    public static int presentX1(int vanilla) {
        return presentScaled ? rectX + rectW : vanilla;
    }

    public static int presentY1(int vanilla) {
        return presentScaled ? rectY + rectH : vanilla;
    }

    /** Linear filtering when the picture is scaled (a 1:1 picture in bars keeps vanilla's nearest). */
    public static int presentFilter(int vanilla) {
        return presentScaled && !presentSameSize ? GL11.GL_LINEAR : vanilla;
    }

    // ---- the mouse (MouseHandlerMixin) ------------------------------------------------------------------------------

    /**
     * The width in screen coordinates that GUI x positions and drags are scaled by: the picture's on the window (the
     * window's own while it fills it).
     */
    public static int mouseWidth(Window window) {
        int screen = window.getScreenWidth();
        if (!scalingMouse(window)) return screen;
        int[] rect = destination(realWidth, realHeight, window.getWidth(), window.getHeight(), true);
        return Math.max(1, (int) Math.round(rect[2] * (double) screen / realWidth));
    }

    public static int mouseHeight(Window window) {
        int screen = window.getScreenHeight();
        if (!scalingMouse(window)) return screen;
        int[] rect = destination(realWidth, realHeight, window.getWidth(), window.getHeight(), true);
        return Math.max(1, (int) Math.round(rect[3] * (double) screen / realHeight));
    }

    /** A cursor x in screen coordinates, relative to the picture's left edge. */
    public static double mouseX(Window window, double x) {
        if (!scalingMouse(window)) return x;
        int[] rect = destination(realWidth, realHeight, window.getWidth(), window.getHeight(), true);
        return x - rect[0] * (double) window.getScreenWidth() / realWidth;
    }

    /** A cursor y in screen coordinates (from the top), relative to the picture's top edge. */
    public static double mouseY(Window window, double y) {
        if (!scalingMouse(window)) return y;
        int[] rect = destination(realWidth, realHeight, window.getWidth(), window.getHeight(), true);
        // the rectangle is centred, so its top margin is the bottom one (to the pixel the blit used)
        int top = realHeight - rect[1] - rect[3];
        return y - top * (double) window.getScreenHeight() / realHeight;
    }

    /** Only bars move the GUI off the window's edges; a stretched picture maps the whole window like vanilla. */
    private static boolean scalingMouse(Window window) {
        return bars() && realWidth > 0 && realHeight > 0 && (window.getWidth() != realWidth || window.getHeight() != realHeight);
    }
}
