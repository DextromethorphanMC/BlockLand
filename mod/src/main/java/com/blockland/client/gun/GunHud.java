package com.blockland.client.gun;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.font.UiFont;
import com.blockland.client.hud.Crosshair;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.ui.Draw;
import com.blockland.gun.GunStats;
import com.blockland.network.packet.GunPackets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import static com.blockland.client.ui.Tr.tr;

/**
 * Gun HUD in the style of modern shooters: ammo counter with magazine pips (bottom right; a dual pair shows each gun's
 * rounds), a dynamic crosshair that opens with the real spread, the shared {@link HitMarker} and short hints.
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
        int w = g.guiWidth(), h = g.guiHeight();
        crosshair(g, stats, w, h, mc);
        HitMarker.render(g, w, h);
        ammo(g, stats, w, h);
        hints(g, stats, w, h);
    }

    private static void crosshair(GuiGraphics g, GunStats stats, int w, int h, Minecraft mc) {
        float aim = ClientGuns.aim();
        // a shell reload can be cut short by firing: the crosshair stays
        boolean hide = aim > 0.4f || ClientGuns.isReloading() && !ClientGuns.isShellReloading();
        crosshairAlpha.toIfChanged(hide ? 0 : 1, 0.12, Ease.QUAD_OUT);
        float a = crosshairAlpha.get();
        if (a < 0.02f) return;
        float fov = mc.options.fov().get();
        // gap = where the spread cone meets the screen edge of the crosshair; the player's style decides the rest
        float gap = (float) (Math.tan(Math.toRadians(ClientGuns.currentSpread())) / Math.tan(Math.toRadians(fov) / 2) * (h / 2f));
        Crosshair.render(g, w / 2f, h / 2f, gap, a);
    }

    private static void ammo(GuiGraphics g, GunStats stats, int w, int h) {
        int ammo = ClientGuns.ammo();
        int capacity = stats.capacity();
        if (ammo < lastAmmo) ammoPulse.impulse(9);
        lastAmmo = ammo;
        int reserve = ClientGuns.reserve();
        boolean low = ammo <= Math.max(1, capacity / 4);
        int ammoColor = ammo == 0 ? EMPTY : low ? LOW : TEXT;

        int right = w - 14, bottom = h - 14;
        Draw.glow(g, right - 30, bottom - 22, 70, 36, 0x66000000);
        // magazine pips, one per round (compressed for big magazines); a dual pair: the left gun's, a gap, the right's
        boolean dual = ClientGuns.isDual();
        int[] split = dual ? ClientGuns.dualRounds() : null;
        int pipW = capacity > 30 ? 1 : 2, gapW = 1, pairGap = dual ? 4 : 0;
        int pipsWidth = capacity * (pipW + gapW) - gapW + pairGap;
        int pipX = right - pipsWidth, pipY = bottom - 4;
        int half = (capacity + 1) / 2;
        for (int i = 0; i < capacity; i++) {
            boolean full = dual ? (i < capacity - half ? i < split[0] : i - (capacity - half) < split[1]) : i < ammo;
            int x = pipX + i * (pipW + gapW) + (dual && i >= capacity - half ? pairGap : 0);
            g.fill(x, pipY, x + pipW, pipY + 4, full ? ColorMath.fade(ammoColor, 0.95f) : 0x40FFFFFF);
        }
        // big round count + reserve
        String reserveText = "/ " + reserve;
        float scale = 2.2f + ammoPulse.get() * 0.012f;
        String count = dual ? split[0] + " | " + split[1] : String.valueOf(ammo);
        float countW = UiFont.width(count) * scale;
        float reserveX = right - UiFont.width(reserveText);
        Draw.text(g,reserveText, reserveX, bottom - 16, 1f, reserve == 0 ? EMPTY : MUTED, true);
        Draw.text(g,count, reserveX - 5 - countW, bottom - 9 - UiFont.LINE_HEIGHT * scale, scale, ammoColor, true);
        // name and configuration
        String name = stats.gun().displayName().getString();
        Draw.text(g,name, right - UiFont.width(name), bottom - 50, 1f, TEXT, true);
        // a dual pair has no sight: its use key fires a volley
        String sightText = dual ? tr("blockland.gun.hud.dual") : stats.gun().describe("optic", stats.sight().id).name().getString();
        String detail = tr("blockland.gun.fire_mode." + ClientGuns.fireMode().name().toLowerCase(java.util.Locale.ROOT))
                + " · " + sightText
                + (stats.laser().hasBeam() ? " · " + stats.gun().describe("laser", stats.laser().id).name().getString() : "");
        Draw.text(g,detail, right - UiFont.width(detail), bottom - 38, 1f, MUTED, true);
        if (ClientGuns.isReloading()) {
            float p = ClientGuns.reloadProgress();
            g.fill(pipX, pipY + 6, right, pipY + 7, 0x30FFFFFF);
            g.fill(pipX, pipY + 6, pipX + Math.round((right - pipX) * p), pipY + 7, 0xFFFFFFFF);
        }
    }

    private static void hints(GuiGraphics g, GunStats stats, int w, int h) {
        String text = null;
        int color = TEXT;
        if (ClientGuns.isShellReloading()) {
            text = ClientGuns.ammo() > 0 ? tr("blockland.gun.hud.shell_reload") : tr("blockland.gun.hud.reloading");
            color = MUTED;
        } else if (ClientGuns.isReloading()) {
            text = tr("blockland.gun.hud.reloading");
            color = MUTED;
        } else if (ClientGuns.ammo() == 0) {
            text = ClientGuns.reserve() > 0 ? tr("blockland.gun.hud.reload_hint", ClientGuns.RELOAD.getTranslatedKeyMessage().getString())
                    : tr("blockland.gun.hud.out_of_ammo");
            color = ClientGuns.reserve() > 0 ? LOW : EMPTY;
        } else if (ClientGuns.ammo() <= Math.max(1, stats.capacity() / 4) && ClientGuns.reserve() > 0) {
            text = tr("blockland.gun.hud.reload_hint", ClientGuns.RELOAD.getTranslatedKeyMessage().getString());
            color = LOW;
        }
        float cx = w / 2f, y = h / 2f + 22;
        if (text != null) Draw.text(g,text, cx - UiFont.width(text) / 2f, y, 1f, ColorMath.fade(color, 0.9f), true);
        float a = hintAlpha.get();
        if (a > 0.02f && !hint.isEmpty()) {
            Draw.text(g,hint, cx - UiFont.width(hint) / 2f, y + 12, 1f, ColorMath.fade(EMPTY, a), true);
        }
    }
}
