package com.blockland.client.craft;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * The options of one slot, opened next to its callout box: a card per option (name, what it does, equipped / legendary
 * tags, the fitted one marked), scrolling when they do not fit. The option under the mouse is previewed on the model and
 * in the numbers; a click fits it. Opens with a short slide from the box.
 */
final class OptionPicker {
    static final int WIDTH = 176;
    private static final int MAX_HEIGHT = 220;

    @Nullable
    private String slot;
    private boolean fromLeft;
    private final AnimValue open = new AnimValue(0);
    private final Map<String, Spring> hover = new HashMap<>();
    private float scroll, scrollTarget;
    private int x, y, w, h, contentHeight;
    @Nullable
    private String hovered;

    @Nullable
    String slot() {
        return slot;
    }

    /** The option under the mouse (to preview), or null. */
    @Nullable
    String hovered() {
        return slot == null ? null : hovered;
    }

    /** Opens for {@code slotId} beside a box ({@code fromLeft}: the box is in the left column); toggles when already open. */
    void toggle(String slotId, boolean fromLeft) {
        if (slotId.equals(slot)) {
            close();
            return;
        }
        slot = slotId;
        this.fromLeft = fromLeft;
        scroll = scrollTarget = 0;
        open.snap(0);
        open.to(1, 0.22, Ease.QUART_OUT);
    }

    void close() {
        slot = null;
        hovered = null;
    }

    /**
     * @param boxX…boxH the callout box it opens from
     * @param screenH   to keep the list on screen
     */
    void render(GuiGraphics g, int mouseX, int mouseY, CraftSession session, int boxX, int boxY, int boxW, int boxH, int screenH, float a) {
        hovered = null;
        if (slot == null) return;
        List<CraftSession.Option> options = session.options(slot);
        float t = open.get();
        a *= t;
        w = WIDTH;
        x = fromLeft ? boxX + boxW + 8 : boxX - w - 8;
        x += Math.round((1 - t) * (fromLeft ? -10 : 10));
        contentHeight = 0;
        for (CraftSession.Option option : options) contentHeight += height(option) + 3;
        h = Math.min(MAX_HEIGHT, contentHeight + 8);
        y = Math.max(8, Math.min(screenH - h - 8, boxY + boxH / 2 - h / 2));
        Draw.rect(g, x, y, w, h, ColorMath.fade(0xE60C121C, a));
        Draw.outline(g, x, y, w, h, ColorMath.fade(LINE, a));
        scroll += (scrollTarget - scroll) * 0.35f;
        String current = session.selected(slot, session.edited);
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        int cy = y + 4 - Math.round(scroll);
        for (CraftSession.Option option : options) {
            int ch = height(option);
            boolean over = mouseY >= y && mouseY < y + h && inside(mouseX, mouseY, x + 4, cy, w - 8, ch);
            if (over) hovered = option.id();
            boolean chosen = option.id().equals(current);
            float hv = spring(option.id(), over ? 1 : 0).get();
            Draw.rect(g, x + 4, cy, w - 8, ch, ColorMath.fade(ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hv), CARD_SELECTED, chosen ? 1 : 0), a));
            Draw.rect(g, x + 4, cy, 2 + Math.round(hv * 2), ch, ColorMath.fade(CraftSession.rarityColor(option.rarity(), chosen), a * (0.5f + 0.5f * hv)));
            if (chosen) Draw.outline(g, x + 4, cy, w - 8, ch, ColorMath.fade(ACCENT, a * 0.7f));
            Draw.text(g, Draw.fit(option.name().getString(), w - 64), x + 12 + Math.round(hv * 2), cy + 5, 1f, ColorMath.fade(TEXT, a), false);
            String description = option.description().getString();
            if (!description.isEmpty()) {
                Draw.text(g, Draw.fit(description, w - 22), x + 12, cy + 18, 0.75f, ColorMath.fade(MUTED, a), false);
            }
            String tag = session.tag(slot, option);
            if (tag != null) {
                Draw.text(g, tag, x + w - 10 - UiFont.width(tag) * 0.75f, cy + 6, 0.75f, ColorMath.fade(session.tagColor(slot, option), a), false);
            }
            cy += ch + 3;
        }
        g.disableScissor();
        float max = Math.max(0, contentHeight + 8 - h);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        if (max > 0) {
            int barH = Math.max(12, Math.round(h * h / (float) (contentHeight + 8)));
            int barY = y + Math.round((h - barH) * (scroll / max));
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, ColorMath.fade(0x55FFFFFF, a));
        }
    }

    /** Bottom of the open list (for what docks under it), or -1. */
    int bottom() {
        return slot == null ? -1 : y + h;
    }

    int x() {
        return x;
    }

    boolean contains(double mouseX, double mouseY) {
        return slot != null && inside(mouseX, mouseY, x, y, w, h);
    }

    /** A click on the list: fits the option under the mouse. */
    boolean mouseClicked(double mouseX, double mouseY, CraftSession session) {
        if (!contains(mouseX, mouseY)) return false;
        if (slot != null && hovered != null) session.choose(slot, hovered);
        return true;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!contains(mouseX, mouseY)) return false;
        scrollTarget -= (float) scrollY * 24f;
        return true;
    }

    private static int height(CraftSession.Option option) {
        return option.description().getString().isEmpty() ? 20 : 30;
    }

    private Spring spring(String key, float target) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(0).config(320, 24));
        spring.setTarget(target);
        return spring;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
