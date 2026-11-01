package com.blockland.client.hud.vanilla;

import com.blockland.anim.ColorMath;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.ui.Draw;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;

/**
 * Food (UI spec §3.2), the mirror of the health bar: the number, a bar filling from the drumstick on the right, the
 * saturation as a gold layer over it; green with the hunger effect, the drumstick shaking when food runs low. Above it,
 * only while the air runs short (under water), a thin air bar with a bubble — vanilla's bubbles.
 */
final class FoodBar implements HudElement {
    static final int W = 96, H = 18;
    private static final int BAR_W = 60, ROW = 7;

    @Override
    public String id() {
        return "food";
    }

    @Override
    public String nameKey() {
        return "blockland.hud.element.food";
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
        return Hotbar.SLOTS_W / 2 - W / 2;
    }

    @Override
    public int defaultY() {
        return Hotbar.STACK_TOP - 3;
    }

    @Override
    public boolean visible() {
        Player p = VanillaHud.cameraPlayer();
        // vanilla shows a ridden animal's health where the food is: it stays vanilla's, so the food steps aside
        return VanillaHud.replaces() && VanillaHud.hurtable() && p != null
                && !(p.getVehicle() instanceof LivingEntity ridden && ridden.showVehicleHealth());
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
        Player p = VanillaHud.cameraPlayer();
        if (p == null) return;
        FoodData food = p.getFoodData();
        int level = food.getFoodLevel();
        float saturation = food.getSaturationLevel();
        int color = p.hasEffect(MobEffects.HUNGER) ? Bars.HUNGER : Bars.FOOD;
        boolean low = level <= 6;
        int fy = y + ROW, bx = x + W - 10 - BAR_W;

        // the drumstick at the bar's start (the right), shaking when food is low
        int shake = low && VanillaHud.ticks % 6 < 3 ? -1 : 0;
        BarIcons.draw(g, BarIcons.FOOD, x + W - BarIcons.width(BarIcons.FOOD), fy + 2 + shake, color, 0xFFEDE3D2);
        Bars.track(g, bx, fy + 3, BAR_W, Bars.HEIGHT);
        Bars.fill(g, bx, fy + 3, BAR_W, Bars.HEIGHT, 0, level / 20f, color, true);
        if (saturation > 0) Bars.fill(g, bx, fy + 3, BAR_W, 2, 0, Math.min(level, saturation) / 20f, Bars.SATURATION, true);
        String number = String.valueOf(level);
        Draw.text(g, number, x, fy + 2, 0.75f, low ? ColorMath.mix(Bars.TEXT, Bars.FOOD, 0.6f) : Bars.TEXT, true);

        // the air, only while it runs short
        int air = p.getAirSupply(), maxAir = Math.max(1, p.getMaxAirSupply());
        boolean short_ = p.isEyeInFluid(FluidTags.WATER) || air < maxAir;
        if (!short_ && !editing) return;
        float share = Mth.clamp(air / (float) maxAir, 0, 1);
        boolean drowning = air <= 0;
        int airColor = drowning && VanillaHud.ticks % 10 < 5 ? Bars.HEALTH : Bars.AIR;
        float alpha = short_ ? 1 : 0.45f;
        BarIcons.draw(g, BarIcons.BUBBLE, x + W - 6, y, ColorMath.fade(airColor, alpha), ColorMath.fade(0xFFFFFFFF, alpha));
        Bars.track(g, bx, y + 1, BAR_W, 2);
        Bars.fill(g, bx, y + 1, BAR_W, 2, 0, share, ColorMath.fade(airColor, alpha), true);
        if (short_) {
            String bubbles = String.valueOf(Mth.ceil(share * 10));
            Draw.text(g, bubbles, x, y, 0.6f, Bars.MUTED, true);
        }
    }
}
