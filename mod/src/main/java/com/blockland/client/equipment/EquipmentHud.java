package com.blockland.client.equipment;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.client.font.UiFont;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.ui.Draw;
import com.blockland.equipment.EquipmentData;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import static com.blockland.client.ui.Tr.tr;

/**
 * Equipment HUD, matching the gun and knife HUDs: name, how many are carried and the controls (bottom right) with a
 * thin readiness bar, the shared {@link HitMarker} (thrown knives), and while a grenade cooks a fuse bar under the
 * crosshair that turns red in its last second.
 */
final class EquipmentHud {
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;
    private static final int CALLOUT = 0xFFFFA235;
    private static final int FUSE = 0xFFFFD27A;
    private static final int FUSE_LATE = 0xFFFF4D3D;

    private static String callout = "";
    private static final AnimValue calloutAlpha = new AnimValue(0);
    private static final AnimValue barAlpha = new AnimValue(0);
    private static final AnimValue fuseAlpha = new AnimValue(0);

    private EquipmentHud() {
    }

    static void flash(String text) {
        callout = text;
        calloutAlpha.snap(1);
        calloutAlpha.to(0, 0.5, Ease.QUAD_IN).delay(0.7);
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        EquipmentData.Equipment item = ClientEquipment.item();
        if (mc.player == null || item == null || !ClientEquipment.holdingEquipment() || mc.options.hideGui) return;
        int w = g.guiWidth(), h = g.guiHeight();
        HitMarker.render(g, w, h);

        int right = w - 14, bottom = h - 14;
        Draw.glow(g, right - 60, bottom - 22, 100, 36, 0x66000000);
        String name = item.displayName().getString();
        String count = " ×" + ClientEquipment.carried();
        float countW = UiFont.width(count);
        Draw.text(g, name, right - countW - UiFont.width(name), bottom - 26, 1f, TEXT, true);
        Draw.text(g, count, right - countW, bottom - 26, 1f, MUTED, true);
        String controls = tr(item.held() ? (item.throwing.lob != null ? "blockland.equipment.hud.grenade" : "blockland.equipment.hud.grenade_no_lob")
                : item.thrown() ? "blockland.equipment.hud.knife" : "blockland.equipment.hud.use");
        Draw.text(g, controls, right - UiFont.width(controls), bottom - 14, 1f, MUTED, true);
        float ready = ClientEquipment.readiness();
        barAlpha.toIfChanged(ready < 1 ? 1 : 0, 0.15, Ease.QUAD_OUT);
        float a = barAlpha.get();
        if (a > 0.02f) {
            int barW = Math.round(UiFont.width(controls)), x0 = right - barW, y = bottom - 3;
            g.fill(x0, y, right, y + 1, ColorMath.fade(0x30FFFFFF, a));
            g.fill(x0, y, x0 + Math.round(barW * ready), y + 1, ColorMath.fade(0xFFFFFFFF, a));
        }

        // the cooking fuse: a short bar under the crosshair emptying towards the bang
        float left = ClientEquipment.cookLeft();
        fuseAlpha.toIfChanged(left >= 0 ? 1 : 0, 0.12, Ease.QUAD_OUT);
        float f = fuseAlpha.get();
        if (f > 0.02f && item.fuse > 0) {
            float share = Math.max(0, Math.min(1, Math.max(0, left) / item.fuse));
            int barW = 40, x0 = Math.round(w / 2f - barW / 2f), y = Math.round(h / 2f + 14);
            int color = left >= 0 && left < 1 ? FUSE_LATE : FUSE;
            g.fill(x0, y, x0 + barW, y + 2, ColorMath.fade(0x50000000, f));
            g.fill(x0, y, x0 + Math.round(barW * share), y + 2, ColorMath.fade(color, f));
            String seconds = String.format("%.1f", Math.max(0, left));
            Draw.text(g, seconds, w / 2f - UiFont.width(seconds) * 0.8f / 2f, y + 5, 0.8f, ColorMath.fade(color, f), true);
        }

        float c = calloutAlpha.get();
        if (c > 0.02f && !callout.isEmpty()) {
            float scale = 1.4f;
            Draw.text(g, callout, w / 2f - UiFont.width(callout) * scale / 2f, h / 2f + 28, scale, ColorMath.fade(CALLOUT, c), true);
        }
    }
}
