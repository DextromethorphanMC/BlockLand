package com.blockland.client.hud.vanilla;

import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.ui.Draw;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The name of a newly selected item (UI spec §3.2, vanilla's selected item name): in its rarity's colour (italic when
 * renamed) on a dark plate, for vanilla's notification time, then fading. Its own element above the bars, so it can go
 * wherever the hotbar goes.
 */
final class ItemName implements HudElement {
    static final int W = 220, H = 12;

    @Override
    public String id() {
        return "item_name";
    }

    @Override
    public String nameKey() {
        return "blockland.hud.element.item_name";
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
        return 0;
    }

    @Override
    public int defaultY() {
        // over the armour row
        return new ArmorBar().defaultY() - ArmorBar.H - 4;
    }

    @Override
    public boolean visible() {
        return VanillaHud.replaces() && !VanillaHud.spectating() && VanillaHud.highlightTimer > 0 && !VanillaHud.highlighted.isEmpty();
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
        ItemStack stack = VanillaHud.highlighted;
        float alpha = Math.min(1, VanillaHud.highlightTimer * 256f / 10f / 255f);
        if (editing && (stack.isEmpty() || VanillaHud.highlightTimer <= 0)) {
            if (stack.isEmpty()) stack = new ItemStack(Items.DIAMOND_SWORD);
            alpha = 1;
        }
        if (stack.isEmpty() || alpha <= 0.02f) return;
        MutableComponent name = Component.empty().append(stack.getHoverName()).withStyle(stack.getRarity().color());
        if (stack.has(DataComponents.CUSTOM_NAME)) name.withStyle(ChatFormatting.ITALIC);
        Component text = stack.getHighlightTip(name);
        float w = UiFont.width(text);
        float tx = x + (W - w) / 2f;
        Draw.rect(g, Math.round(tx) - 4, y, Math.round(w) + 8, H, ColorMath.fade(0xA0101418, alpha));
        UiFont.draw(g, text, tx, y + 2, ColorMath.fade(0xFFFFFFFF, alpha), true);
    }
}
