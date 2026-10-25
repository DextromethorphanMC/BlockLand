package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.font.UiFont;
import com.blockland.client.profile.ClientProfile;
import com.blockland.client.profile.ItemIcons;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.ProfilePackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The lobby's 库存 tab: everything the player owns on this server — granted, bought or free — by category, each with
 * how long it is theirs (永久 / 剩余 N 天), and a detail panel to put an item into one of its slots. The server sends the
 * list and checks every change; this only shows it and asks.
 */
public final class InventoryPanel {
    private static final int TILE_W = 132, TILE_H = 92, GAP = 8;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Map<String, Spring> hover = new HashMap<>();
    private final AnimValue detailIn = new AnimValue(1);
    private final List<Hit> hits = new ArrayList<>();
    private String category = "";
    private String selected = "";
    private float scroll;
    private int gridX, gridY, gridW, gridH, content;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    /** The tab opened: the latest inventory is asked for. */
    public void open() {
        ClientProfile.request(ProfilePackets.Kind.INVENTORY);
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float alpha) {
        hits.clear();
        List<ProfilePackets.Item> all = ClientProfile.inventory();
        float time = (float) Ticker.global().time();

        // header: title, count and the category chips
        Draw.text(g, tr("blockland.inventory.title"), x, y + 3, 1.2f, ColorMath.fade(TEXT, alpha), false);
        String count = tr("blockland.inventory.count", all.size());
        Draw.text(g, count, x + w - UiFont.width(count), y + 5, 1f, ColorMath.fade(DIM, alpha), false);
        int cx = x + (int) (UiFont.width(tr("blockland.inventory.title")) * 1.2f) + 14;
        for (String c : categories(all)) {
            String label = c.isEmpty() ? tr("blockland.inventory.category.all") : tr(c);
            int cw = UiFont.width(label) + 14;
            boolean on = c.equals(category);
            float hv = spring("chip" + c, inside(mouseX, mouseY, cx, y + 2, cw, 14));
            Draw.rect(g, cx, y + 2, cw, 14, ColorMath.fade(on ? ACCENT : ColorMath.mix(CARD, CARD_HOVER, hv), alpha));
            Draw.text(g, label, cx + 7, y + 5, 1f, ColorMath.fade(on ? 0xFF1A1206 : MUTED, alpha), false);
            hits.add(new Hit(cx, y + 2, cw, 14, () -> {
                category = c;
                scroll = 0;
            }));
            cx += cw + 4;
        }

        List<ProfilePackets.Item> shown = all.stream().filter(i -> category.isEmpty() || i.category().equals(category)).toList();
        int detailW = Math.min(250, (int) (w * 0.34f));
        gridX = x;
        gridY = y + 26;
        gridW = w - detailW - 14;
        gridH = h - 26;
        Draw.rect(g, gridX, gridY, gridW, gridH, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, gridX, gridY, gridW, gridH, ColorMath.fade(LINE, alpha));
        if (shown.isEmpty()) {
            String empty = tr(ClientProfile.inventoryLoaded() ? "blockland.inventory.empty" : "blockland.inventory.loading");
            Draw.text(g, empty, gridX + (gridW - UiFont.width(empty)) / 2f, gridY + gridH / 2f - 4, 1f, ColorMath.fade(MUTED, alpha), false);
        } else {
            if (shown.stream().noneMatch(i -> i.key().equals(selected))) selected = shown.get(0).key();
            int cols = Math.max(1, (gridW - 12 + GAP) / (TILE_W + GAP));
            int rows = (shown.size() + cols - 1) / cols;
            content = rows * (TILE_H + GAP);
            scroll = clampScroll(scroll, content, gridH - 12);
            g.enableScissor(gridX + 1, gridY + 1, gridX + gridW - 1, gridY + gridH - 1);
            for (int i = 0; i < shown.size(); i++) {
                int tx = gridX + 6 + (i % cols) * (TILE_W + GAP);
                int ty = gridY + 6 + (i / cols) * (TILE_H + GAP) - Math.round(scroll);
                if (ty + TILE_H < gridY || ty > gridY + gridH) continue;
                tile(g, mouseX, mouseY, shown.get(i), tx, ty, alpha, time);
            }
            g.disableScissor();
        }

        ProfilePackets.Item item = all.stream().filter(i -> i.key().equals(selected)).findFirst().orElse(null);
        detail(g, mouseX, mouseY, item, x + w - detailW, y + 26, detailW, h - 26, alpha * detailIn.get(), time);
    }

    private void tile(GuiGraphics g, int mouseX, int mouseY, ProfilePackets.Item item, int x, int y, float alpha, float time) {
        boolean over = inside(mouseX, mouseY, x, y, TILE_W, TILE_H) && inside(mouseX, mouseY, gridX, gridY, gridW, gridH);
        boolean chosen = item.key().equals(selected);
        float hv = spring("tile" + item.key(), over);
        Draw.rect(g, x, y, TILE_W, TILE_H, ColorMath.fade(chosen ? CARD_SELECTED : ColorMath.mix(CARD, CARD_HOVER, hv), alpha));
        int rarity = rarityColor(item.rarity());
        g.fill(x, y + TILE_H - 2, x + TILE_W, y + TILE_H, ColorMath.fade(rarity, alpha * 0.9f));
        if (chosen) Draw.outline(g, x, y, TILE_W, TILE_H, ColorMath.fade(ACCENT, alpha));
        int iconH = 40;
        ItemIcons.draw(g, item.icon(), item.name().getString(), x + 6, y + 6, TILE_W - 12, iconH, time);
        Draw.text(g, Draw.fit(item.name().getString(), TILE_W - 12), x + 6, y + iconH + 11, 1f, ColorMath.fade(TEXT, alpha), false);
        Draw.text(g, Draw.fit(expiry(item.until()), TILE_W - 12), x + 6, y + iconH + 24, 1f,
                ColorMath.fade(item.until() == 0 ? OK : MUTED, alpha), false);
        if (!item.equipped().isEmpty()) {
            String worn = tr("blockland.inventory.equipped");
            int bw = UiFont.width(worn) + 8;
            Draw.rect(g, x + TILE_W - bw - 4, y + 4, bw, 11, ColorMath.fade(0xE02FB257, alpha));
            Draw.text(g, worn, x + TILE_W - bw, y + 6, 1f, ColorMath.fade(0xFFFFFFFF, alpha), false);
        }
        String key = item.key();
        hits.add(new Hit(x, Math.max(y, gridY), TILE_W, Math.min(y + TILE_H, gridY + gridH) - Math.max(y, gridY), () -> {
            if (!key.equals(selected)) {
                selected = key;
                Anim.fromTo(detailIn, 0, 1, 0.3).ease(Ease.CUBIC_OUT);
            }
        }));
    }

    private void detail(GuiGraphics g, int mouseX, int mouseY, ProfilePackets.Item item, int x, int y, int w, int h, float alpha, float time) {
        Draw.rect(g, x, y, w, h, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, x, y, w, h, ColorMath.fade(LINE, alpha));
        if (item == null) return;
        int pad = 10;
        int iconW = w - pad * 2, iconH = Math.round(iconW / 3.2f);
        ItemIcons.draw(g, item.icon(), ClientLobbyState.selfName(), x + pad, y + pad, iconW, iconH, time);
        float ty = y + pad + iconH + 8;
        int rarity = rarityColor(item.rarity());
        List<FormattedCharSequence> name = Draw.wrap(item.name().getString(), (iconW) / 1.3f);
        for (FormattedCharSequence line : name.subList(0, Math.min(2, name.size()))) {
            g.pose().pushMatrix();
            g.pose().translate(x + pad, ty);
            g.pose().scale(1.3f, 1.3f);
            Draw.text(g, line, 0, 0, ColorMath.fade(TEXT, alpha));
            g.pose().popMatrix();
            ty += UiFont.LINE_HEIGHT * 1.3f + 2;
        }
        Draw.text(g, tr("blockland.inventory.rarity." + item.rarity()) + " · " + tr(item.category()), x + pad, ty + 1, 1f,
                ColorMath.fade(rarity, alpha), false);
        ty += 14;
        String until = item.until() == 0 ? tr("blockland.inventory.permanent")
                : tr("blockland.inventory.until", DATE.format(Instant.ofEpochMilli(item.until()))) + " · " + expiry(item.until());
        Draw.text(g, Draw.fit(until, iconW), x + pad, ty, 1f, ColorMath.fade(item.until() == 0 ? OK : GOLD, alpha), false);
        ty += 16;
        for (FormattedCharSequence line : Draw.wrap(item.description().getString(), iconW)) {
            if (ty > y + h - 70) break;
            Draw.text(g, line, x + pad, ty, ColorMath.fade(MUTED, alpha));
            ty += UiFont.LINE_HEIGHT + 3;
        }

        // one button per slot the item fits: put it there, or take it off
        int by = y + h - pad - 18;
        for (int i = item.slots().size() - 1; i >= 0; i--) {
            String slot = item.slots().get(i);
            boolean worn = item.equipped().contains(slot);
            String label = worn ? tr("blockland.inventory.unequip", slotName(slot)) : tr("blockland.inventory.equip", slotName(slot));
            boolean over = inside(mouseX, mouseY, x + pad, by, iconW, 18);
            float hv = spring("slot" + slot, over);
            int base = worn ? 0xFF3A4458 : GO;
            Draw.rect(g, x + pad, by, iconW, 18, ColorMath.fade(ColorMath.lighten(base, 0.08f * hv), alpha));
            Draw.text(g, label, x + pad + (iconW - UiFont.width(label)) / 2f, by + 5, 1f, ColorMath.fade(0xFFFFFFFF, alpha), false);
            String key = item.key();
            hits.add(new Hit(x + pad, by, iconW, 18, () -> ClientProfile.equip(slot, worn ? "" : key)));
            by -= 22;
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        for (Hit hit : hits) {
            if (hit.h > 0 && inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!inside(mouseX, mouseY, gridX, gridY, gridW, gridH)) return false;
        scroll = clampScroll(scroll - (float) amount * 30, content, gridH - 12);
        return true;
    }

    // ---- shared with the profile tab ------------------------------------------------------------

    /** 永久 / 剩余 N 天 / 剩余 N 小时 / 已过期. */
    static String expiry(long until) {
        if (until == 0) return tr("blockland.inventory.permanent");
        long left = until - System.currentTimeMillis();
        if (left <= 0) return tr("blockland.inventory.expired");
        long days = left / 86_400_000L;
        if (days >= 1) return tr("blockland.inventory.days_left", days);
        return tr("blockland.inventory.hours_left", Math.max(1, left / 3_600_000L));
    }

    static int rarityColor(String rarity) {
        return switch (rarity) {
            case "rare" -> 0xFF4FA3FF;
            case "epic" -> 0xFFB46CFF;
            case "legendary" -> 0xFFFFB02E;
            // special skins (their own model or shot effects), as in the 工匠
            case "special" -> 0xFFFF4D5E;
            default -> 0xFF9AA4B5;
        };
    }

    /** 名片 / 喷漆槽 N / the slot's own name. */
    static String slotName(String slot) {
        int dot = slot.lastIndexOf('.');
        if (dot > 0) return tr("blockland.inventory.slot." + slot.substring(0, dot), slot.substring(dot + 1));
        return tr("blockland.inventory.slot." + slot);
    }

    private static List<String> categories(List<ProfilePackets.Item> items) {
        Set<String> set = new LinkedHashSet<>();
        for (ProfilePackets.Item item : items) set.add(item.category());
        List<String> list = new ArrayList<>();
        list.add("");
        list.addAll(set);
        return list;
    }

    private static float clampScroll(float value, int content, int view) {
        return Math.max(0, Math.min(value, Math.max(0, content - view)));
    }

    private float spring(String key, boolean on) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(0).config(320, 26));
        spring.setTarget(on ? 1 : 0);
        return spring.get();
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
