package com.blockland.client.craft;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Spring;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.craft.Build;
import com.blockland.gun.GunData;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.blockland.melee.MeleeData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * The numbers of a crafted weapon: a gun's stat bars (what the fitted build has, and in green / red what the option under
 * the mouse would change) and a knife's damage lines. One instance per editor (its bars ease on their own).
 */
final class CraftStats {
    private record Stat(String key, float applied, float preview, String value) {
    }

    private final Map<String, Spring> bars = new HashMap<>();

    /**
     * @param applied what the weapon wears now, {@code shown} what is previewed
     * @param compact fewer rows (the in-game screen)
     */
    void drawGun(GuiGraphics g, GunData.Gun gun, Build applied, Build shown, int x, int y, int w, int h, float a, boolean compact) {
        drawGun(g, gun, applied, shown, x, y, w, h, a, compact, 1);
    }

    /** The same with the stats in {@code columns} side by side (filled row by row). */
    void drawGun(GuiGraphics g, GunData.Gun gun, Build applied, Build shown, int x, int y, int w, int h, float a, boolean compact,
                 int columns) {
        GunStats now = GunStats.of(gun.id, new GunState(applied, 0)).orElse(null);
        GunStats next = GunStats.of(gun.id, new GunState(shown, 0)).orElse(null);
        if (now == null || next == null || h < 20) return;
        List<Stat> stats = List.of(
                new Stat("damage", damage(now), damage(next), String.format("%.1f", next.damageAt(0))),
                new Stat("rate", next.gun().rpm / 1100f, next.gun().rpm / 1100f, (int) next.gun().rpm + " RPM"),
                new Stat("range", now.range() / 150f, next.range() / 150f, String.format("%.0f", next.range())),
                new Stat("hip", 1 - now.baseSpread(false) / 8f, 1 - next.baseSpread(false) / 8f, String.format("%.1f°", next.baseSpread(false))),
                new Stat("ads_accuracy", 1 - now.baseSpread(true) / 1.5f, 1 - next.baseSpread(true) / 1.5f, String.format("%.2f°", next.baseSpread(true))),
                new Stat("recoil", 1 - now.recoilVertical() / 4f, 1 - next.recoilVertical() / 4f, String.format("%.2f", next.recoilVertical())),
                new Stat("ads_speed", 1 - now.adsTime() / 0.5f, 1 - next.adsTime() / 0.5f, Math.round(next.adsTime() * 1000) + " ms"),
                new Stat("reload", 1 - now.reloadTime(false) / 4f, 1 - next.reloadTime(false) / 4f, String.format("%.2f s", next.reloadTime(false))),
                new Stat("capacity", now.capacity() / 50f, next.capacity() / 50f, String.valueOf(next.capacity())),
                // what the rounds wear off plates and hulls (armour-piercing rounds: the ammo slot)
                new Stat("armor", now.traits().armorDamage() / 3f, next.traits().armorDamage() / 3f,
                        String.format("×%.1f", next.traits().armorDamage())));
        if (compact) stats = List.of(stats.get(0), stats.get(1), stats.get(2), stats.get(4), stats.get(5), stats.get(6));
        Draw.rect(g, x, y, w, h, ColorMath.fade(PANEL, a));
        columns = Math.max(1, columns);
        int rows = (stats.size() + columns - 1) / columns;
        int rowH = Math.max(9, Math.min(14, (h - 6) / rows));
        int labelW = 0;
        for (Stat s : stats) labelW = Math.max(labelW, UiFont.width(Component.translatable("blockland.craft.stat." + s.key()).getString()));
        int valueW = 52;
        int columnW = (w - (columns - 1) * 4) / columns;
        int barW = Math.max(20, columnW - labelW - valueW - 30);
        for (int i = 0; i < stats.size(); i++) {
            Stat s = stats.get(i);
            int cx = x + (i % columns) * (columnW + 4);
            int ry = y + 6 + (i / columns) * rowH;
            if (ry + 8 > y + h) break;
            int barX = cx + 8 + labelW + 6;
            if (i % columns > 0) g.fill(cx - 2, ry - 1, cx - 1, ry + 9, ColorMath.fade(LINE, a));
            Draw.text(g, Component.translatable("blockland.craft.stat." + s.key()).getString(), cx + 8, ry, 1f, ColorMath.fade(MUTED, a), false);
            float base = spring01("a" + s.key(), s.applied());
            float target = spring01("p" + s.key(), s.preview());
            g.fill(barX, ry + 3, barX + barW, ry + 6, ColorMath.fade(0x30FFFFFF, a));
            int common = Math.round(barW * Math.min(base, target));
            g.fill(barX, ry + 3, barX + common, ry + 6, ColorMath.fade(TEXT, a * 0.85f));
            if (target > base + 0.002f) {
                g.fill(barX + common, ry + 3, barX + Math.round(barW * target), ry + 6, ColorMath.fade(OK, a));
            } else if (base > target + 0.002f) {
                g.fill(barX + common, ry + 3, barX + Math.round(barW * base), ry + 6, ColorMath.fade(ERROR, a));
            }
            Draw.text(g, s.value(), cx + columnW - 8 - UiFont.width(s.value()), ry, 1f, ColorMath.fade(TEXT, a), false);
        }
    }

    /** Rows a gun panel needs (to size it). */
    static int gunRows(boolean compact) {
        return compact ? 6 : 10;
    }

    /** Height of the full gun panel with its stats in {@code columns}, at {@code rowH} per row. */
    static int gunHeight(int columns, int rowH) {
        int rows = (gunRows(false) + Math.max(1, columns) - 1) / Math.max(1, columns);
        return rows * rowH + 8;
    }

    /** @return the panel's height */
    static int drawKnife(GuiGraphics g, MeleeData.Knife knife, int x, int y, int w, float a) {
        List<Component> rows = List.of(
                Component.translatable("blockland.craft.knife.light", format(knife.light.damage)),
                Component.translatable("blockland.craft.knife.heavy", format(knife.heavy.damage)),
                Component.translatable("blockland.craft.knife.backstab", format(knife.heavy.backstabMultiplier)),
                Component.translatable("blockland.craft.knife.cosmetic"));
        int h = rows.size() * 13 + 10;
        Draw.rect(g, x, y, w, h, ColorMath.fade(PANEL, a));
        for (int i = 0; i < rows.size(); i++) {
            Draw.text(g, Draw.fit(rows.get(i).getString(), w - 16), x + 8, y + 6 + i * 13, 1f,
                    ColorMath.fade(i == rows.size() - 1 ? DIM : MUTED, a), false);
        }
        return h;
    }

    private static String format(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value)) : String.format("%.2f", value);
    }

    private static float damage(GunStats s) {
        return Math.min(1, s.damageAt(0) / 20f);
    }

    private float spring01(String key, float target) {
        Spring spring = bars.computeIfAbsent(key, k -> new Spring(target).config(200, 24));
        spring.setTarget(Math.max(0, Math.min(1, target)));
        return Math.max(0, Math.min(1, spring.get()));
    }
}
