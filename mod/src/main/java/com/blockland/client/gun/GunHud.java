package com.blockland.client.gun;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.ui.Draw;
import com.blockland.gun.GunStats;
import com.blockland.network.packet.GunPackets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Gun HUD in the style of modern shooters: ammo counter with magazine pips (bottom right), a dynamic crosshair
 * that opens with the real spread, the shared {@link HitMarker} and short hints.
 * Every motion uses the {@code anim} library.
 */
final class GunHud {
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;
    private static final int LOW = 0xFFFFB02E;
    private static final int EMPTY = 0xFFFF5A4E;
    private static final Spring ammoPulse = new Spring(0).config(500, 22);
    private static int lastAmmo = -1;
    private static String hint = "";
    private static final AnimValue hintAlpha = new AnimValue(0);
    private static final AnimValue crosshairAlpha = new AnimValue(1);

    private GunHud() {
    }

    static void hit(GunPackets.HitKind kind) {
        HitMarker.show(kind == GunPackets.HitKind.HEAD || kind == GunPackets.HitKind.HEAD_KILL,
                kind == GunPackets.HitKind.KILL || kind == GunPackets.HitKind.HEAD_KILL);
    }

    static void flashHint(String text) {
        hint = text;
        hintAlpha.snap(1);
        hintAlpha.to(0, 0.6, Ease.QUAD_IN).delay(1.2);
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        GunStats stats = ClientGuns.stats();
        if (mc.player == null || stats == null || !ClientGuns.holdingGun() || mc.options.hideGui) return;
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        crosshair(g, stats, w, h, mc);
        HitMarker.render(g, w, h);
        ammo(g, font, stats, w, h);
        hints(g, font, stats, w, h);
    }

    private static void crosshair(GuiGraphics g, GunStats stats, int w, int h, Minecraft mc) {
        float aim = ClientGuns.aim();
        crosshairAlpha.toIfChanged(aim > 0.4f || ClientGuns.isReloading() ? 0 : 1, 0.12, Ease.QUAD_OUT);
        float a = crosshairAlpha.get();
        if (a < 0.02f) return;
        float fov = mc.options.fov().get();
        // gap = where the spread cone meets the screen edge of the crosshair
        float gap = (float) (Math.tan(Math.toRadians(ClientGuns.currentSpread())) / Math.tan(Math.toRadians(fov) / 2) * (h / 2f));
        gap = Math.max(3, gap);
        float cx = w / 2f, cy = h / 2f;
        int color = ColorMath.fade(0xFFFFFFFF, a * 0.9f);
        int shadow = ColorMath.fade(0xFF000000, a * 0.45f);
        int len = 5;
        bar(g, cx - gap - len, cy - 0.5f, len, 1, color, shadow);
        bar(g, cx + gap, cy - 0.5f, len, 1, color, shadow);
        bar(g, cx - 0.5f, cy - gap - len, 1, len, color, shadow);
        bar(g, cx - 0.5f, cy + gap, 1, len, color, shadow);
        bar(g, cx - 0.5f, cy - 0.5f, 1, 1, ColorMath.fade(0xFFFFFFFF, a * 0.6f), 0);
    }

    private static void bar(GuiGraphics g, float x, float y, float w, float h, int color, int shadow) {
        int ix = Math.round(x), iy = Math.round(y), iw = Math.max(1, Math.round(w)), ih = Math.max(1, Math.round(h));
        if (ColorMath.alpha(shadow) > 0) g.fill(ix - 1, iy - 1, ix + iw + 1, iy + ih + 1, shadow);
        g.fill(ix, iy, ix + iw, iy + ih, color);
    }

    private static void ammo(GuiGraphics g, Font font, GunStats stats, int w, int h) {
        int ammo = ClientGuns.ammo();
        int capacity = stats.capacity();
        if (ammo < lastAmmo) ammoPulse.impulse(9);
        lastAmmo = ammo;
        int reserve = ClientGuns.reserve();
        boolean low = ammo <= Math.max(1, capacity / 4);
        int ammoColor = ammo == 0 ? EMPTY : low ? LOW : TEXT;

        int right = w - 14, bottom = h - 14;
        Draw.glow(g, right - 30, bottom - 22, 70, 36, 0x66000000);
        // magazine pips, one per round (compressed for big magazines)
        int pipW = capacity > 30 ? 1 : 2, gapW = 1;
        int pipsWidth = capacity * (pipW + gapW) - gapW;
        int pipX = right - pipsWidth, pipY = bottom - 4;
        for (int i = 0; i < capacity; i++) {
            boolean full = i < ammo;
            g.fill(pipX + i * (pipW + gapW), pipY, pipX + i * (pipW + gapW) + pipW, pipY + 4,
                    full ? ColorMath.fade(ammoColor, 0.95f) : 0x40FFFFFF);
        }
        // big round count + reserve
        String reserveText = "/ " + reserve;
        float scale = 2.2f + ammoPulse.get() * 0.012f;
        String count = String.valueOf(ammo);
        float countW = font.width(count) * scale;
        float reserveX = right - font.width(reserveText);
        Draw.text(g, font, reserveText, reserveX, bottom - 16, 1f, reserve == 0 ? EMPTY : MUTED, true);
        Draw.text(g, font, count, reserveX - 5 - countW, bottom - 9 - font.lineHeight * scale, scale, ammoColor, true);
        // name and configuration
        String name = stats.gun().name;
        Draw.text(g, font, name, right - font.width(name), bottom - 50, 1f, TEXT, true);
        String detail = ClientGuns.fireMode().label
                + " · " + stats.sight().name + (stats.laser().hasBeam() ? " · " + stats.laser().name : "");
        Draw.text(g, font, detail, right - font.width(detail), bottom - 38, 1f, MUTED, true);
        if (ClientGuns.isReloading()) {
            float p = ClientGuns.reloadProgress();
            g.fill(pipX, pipY + 6, right, pipY + 7, 0x30FFFFFF);
            g.fill(pipX, pipY + 6, pipX + Math.round((right - pipX) * p), pipY + 7, 0xFFFFFFFF);
        }
    }

    private static void hints(GuiGraphics g, Font font, GunStats stats, int w, int h) {
        String text = null;
        int color = TEXT;
        if (ClientGuns.isReloading()) {
            text = "换弹中";
            color = MUTED;
        } else if (ClientGuns.ammo() == 0) {
            text = ClientGuns.reserve() > 0 ? "按 R 换弹" : "弹药耗尽";
            color = ClientGuns.reserve() > 0 ? LOW : EMPTY;
        } else if (ClientGuns.ammo() <= Math.max(1, stats.capacity() / 4) && ClientGuns.reserve() > 0) {
            text = "按 R 换弹";
            color = LOW;
        }
        float cx = w / 2f, y = h / 2f + 22;
        if (text != null) Draw.text(g, font, text, cx - font.width(text) / 2f, y, 1f, ColorMath.fade(color, 0.9f), true);
        float a = hintAlpha.get();
        if (a > 0.02f && !hint.isEmpty()) {
            Draw.text(g, font, hint, cx - font.width(hint) / 2f, y + 12, 1f, ColorMath.fade(EMPTY, a), true);
        }
    }
}
