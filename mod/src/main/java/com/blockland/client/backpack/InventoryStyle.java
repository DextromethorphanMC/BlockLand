package com.blockland.client.backpack;

import com.blockland.client.font.UiFont;
import com.blockland.client.settings.ClientSettings;
import com.blockland.client.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The inventory screens' look (UI spec §1), drawn entirely by BlockLand — no vanilla GUI sprite: soft rounded panels and
 * cells, vector glyphs for empty equipment slots, counts and durability in BlockLand's font and greys. Every colour is a
 * grey of one brightness ({@code ClientSettings.inventoryBrightness}): dark themes use light text, light themes dark text.
 */
final class InventoryStyle {
    private InventoryStyle() {
    }

    // ---- palette: greys only -------------------------------------------------------------------------

    static float brightness() {
        return Mth.clamp(ClientSettings.get().inventoryBrightness, 0, 1);
    }

    static void setBrightness(float value) {
        ClientSettings.get().inventoryBrightness = Mth.clamp(value, 0, 1);
    }

    /** A grey of luminance {@code l} (0..1) with alpha {@code a}. */
    static int grey(float l, float a) {
        int v = Math.round(Mth.clamp(l, 0, 1) * 255);
        return Math.round(Mth.clamp(a, 0, 1) * 255) << 24 | v << 16 | v << 8 | v;
    }

    static float panelL() {
        return Mth.lerp(brightness(), 0.075f, 0.86f);
    }

    static boolean light() {
        return panelL() > 0.5f;
    }

    /** {@code panelL} moved towards contrast (+) or towards the background's own extreme (−). */
    private static float shift(float amount) {
        return panelL() + (light() ? -amount : amount);
    }

    static int panel() {
        return grey(panelL(), 0.97f);
    }

    static int raised() {
        return grey(shift(0.035f), 1);
    }

    static int cell() {
        return grey(light() ? panelL() - 0.12f : panelL() * 0.45f, 1);
    }

    static int cellHover() {
        return grey(light() ? panelL() - 0.2f : panelL() * 0.45f + 0.1f, 1);
    }

    static int line() {
        return grey(shift(0.1f), 1);
    }

    static int strong() {
        return grey(light() ? 0.05f : 0.97f, 1);
    }

    static int text() {
        return grey(light() ? 0.1f : 0.93f, 1);
    }

    static int dim() {
        return grey(Mth.lerp(0.5f, light() ? 0.1f : 0.93f, panelL()), 1);
    }

    static int glyph() {
        return grey(Mth.lerp(0.62f, light() ? 0.1f : 0.93f, panelL() * (light() ? 0.88f : 0.45f)), 1);
    }

    static int fade(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * Mth.clamp(alpha, 0, 1));
        return a << 24 | color & 0xFFFFFF;
    }

    // ---- shapes --------------------------------------------------------------------------------------

    /** A rectangle with corners rounded to radius {@code r} (whole pixels, scanline circle). */
    static void round(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        for (int i = 0; i < r; i++) {
            float dy = r - i - 0.5f;
            int inset = Math.round(r - (float) Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
        g.fill(x, y + r, x + w, y + h - r, color);
    }

    /** A rounded rectangle with a hairline border. */
    static void framed(GuiGraphics g, int x, int y, int w, int h, int r, int fill, int border) {
        round(g, x, y, w, h, r, border);
        round(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    /** A soft drop shadow under a rounded rectangle (subtle depth). */
    static void shadow(GuiGraphics g, int x, int y, int w, int h, int r) {
        for (int i = 4; i >= 1; i--) {
            round(g, x - i, y - i + 3, w + 2 * i, h + 2 * i, r + i, grey(0, 0.07f));
        }
    }

    /** A window: shadow, frame, fill. */
    static void window(GuiGraphics g, int x, int y, int w, int h) {
        shadow(g, x, y, w, h, 7);
        framed(g, x, y, w, h, 7, panel(), line());
    }

    /** A sub-panel inside a window. */
    static void card(GuiGraphics g, int x, int y, int w, int h) {
        round(g, x, y, w, h, 4, raised());
    }

    /** A slot's cell: {@code x, y} its corner (the item sits 2 in), {@code size} 20 (24 for a result). */
    static void cell(GuiGraphics g, int x, int y, int size, float hover) {
        round(g, x, y, size, size, 3, hover > 0.01f ? blend(cell(), cellHover(), hover) : cell());
        if (hover > 0.01f) {
            int ring = fade(strong(), 0.55f * hover);
            round(g, x - 1, y - 1, size + 2, 1, 0, ring);
            round(g, x - 1, y + size, size + 2, 1, 0, ring);
            g.fill(x - 1, y, x, y + size, ring);
            g.fill(x + size, y, x + size + 1, y + size, ring);
        }
    }

    static int blend(int a, int b, float t) {
        t = Mth.clamp(t, 0, 1);
        int r = Math.round(Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF));
        int gr = Math.round(Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF));
        int bl = Math.round(Mth.lerp(t, a & 0xFF, b & 0xFF));
        int al = Math.round(Mth.lerp(t, (a >>> 24) & 0xFF, (b >>> 24) & 0xFF));
        return al << 24 | r << 16 | gr << 8 | bl;
    }

    /** A button: rounded, filled stronger while hovered, its label centred. */
    static void button(GuiGraphics g, int x, int y, int w, int h, String label, float hover, boolean active) {
        int fill = blend(raised(), cellHover(), hover);
        framed(g, x, y, w, h, 4, fill, active ? fade(strong(), 0.35f + 0.4f * hover) : line());
        String fitted = Draw.fit(label, (w - 8) / 0.8f);
        Draw.text(g, fitted, x + (w - UiFont.width(fitted) * 0.8f) / 2f, y + (h - 7) / 2f, 0.8f, text(), false);
    }

    /** A vertical scrollbar: track and thumb. */
    static void scrollbar(GuiGraphics g, int x, int y, int w, int h, float offs, float thumbShare, boolean enabled, float hover) {
        round(g, x, y, w, h, w / 2, cell());
        if (!enabled) return;
        int thumb = Math.max(14, Math.round(h * Mth.clamp(thumbShare, 0.08f, 1)));
        int ty = y + Math.round((h - thumb) * Mth.clamp(offs, 0, 1));
        round(g, x, ty, w, thumb, w / 2, blend(fade(strong(), 0.55f), strong(), hover));
    }

    // ---- glyphs (16 × 16, for empty slots and widgets) ------------------------------------------------

    /** Glyphs as rectangles {x, y, w, h} on a 16 × 16 grid. */
    enum Glyph {
        HELMET(new int[][]{{4, 3, 8, 2}, {3, 5, 2, 6}, {11, 5, 2, 6}, {5, 5, 6, 1}, {3, 10, 3, 2}, {10, 10, 3, 2}}),
        CHEST(new int[][]{{2, 3, 4, 2}, {10, 3, 4, 2}, {2, 5, 3, 4}, {11, 5, 3, 4}, {4, 5, 8, 9}, {7, 3, 2, 2}}),
        LEGS(new int[][]{{4, 2, 8, 3}, {4, 5, 3, 9}, {9, 5, 3, 9}}),
        BOOTS(new int[][]{{3, 4, 3, 7}, {10, 4, 3, 7}, {2, 11, 5, 2}, {9, 11, 5, 2}}),
        SHIELD(new int[][]{{3, 2, 10, 2}, {3, 4, 2, 6}, {11, 4, 2, 6}, {4, 10, 2, 2}, {10, 10, 2, 2}, {6, 12, 4, 2}, {7, 4, 2, 8}}),
        BACKPACK(new int[][]{{6, 1, 4, 1}, {6, 2, 1, 1}, {9, 2, 1, 1}, {4, 3, 8, 1}, {3, 4, 1, 10}, {12, 4, 1, 10}, {4, 14, 8, 1},
                {4, 7, 2, 1}, {10, 7, 2, 1}, {6, 8, 4, 1}, {6, 10, 4, 1}, {5, 11, 1, 2}, {10, 11, 1, 2}}),
        TRASH(new int[][]{{6, 2, 4, 1}, {3, 3, 10, 2}, {4, 6, 8, 1}, {4, 6, 1, 8}, {11, 6, 1, 8}, {4, 13, 8, 1}, {7, 8, 1, 4}, {9, 8, 1, 4}}),
        SEARCH(new int[][]{{4, 2, 5, 1}, {3, 3, 1, 5}, {9, 3, 1, 5}, {4, 8, 5, 1}, {9, 9, 2, 2}, {11, 11, 2, 2}}),
        ARROW(new int[][]{{1, 7, 10, 2}, {10, 4, 1, 8}, {11, 5, 1, 6}, {12, 6, 1, 4}, {13, 7, 1, 2}});

        final int[][] rects;

        Glyph(int[][] rects) {
            this.rects = rects;
        }
    }

    static void glyph(GuiGraphics g, Glyph glyph, int x, int y, int color) {
        for (int[] r : glyph.rects) g.fill(x + r[0], y + r[1], x + r[0] + r[2], y + r[1] + r[3], color);
    }

    // ---- items -----------------------------------------------------------------------------------------

    /** The count and durability of a stack drawn at {@code x, y} (its 16 × 16 corner), in BlockLand's font and greys. */
    static void decorations(GuiGraphics g, ItemStack stack, int x, int y, @Nullable String countOverride) {
        if (stack.isEmpty()) return;
        g.pose().pushMatrix();
        if (stack.isBarVisible()) {
            int width = Mth.clamp(stack.getBarWidth(), 0, 13);
            g.fill(x + 2, y + 13, x + 15, y + 15, grey(0, 0.85f));
            g.fill(x + 2, y + 13, x + 2 + width, y + 14, grey(0.92f, 1));
        }
        Player player = Minecraft.getInstance().player;
        float cooldown = player == null ? 0 : player.getCooldowns().getCooldownPercent(stack, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
        if (cooldown > 0) {
            int top = y + Mth.floor(16 * (1 - cooldown));
            g.fill(x, top, x + 16, y + 16, grey(1, 0.3f));
        }
        String count = countOverride != null ? countOverride : stack.getCount() != 1 ? String.valueOf(stack.getCount()) : null;
        if (count != null) {
            float scale = 0.8f;
            float w = UiFont.width(count) * scale;
            Draw.text(g, count, x + 17 - w, y + 10, scale, grey(1, 1), true);
        }
        g.pose().popMatrix();
    }

    // ---- tooltips and effects --------------------------------------------------------------------------

    /** A tooltip: the lines in BlockLand's font, the first brighter, on a rounded dark card near the mouse. */
    static void tooltip(GuiGraphics g, List<String> lines, int mouseX, int mouseY, int screenW, int screenH) {
        if (lines.isEmpty()) return;
        float scale = 0.85f;
        int w = 0;
        for (String line : lines) w = Math.max(w, Math.round(UiFont.width(line) * scale));
        int lineH = 10;
        int boxW = w + 12, boxH = lines.size() * lineH + 8;
        int x = mouseX + 12, y = mouseY - 12;
        if (x + boxW > screenW - 4) x = mouseX - 12 - boxW;
        y = Mth.clamp(y, 4, Math.max(4, screenH - boxH - 4));
        g.nextStratum();
        shadow(g, x, y, boxW, boxH, 4);
        framed(g, x, y, boxW, boxH, 4, grey(light() ? 0.95f : 0.06f, 0.96f), grey(light() ? 0.7f : 0.3f, 1));
        int bright = grey(light() ? 0.05f : 0.97f, 1), soft = grey(light() ? 0.35f : 0.68f, 1);
        for (int i = 0; i < lines.size(); i++) {
            Draw.text(g, lines.get(i), x + 6, y + 5 + i * lineH, scale, i == 0 ? bright : soft, false);
        }
    }

    /**
     * An item's tooltip as vanilla builds it — every line in its own colours and styles (the rarity's name colour,
     * enchantments, lore, attribute bonuses), each colour made {@link #readable} on this panel — and the item's tooltip
     * image under the text (a bundle's contents, other mods' images).
     */
    static void tooltip(GuiGraphics g, List<Component> lines, @Nullable ClientTooltipComponent image, int mouseX, int mouseY,
                        int screenW, int screenH) {
        if (lines.isEmpty() && image == null) return;
        Font font = Minecraft.getInstance().font;
        float scale = 0.85f;
        int lineH = 10;
        List<FormattedCharSequence> text = new ArrayList<>(lines.size());
        int w = 0;
        for (Component line : lines) {
            FormattedCharSequence seq = readable(Language.getInstance().getVisualOrder(line));
            text.add(seq);
            w = Math.max(w, Math.round(UiFont.width(seq) * scale));
        }
        int imageW = image == null ? 0 : image.getWidth(font), imageH = image == null ? 0 : image.getHeight(font);
        w = Math.max(w, imageW);
        int boxW = w + 12, boxH = text.size() * lineH + (image == null ? 0 : imageH + 4) + 8;
        int x = mouseX + 12, y = mouseY - 12;
        if (x + boxW > screenW - 4) x = mouseX - 12 - boxW;
        y = Mth.clamp(y, 4, Math.max(4, screenH - boxH - 4));
        g.nextStratum();
        shadow(g, x, y, boxW, boxH, 4);
        framed(g, x, y, boxW, boxH, 4, grey(light() ? 0.95f : 0.06f, 0.96f), grey(light() ? 0.7f : 0.3f, 1));
        int bright = grey(light() ? 0.05f : 0.97f, 1), soft = grey(light() ? 0.35f : 0.68f, 1);
        for (int i = 0; i < text.size(); i++) {
            // a line without a colour of its own: the name bright, the rest soft
            UiFont.draw(g, text.get(i), x + 6, y + 5 + i * lineH, scale, i == 0 ? bright : soft, false);
        }
        if (image != null) image.renderImage(font, x + 6, y + 5 + text.size() * lineH + 2, imageW, imageH, g);
    }

    /**
     * A colour a text brings with it (an item's rarity, an enchantment, lore), kept in hue but lightened or darkened until
     * it reads on this brightness's panel: white and yellow names would vanish on a light panel, dark blue ones on a dark.
     */
    static int readable(int rgb) {
        float r = (rgb >> 16 & 255) / 255f, gr = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        float l = 0.2126f * r + 0.7152f * gr + 0.0722f * b;
        if (light() && l > 0.42f) {
            float k = 0.42f / l;
            r *= k;
            gr *= k;
            b *= k;
        } else if (!light() && l < 0.6f) {
            float t = (0.6f - l) / Math.max(1e-3f, 1 - l);
            r += (1 - r) * t;
            gr += (1 - gr) * t;
            b += (1 - b) * t;
        }
        return Math.round(r * 255) << 16 | Math.round(gr * 255) << 8 | Math.round(b * 255);
    }

    /** {@code text} with every colour of its own made {@link #readable} (styles otherwise untouched). */
    static FormattedCharSequence readable(FormattedCharSequence text) {
        return sink -> text.accept((index, style, codepoint) -> sink.accept(index, readable(style), codepoint));
    }

    private static Style readable(Style style) {
        TextColor color = style.getColor();
        return color == null ? style : style.withColor(readable(color.getValue()));
    }

    static List<String> plain(List<Component> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (Component line : lines) {
            String s = line.getString();
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    /** The player's effects as a column of cards from {@code x, y}: icon, name and level, time left. */
    static void effects(GuiGraphics g, Collection<MobEffectInstance> effects, int x, int y, int maxW) {
        Minecraft mc = Minecraft.getInstance();
        float tickRate = mc.level == null ? 20 : mc.level.tickRateManager().tickrate();
        int row = 0;
        for (MobEffectInstance effect : effects) {
            if (!effect.showIcon()) continue;
            int ry = y + row * 26;
            card(g, x, ry, maxW, 24);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, Gui.getMobEffectSprite(effect.getEffect()), x + 4, ry + 3, 18, 18);
            String name = effect.getEffect().value().getDisplayName().getString();
            if (effect.getAmplifier() > 0) name += " " + (effect.getAmplifier() + 1);
            Draw.text(g, Draw.fit(name, (maxW - 30) / 0.8f), x + 26, ry + 4, 0.8f, text(), false);
            Draw.text(g, MobEffectUtil.formatDuration(effect, 1.0f, tickRate).getString(), x + 26, ry + 13, 0.75f, dim(), false);
            row++;
        }
    }

    /** The brightness bar (the one setting): a grey ramp, its knob at the current value. */
    static void brightnessBar(GuiGraphics g, int x, int y, int w, int h, float hover) {
        for (int i = 0; i < w; i++) {
            g.fill(x + i, y, x + i + 1, y + h, grey(Mth.lerp(i / (float) (w - 1), 0.075f, 0.86f), 1));
        }
        int kx = x + Math.round(brightness() * (w - 1));
        round(g, kx - 2, y - 3, 5, h + 6, 2, blend(fade(strong(), 0.75f), strong(), hover));
    }
}
