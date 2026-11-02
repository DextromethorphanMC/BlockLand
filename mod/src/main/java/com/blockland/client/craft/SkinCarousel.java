package com.blockland.client.craft;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Spring;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * The in-game 工匠's skins: a carousel of cards along the bottom — each a mosaic of the skin's own colours
 * ({@link SkinSwatches}), its name and a rarity stripe — filtered by kind (全部 / 迷彩 / 特殊皮肤), with the
 * hovered (or chosen) skin's name large above it. The card under the mouse is previewed on the model; a click puts the
 * skin on.
 */
final class SkinCarousel {
    static final int HEIGHT = 74;
    private static final int CARD_W = 72, CARD_H = 48, GAP = 6;
    private static final String ALL = "all";

    private String filter = ALL;
    private final Map<String, Spring> hover = new HashMap<>();
    private final Spring scroll = new Spring(0).config(220, 26);
    private float scrollTarget;
    @Nullable
    private String hovered;
    private final List<Hit> hits = new ArrayList<>();
    private int x, y, w;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    /** The skin under the mouse (to preview), or null. */
    @Nullable
    String hovered() {
        return hovered;
    }

    void render(GuiGraphics g, int mouseX, int mouseY, CraftSession session, int x, int y, int w, float a) {
        this.x = x;
        this.y = y;
        this.w = w;
        hits.clear();
        hovered = null;
        Craftable weapon = session.weapon;
        if (weapon == null || a <= 0.01f) return;
        // the kinds this weapon has skins of
        List<String> kinds = new ArrayList<>();
        kinds.add(ALL);
        for (String kind : List.of(CraftSession.SKIN, CraftSession.SPECIAL_SKIN)) {
            if (weapon.skins().values().stream().anyMatch(s -> CraftSession.skinKind(s).equals(kind))) kinds.add(kind);
        }
        if (!kinds.contains(filter)) filter = ALL;
        int chipY = y + HEIGHT - CARD_H - 18;
        int chipX = x;
        for (String kind : kinds) {
            String label = kind.equals(ALL) ? Component.translatable("blockland.craft.ingame.all_skins").getString()
                    : CraftSession.categoryName(kind).getString();
            int cw = UiFont.width(label) + 12;
            boolean on = kind.equals(filter);
            boolean over = inside(mouseX, mouseY, chipX, chipY, cw, 13);
            float hv = spring("chip" + kind, over || on ? 1 : 0).get();
            Draw.rect(g, chipX, chipY, cw, 13, ColorMath.fade(on ? 0xFF1E2A3E : ColorMath.mix(0xC0121822, CARD_HOVER, hv), a));
            if (on) g.fill(chipX, chipY + 12, chipX + cw, chipY + 13, ColorMath.fade(ACCENT, a));
            Draw.text(g, label, chipX + 6, chipY + 3, 0.75f, ColorMath.fade(ColorMath.mix(MUTED, TEXT, hv), a), false);
            String target = kind;
            hits.add(new Hit(chipX, chipY, cw, 13, () -> {
                if (!target.equals(filter)) {
                    filter = target;
                    scrollTarget = 0;
                    CraftSession.playUi(1.4f, 0.25f);
                }
            }));
            chipX += cw + 4;
        }

        List<CraftData.Skin> skins = new ArrayList<>();
        for (CraftData.Skin skin : weapon.skins().values()) {
            if (filter.equals(ALL) || CraftSession.skinKind(skin).equals(filter)) skins.add(skin);
        }
        int total = skins.size() * (CARD_W + GAP) - GAP;
        float maxScroll = Math.max(0, total - w);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll.setTarget(scrollTarget);
        int startX = total < w ? x + (w - total) / 2 : x - Math.round(scroll.get());
        int cardY = y + HEIGHT - CARD_H;
        String worn = session.edited.skin();
        String saved = session.baseline(weapon).skin();
        g.enableScissor(x - 4, cardY - 6, x + w + 4, cardY + CARD_H + 2);
        for (int i = 0; i < skins.size(); i++) {
            CraftData.Skin skin = skins.get(i);
            int cx = startX + i * (CARD_W + GAP);
            if (cx + CARD_W < x - 4 || cx > x + w + 4) continue;
            boolean over = mouseY >= cardY - 6 && inside(mouseX, mouseY, Math.max(cx, x), cardY, Math.min(cx + CARD_W, x + w) - Math.max(cx, x), CARD_H);
            if (over) hovered = skin.id;
            boolean chosen = skin.id.equals(worn);
            float hv = spring("card" + skin.id, over ? 1 : 0).get();
            float sel = spring("sel" + skin.id, chosen ? 1 : 0).get();
            int cy = cardY - Math.round(hv * 4 + sel * 2);
            int rarity = CraftSession.rarityColor(skin.rarity, true);
            Draw.rect(g, cx, cy, CARD_W, CARD_H, ColorMath.fade(ColorMath.mix(0xE0101722, CARD_HOVER, hv), a));
            // the swatch: the skin's own colours
            int[] cells = SkinSwatches.of(weapon, skin);
            int sw = CARD_W - 8, sh = CARD_H - 20;
            for (int row = 0; row < SkinSwatches.ROWS; row++) {
                for (int col = 0; col < SkinSwatches.COLUMNS; col++) {
                    int x0 = cx + 4 + col * sw / SkinSwatches.COLUMNS, x1 = cx + 4 + (col + 1) * sw / SkinSwatches.COLUMNS;
                    int y0 = cy + 4 + row * sh / SkinSwatches.ROWS, y1 = cy + 4 + (row + 1) * sh / SkinSwatches.ROWS;
                    g.fill(x0, y0, x1, y1, ColorMath.fade(cells[row * SkinSwatches.COLUMNS + col], a));
                }
            }
            if (skin.hasEffects() || skin.isSpecial()) {
                // animated camos and special skins shimmer over the swatch
                float shimmer = (float) ((System.nanoTime() / 1e9 * 0.8 + i * 0.37) % 1.0);
                int sx = cx + 4 + Math.round(shimmer * (sw - 6));
                g.fill(sx, cy + 4, sx + 6, cy + 4 + sh, ColorMath.fade(0x40FFFFFF, a));
            }
            boolean locked = session.locked(skin.id);
            if (locked) {
                // not owned: the swatch dims under a padlock (it can still be tried on, not saved)
                g.fill(cx + 4, cy + 4, cx + 4 + sw, cy + 4 + sh, ColorMath.fade(0x80101722, a));
                Draw.rect(g, cx + 6, cy + 6, 11, 13, ColorMath.fade(0xC0101722, a));
                CraftSession.lockIcon(g, cx + 8, cy + 8, ColorMath.fade(CraftSession.LOCKED, a));
            }
            Draw.text(g, Draw.fit(CraftCatalog.skinName(weapon, skin).getString(), CARD_W - 8), cx + 4, cy + CARD_H - 13, 0.75f,
                    ColorMath.fade(chosen ? TEXT : MUTED, a), false);
            g.fill(cx, cy + CARD_H - 2, cx + CARD_W, cy + CARD_H, ColorMath.fade(rarity, a * (0.5f + 0.5f * Math.max(hv, sel))));
            if (sel > 0.01f) Draw.outline(g, cx, cy, CARD_W, CARD_H, ColorMath.fade(ACCENT, a * sel));
            if (skin.id.equals(saved)) {
                String tag = Component.translatable(session.isHeldMode() ? "blockland.craft.equipped" : "blockland.craft.saved_tag").getString();
                Draw.rect(g, cx + CARD_W - 6 - Math.round(UiFont.width(tag) * 0.75f) - 4, cy + 3, Math.round(UiFont.width(tag) * 0.75f) + 6, 10,
                        ColorMath.fade(0xC0101722, a));
                Draw.text(g, tag, cx + CARD_W - 6 - UiFont.width(tag) * 0.75f, cy + 5, 0.75f, ColorMath.fade(OK, a), false);
            }
            String id = skin.id;
            hits.add(new Hit(Math.max(cx, x), cy, Math.min(cx + CARD_W, x + w) - Math.max(cx, x), CARD_H,
                    () -> session.choose(CraftSession.SKIN, id)));
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int barW = Math.max(16, Math.round(w * w / (float) total));
            int barX = x + Math.round((w - barW) * (scroll.get() / maxScroll));
            g.fill(barX, cardY + CARD_H + 3, barX + barW, cardY + CARD_H + 4, ColorMath.fade(0x55FFFFFF, a));
        }

        // the name of what is shown, large, with what kind of skin it is
        CraftData.Skin shown = weapon.skins().get(hovered != null ? hovered : worn);
        if (shown != null) {
            String name = CraftCatalog.skinName(weapon, shown).getString();
            float scale = 1.5f;
            int color = CraftSession.rarityColor(shown.rarity, true);
            Draw.text(g, name, x + w / 2f - UiFont.width(name) * scale / 2f, y - 2, scale, ColorMath.fade(color, a), true);
            String kind = CraftSession.categoryName(CraftSession.skinKind(shown)).getString();
            // a skin the player does not own says where to get it
            if (session.locked(shown.id)) kind += " · " + Component.translatable("blockland.craft.skin.locked_hint").getString();
            Draw.text(g, kind, x + w / 2f - UiFont.width(kind) * 0.75f / 2f, y + 13, 0.75f,
                    ColorMath.fade(session.locked(shown.id) ? CraftSession.LOCKED : DIM, a), false);
        }
    }

    boolean contains(double mouseX, double mouseY) {
        return inside(mouseX, mouseY, x - 4, y - 4, w + 8, HEIGHT + 8);
    }

    boolean mouseClicked(double mouseX, double mouseY) {
        for (Hit hit : hits) {
            if (hit.w > 0 && inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        return false;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!contains(mouseX, mouseY)) return false;
        scrollTarget -= (float) scrollY * 40f;
        return true;
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
