package com.blockland.client.hud.vanilla;

import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.ui.Draw;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * Health (UI spec §3.2): a heart, a bar of health over max health with absorption after it in gold, the number. The
 * bar turns green when poisoned, ash grey withering, ice blue frozen; a hit flashes it and leaves the lost part fading
 * behind; regeneration runs a sheen along it, healing glows round it, low health pulses red and shakes the heart; a
 * hardcore world's heart has eyes.
 */
final class HealthBar implements HudElement {
    static final int W = 96, H = 11;
    private static final int BAR_X = 10, BAR_W = 60;

    @Override
    public String id() {
        return "health";
    }

    @Override
    public String nameKey() {
        return "blockland.hud.element.health";
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
        return Hotbar.STACK_TOP - 3;
    }

    @Override
    public boolean visible() {
        return VanillaHud.replaces() && VanillaHud.hurtable() && VanillaHud.cameraPlayer() != null;
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
        Player p = VanillaHud.cameraPlayer();
        Minecraft mc = Minecraft.getInstance();
        if (p == null) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        float health = p.getHealth(), absorption = p.getAbsorptionAmount();
        float max = Math.max((float) p.getAttributeValue(Attributes.MAX_HEALTH), health);
        float scale = Math.max(1, Math.max(max, health + absorption));
        int color = p.hasEffect(MobEffects.WITHER) ? Bars.WITHER : p.hasEffect(MobEffects.POISON) ? Bars.POISON
                : p.isFullyFrozen() ? Bars.FROZEN : Bars.HEALTH;
        boolean low = health <= Math.max(4, max * 0.2f) && health > 0;
        float time = VanillaHud.ticks + pt;
        float pulse = low ? 0.5f + 0.5f * Mth.sin(time * 0.6f) : 0;

        // the heart: shaking while low (as vanilla's hearts do), eyes in hardcore
        int shake = low && VanillaHud.ticks % 6 < 3 ? -1 : 0;
        boolean hardcore = mc.level != null && mc.level.getLevelData().isHardcore();
        BarIcons.draw(g, BarIcons.HEART, x, y + 3 + shake, color, hardcore ? 0xFFF2F4F7 : color);

        int bx = x + BAR_X, by = y + 3;
        Bars.track(g, bx, by, BAR_W, Bars.HEIGHT);
        float trail = Math.max(health, VanillaHud.trail);
        if (trail > health) Bars.fill(g, bx, by, BAR_W, Bars.HEIGHT, health / scale, (trail - health) / scale, Bars.TRAIL, false);
        int fill = VanillaHud.hurtFlash > 0 ? ColorMath.mix(color, 0xFFFFFFFF, VanillaHud.hurtFlash / 8f * 0.7f) : color;
        Bars.fill(g, bx, by, BAR_W, Bars.HEIGHT, 0, health / scale, fill, false);
        if (absorption > 0) Bars.fill(g, bx, by, BAR_W, Bars.HEIGHT, health / scale, absorption / scale, Bars.ABSORPTION, false);
        if (p.hasEffect(MobEffects.REGENERATION)) {
            Bars.sheen(g, bx, by, Math.round(BAR_W * health / scale), Bars.HEIGHT, (time % 20) / 20f, 4, false, 1);
        }
        if (VanillaHud.healGlow > 0) Draw.outline(g, bx - 2, by - 2, BAR_W + 4, Bars.HEIGHT + 4, ColorMath.fade(Bars.HEAL, VanillaHud.healGlow / 10f));
        if (low) Draw.outline(g, bx - 2, by - 2, BAR_W + 4, Bars.HEIGHT + 4, ColorMath.fade(Bars.HEALTH, 0.35f + 0.5f * pulse));

        // the number, absorption after it in gold
        String number = String.valueOf(Mth.ceil(health));
        String extra = absorption > 0 ? "+" + Mth.ceil(absorption) : "";
        float s = 0.75f, right = x + W;
        float extraW = UiFont.width(extra) * s;
        if (!extra.isEmpty()) Draw.text(g, extra, right - extraW, y + 2, s, Bars.ABSORPTION, true);
        Draw.text(g, number, right - extraW - UiFont.width(number) * s, y + 2, s, low ? ColorMath.mix(Bars.TEXT, Bars.HEALTH, pulse) : Bars.TEXT, true);
    }
}
