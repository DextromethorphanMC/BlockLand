package com.blockland.client.screen.pause;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraftforge.client.event.ScreenEvent;

import java.lang.ref.WeakReference;

/**
 * Puts {@link GameMenuScreen} where vanilla would open its pause menu in a world (Esc, losing focus). Vanilla's menu
 * stays reachable from ours: that one instance is let through, so its own sub-screens (options, advancements…) come
 * back to it. The menu-less pause (F3 + Esc) stays vanilla.
 */
public final class PauseMenus {
    /** The vanilla pause screen the player asked for from our menu; opening it (again) is not intercepted. */
    private static WeakReference<PauseScreen> allowed = new WeakReference<>(null);

    private PauseMenus() {
    }

    public static void init() {
        ScreenEvent.Opening.BUS.addListener(event -> {
            if (event.getNewScreen() instanceof PauseScreen pause && pause.showsPauseMenu() && pause != allowed.get()
                    && Minecraft.getInstance().level != null) {
                event.setNewScreen(new GameMenuScreen());
            }
            return false;
        });
    }

    /** Vanilla's pause menu, marked so it is shown as it is. */
    static PauseScreen vanilla() {
        PauseScreen screen = new PauseScreen(true);
        allowed = new WeakReference<>(screen);
        return screen;
    }
}
