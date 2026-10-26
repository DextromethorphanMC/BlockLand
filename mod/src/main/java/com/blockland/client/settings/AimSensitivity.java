package com.blockland.client.settings;

import com.blockland.client.gun.ClientGuns;
import com.blockland.client.scope.Optics;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;

/**
 * Aim-down-sights sensitivity. Vanilla turns the view with one mouse sensitivity and offers no hook, so while the
 * player aims the vanilla option temporarily holds the scaled value and gets the player's own value back as soon as
 * they stop — and before any screen opens, so menus and saved options always see the real setting.
 */
public final class AimSensitivity {
    /** The player's real sensitivity while ours is in place; null when nothing is overridden. */
    @Nullable
    private static Double saved;

    private AimSensitivity() {
    }

    public static void init() {
        TickEvent.RenderTickEvent.Post.BUS.addListener(event -> update());
        ScreenEvent.Opening.BUS.addListener(event -> {
            restore();
            return false;
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> restore());
    }

    private static void update() {
        Minecraft mc = Minecraft.getInstance();
        // through a magnified scope the turning also slows down with the magnification
        float multiplier = ClientSettings.get().adsSensitivity * Optics.sensitivityScale();
        boolean aiming = mc.player != null && mc.screen == null && (ClientGuns.aim() > 0.5f || Optics.lookingThroughHandOptic());
        if (!aiming || Math.abs(multiplier - 1f) < 0.01f) {
            restore();
            return;
        }
        if (saved == null) saved = mc.options.sensitivity().get();
        double target = scaled(saved, multiplier);
        if (Math.abs(mc.options.sensitivity().get() - target) > 1e-6) mc.options.sensitivity().set(target);
    }

    public static void restore() {
        if (saved == null) return;
        Minecraft.getInstance().options.sensitivity().set(saved);
        saved = null;
    }

    /**
     * Vanilla turns by {@code (0.6 s + 0.2)³ · 8} per mouse unit; this finds the setting that turns {@code multiplier}
     * times as fast (as far as the slider's range allows).
     */
    static double scaled(double sensitivity, float multiplier) {
        double base = sensitivity * 0.6 + 0.2;
        double target = (Math.cbrt(multiplier) * base - 0.2) / 0.6;
        return Math.max(0, Math.min(1, target));
    }
}
