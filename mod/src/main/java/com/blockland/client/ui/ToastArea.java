package com.blockland.client.ui;

import net.minecraft.client.gui.screens.Screen;

/**
 * A screen with its own bar across the top (the lobby's) says where vanilla's toasts may start, so they never cover the
 * bar's buttons; {@code mixin/hud/ToastManagerMixin} moves every toast (ours and vanilla's) down by {@link #offset}.
 */
public interface ToastArea {
    /** GUI y the toasts' first slot starts at on this screen. */
    int toastTop();

    /** How far the toasts move down while {@code screen} is open (0 for any other screen). */
    static int offset(Screen screen) {
        return screen instanceof ToastArea area ? Math.max(0, area.toastTop()) : 0;
    }
}
