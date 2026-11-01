package com.blockland.client.hud.vanilla;

import com.blockland.client.font.UiFont;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.ui.Draw;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * Armour (UI spec §3.2): a breastplate, a bar of armour points out of 20 and the number; toughness, when the armour
 * has any, as a thin blue line under the bar. Only while some armour is worn.
 */
final class ArmorBar implements HudElement {
    static final int W = 96, H = 11;
    private static final int BAR_X = 10, BAR_W = 60;
    /** What the editor shows while no armour is worn. */
    private static final int SAMPLE = 12;

    @Override
    public String id() {
        return "armor";
    }

    @Override
    public String nameKey() {
        return "blockland.hud.element.armor";
    }

    @Override
    public int width() {
        return W;
    }

    @Override
    public int height() {
        return H;
    }

    @Override
    public HudAnchor defaultAnchor() {
        return HudAnchor.BOTTOM;
    }

    @Override
    public int defaultX() {
        return -(Hotbar.SLOTS_W / 2 - W / 2);
    }

    @Override
    public int defaultY() {
        return Hotbar.STACK_TOP - 3 - HealthBar.H - 3;
    }

    @Override
    public boolean visible() {
        Player p = VanillaHud.cameraPlayer();
        return VanillaHud.replaces() && VanillaHud.hurtable() && p != null && p.getArmorValue() > 0;
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
        Player p = VanillaHud.cameraPlayer();
        if (p == null) return;
        int armor = p.getArmorValue();
        double toughness = p.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        if (armor <= 0) {
            if (!editing) return;
            armor = SAMPLE;
        }
        BarIcons.draw(g, BarIcons.ARMOR, x, y + 3, Bars.ARMOR, 0);
        int bx = x + BAR_X, by = y + 3;
        Bars.track(g, bx, by, BAR_W, Bars.HEIGHT);
        Bars.fill(g, bx, by, BAR_W, Bars.HEIGHT, 0, Math.min(armor, 20) / 20f, Bars.ARMOR, false);
        if (toughness > 0) g.fill(bx, by + Bars.HEIGHT + 2, bx + Math.round(BAR_W * Mth.clamp((float) toughness / 20f, 0, 1)), by + Bars.HEIGHT + 3, Bars.TOUGHNESS);
        String number = String.valueOf(armor);
        Draw.text(g, number, x + W - UiFont.width(number) * 0.75f, y + 2, 0.75f, Bars.TEXT, true);
    }
}
