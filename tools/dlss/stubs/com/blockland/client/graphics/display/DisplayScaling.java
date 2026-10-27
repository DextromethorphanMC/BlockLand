package com.blockland.client.graphics.display;

/**
 * Check-only stand-in for the mod's DisplayScaling (which needs Minecraft): the two calls the DLSS bridge makes, as the
 * checks run them — the whole window, no black bars. Keep destination() in step with the real one.
 */
public final class DisplayScaling {
    private DisplayScaling() {
    }

    public static int[] destination(int targetW, int targetH, int srcW, int srcH, boolean bars) {
        if (!bars || srcW <= 0 || srcH <= 0) return new int[]{0, 0, targetW, targetH};
        double scale = Math.min(targetW / (double) srcW, targetH / (double) srcH);
        int w = Math.min(targetW, Math.max(1, (int) Math.round(srcW * scale)));
        int h = Math.min(targetH, Math.max(1, (int) Math.round(srcH * scale)));
        return new int[]{(targetW - w) / 2, (targetH - h) / 2, w, h};
    }

    public static boolean bars() {
        return false;
    }
}
