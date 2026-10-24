package com.blockland.client.parachute;

import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.parachute.ParachuteRules;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

import static com.blockland.client.ui.Tr.tr;

/**
 * Parachute hint above the hotbar: with a parachute on in the air, which key opens the canopy (only once it can);
 * under the canopy, which key closes it and how high the jumper still is.
 */
final class ParachuteHud {
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;
    private static final int KEY = 0xFFFFC24A;
    /** Blocks looked down for the height read-out. */
    private static final float MAX_HEIGHT = 256;

    private ParachuteHud() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui || mc.screen != null || !ParachuteRules.wears(player)) return;
        boolean open = ClientParachute.localOpen();
        if (!open && !ParachuteRules.mayOpen(player, 0)) return;
        int cx = g.guiWidth() / 2, y = g.guiHeight() - 92;
        String key = mc.options.keyJump.getTranslatedKeyMessage().getString();
        String hint = tr(open ? "blockland.parachute.hud.close" : "blockland.parachute.hud.open", key);
        Draw.glow(g, cx, y + 6, 110, 16, 0x44000000);
        Draw.text(g, hint, cx - UiFont.width(hint) / 2f, y, 1f, open ? TEXT : KEY, true);
        float height = ParachuteRules.heightAboveGround(player, MAX_HEIGHT);
        String altitude = height >= MAX_HEIGHT ? tr("blockland.parachute.hud.height_far")
                : tr("blockland.parachute.hud.height", Math.round(height));
        Draw.text(g, altitude, cx - UiFont.width(altitude) * 0.8f / 2f, y + 11, 0.8f, MUTED, true);
    }
}
