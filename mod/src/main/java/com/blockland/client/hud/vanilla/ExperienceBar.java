package com.blockland.client.hud.vanilla;

import com.blockland.client.font.UiFont;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.hud.layout.HudLayout;
import com.blockland.client.ui.Draw;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * The experience bar (UI spec §3.2): a thin bar of the progress to the next level with the level in green over it — its
 * own element, placed apart from the hotbar (by default right over its cells, where vanilla has it). Across (the
 * default) or down: down, the bar fills upwards under the level.
 */
final class ExperienceBar implements HudElement {
    static final int H = 10;
    private static final int BAR = 3, LENGTH = Hotbar.SLOTS_W;
    /** 横 / 竖: across or down (its own choice, so it can follow a hotbar stood on end). */
    static final Option ORIENTATION = new Option("orientation", "blockland.hud.option.orientation", List.of("horizontal", "vertical"));

    private boolean vertical() {
        return "vertical".equals(HudLayout.option(this, ORIENTATION));
    }

    @Override
    public String id() {
        return "experience";
    }

    @Override
    public String nameKey() {
        return "blockland.hud.element.experience";
    }

    @Override
    public List<Option> options() {
        return List.of(ORIENTATION);
    }

    @Override
    public int width() {
        return vertical() ? H : LENGTH;
    }

    @Override
    public int height() {
        return vertical() ? LENGTH + H : H;
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
        return Hotbar.DEFAULT_Y - Hotbar.H - 2;
    }

    @Override
    public boolean visible() {
        Minecraft mc = Minecraft.getInstance();
        return VanillaHud.replaces() && !VanillaHud.spectating() && mc.gameMode != null && mc.gameMode.hasExperience();
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
        LocalPlayer p = Minecraft.getInstance().player;
        boolean has = p != null && visible();
        if (!has && !editing) return;
        float progress = has ? (p.getXpNeededForNextLevel() > 0 ? p.experienceProgress : 0) : 0.6f;
        int level = has ? p.experienceLevel : 12;
        String text = String.valueOf(level);
        if (vertical()) {
            int bx = x + (H - BAR) / 2, by = y + H;
            Bars.track(g, bx, by, BAR, LENGTH);
            int filled = Math.round(LENGTH * progress);
            if (filled > 0) g.fill(bx, by + LENGTH - filled, bx + BAR, by + LENGTH, Bars.EXPERIENCE);
            if (level > 0) Draw.text(g, text, x + H / 2f - UiFont.width(text) * 0.75f / 2f, y, 0.75f, Bars.EXPERIENCE, true);
            return;
        }
        int by = y + H - BAR;
        Bars.track(g, x, by, LENGTH, BAR);
        if (progress > 0) Bars.fill(g, x, by, LENGTH, BAR, 0, progress, Bars.EXPERIENCE, false);
        if (level > 0) Draw.text(g, text, x + LENGTH / 2f - UiFont.width(text) / 2f, y, 1f, Bars.EXPERIENCE, true);
    }
}
