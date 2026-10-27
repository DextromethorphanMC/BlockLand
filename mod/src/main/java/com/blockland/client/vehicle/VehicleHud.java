package com.blockland.client.vehicle;

import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.vehicle.VehicleData;
import com.blockland.vehicle.VehicleEntity;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import static com.blockland.client.ui.Tr.tr;

/**
 * The rider's HUD (vehicle spec §1): the vehicle, its health, the speed, the seat's role, the seat weapon's rounds or
 * its reload, the keys of this seat; through a gunner's sight, a reticle.
 */
final class VehicleHud {
    private static final int TEXT = 0xFFF2F4F7, MUTED = 0xFF9AA4B5, ACCENT = 0xFFE8B04A, PANEL = 0xB0101418;

    private VehicleHud() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui || !(player.getVehicle() instanceof VehicleEntity v)) return;
        int seatIndex = Math.max(0, v.seatOf(player));
        VehicleData.Seat seat = v.data.seats.get(Math.min(seatIndex, v.data.seats.size() - 1));
        int w = g.guiWidth(), h = g.guiHeight();
        if (ClientVehicles.sighting()) reticle(g, w / 2, h / 2);

        int panelW = 190, panelH = 46, x = (w - panelW) / 2, y = h - 64 - panelH;
        Draw.rect(g, x, y, panelW, panelH, PANEL);
        Draw.rect(g, x, y, panelW, 1, ACCENT);
        String name = Component.translatable("entity.blockland." + v.data.id).getString();
        Draw.text(g, name, x + 6, y + 5, 1f, TEXT, true);
        String role = tr("blockland.vehicle.role." + seat.role) + " · " + (seatIndex + 1) + "/" + v.data.seats.size();
        Draw.text(g, role, x + panelW - 6 - UiFont.width(role) * 0.75f, y + 6, 0.75f, MUTED, false);

        // health
        float health = Mth.clamp(v.health() / Math.max(1, v.data.health), 0, 1);
        int barW = panelW - 12;
        Draw.rect(g, x + 6, y + 18, barW, 3, 0x40FFFFFF);
        int color = health > 0.5f ? 0xFF7CDB5A : health > 0.25f ? 0xFFE8B04A : 0xFFE0463C;
        Draw.rect(g, x + 6, y + 18, Math.round(barW * health), 3, color);

        // speed, or the seat's weapon
        float speed = seatIndex == 0 ? (float) Math.abs(v.forwardSpeed()) * 20 : Math.abs(v.speed());
        String line = tr("blockland.vehicle.hud.speed", Math.round(speed * 3.6f));
        int weapon = v.data.weaponIndexOf(seatIndex);
        if (weapon >= 0) {
            VehicleData.Weapon wd = v.data.weapons.get(weapon);
            int reload = v.reloadLeft(weapon);
            line = reload > 0
                    ? tr("blockland.vehicle.hud.reloading", Math.round(100 * (1 - reload / (float) wd.reloadTicks())))
                    : tr("blockland.vehicle.hud.ammo", tr("blockland.vehicle.weapon." + wd.id), v.ammo(weapon), wd.magazine);
        }
        Draw.text(g, line, x + 6, y + 25, 1f, TEXT, true);
        String hint = tr(weapon >= 0 ? "blockland.vehicle.hint.gunner" : seatIndex == 0 ? "blockland.vehicle.hint.driver" : "blockland.vehicle.hint.passenger");
        Draw.text(g, Draw.fit(hint, (int) ((panelW - 12) / 0.75f)), x + 6, y + 37, 0.75f, MUTED, false);
    }

    /** A gun sight: a centre ring, a fine cross and range ticks under it. */
    private static void reticle(GuiGraphics g, int cx, int cy) {
        int c = 0xE0F2F4F7, dim = 0x90F2F4F7;
        Draw.rect(g, cx - 1, cy - 1, 2, 2, c);
        Draw.rect(g, cx - 40, cy, 30, 1, c);
        Draw.rect(g, cx + 10, cy, 30, 1, c);
        Draw.rect(g, cx, cy + 10, 1, 30, c);
        for (int i = 1; i <= 4; i++) Draw.rect(g, cx - 4 - i, cy + 10 + i * 6, 9 + 2 * i, 1, dim);
        Draw.outline(g, cx - 8, cy - 8, 16, 16, dim);
    }
}
