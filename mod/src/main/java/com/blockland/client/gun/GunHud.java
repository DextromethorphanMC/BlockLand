package com.blockland.client.gun;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.font.UiFont;
import com.blockland.client.hud.Crosshair;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.ui.Draw;
import com.blockland.gun.GunStats;
import com.blockland.network.packet.GunPackets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;

import static com.blockland.client.ui.Tr.tr;

/**
 * Gun HUD in the style of modern shooters: ammo counter with magazine pips (a {@link Panel} of the movable HUD, bottom
 * right by default; a dual pair shows each gun's rounds), a dynamic crosshair that opens with the real spread, the
 * shared {@link HitMarker} and short hints. Every motion uses the {@code anim} library.
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

    /** @param killRgb the kill marks' colour of a special skin (asset spec §38), -1 = the player's own */
    static void hit(GunPackets.HitKind kind, int killRgb) {
        HitMarker.show(kind == GunPackets.HitKind.HEAD || kind == GunPackets.HitKind.HEAD_KILL,
                kind == GunPackets.HitKind.KILL || kind == GunPackets.HitKind.HEAD_KILL, killRgb);
    }

    static void flashHint(String text) {
        hint = text;
        hintAlpha.snap(1);
        hintAlpha.to(0, 0.6, Ease.QUAD_IN).delay(1.2);
    }

    /** The crosshair, the hit marker and the hints, round the screen's middle (the ammo is the {@link Panel}'s). */
    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        // spectating through someone's eyes: only their rounds, on the panel
        if (WatchedGun.stats() != null) return;
        GunStats stats = ClientGuns.stats();
        if (mc.player == null || stats == null || !ClientGuns.holdingGun() || mc.options.hideGui) return;
        int w = g.guiWidth(), h = g.guiHeight();
        crosshair(g, stats, w, h, mc);
        HitMarker.render(g, w, h);
        hints(g, stats, w, h);
    }

    /**
     * The ammo counter as an element of the movable HUD (UI spec §3): its box's right edge is the counter's, the round
     * count and pips sit at its bottom; the gun's name and configuration above. In the editor without a gun: a sample.
     */
    static final class Panel implements HudElement {
        private static final int W = 200, H = 56;

        @Override
        public String id() {
            return "gun_ammo";
        }

        @Override
        public String nameKey() {
            return "blockland.hud.element.gun_ammo";
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
            return HudAnchor.BOTTOM_RIGHT;
        }

        @Override
        public int defaultX() {
            return -14;
        }

        @Override
        public int defaultY() {
            return -10;
        }

        @Override
        public boolean visible() {
            return Minecraft.getInstance().player != null
                    && (WatchedGun.stats() != null || ClientGuns.stats() != null && ClientGuns.holdingGun());
        }

        @Override
        public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
            // the pips' bottom edge; the reload line runs just under it
            int right = x + W, bottom = y + H - 4;
            GunStats watched = WatchedGun.stats();
            GunStats stats = ClientGuns.stats();
            if (watched != null) {
                watchedAmmo(g, watched, right, bottom);
            } else if (stats != null && ClientGuns.holdingGun()) {
                ammo(g, stats, right, bottom);
            } else if (editing) {
                counter(g, right, bottom, 24, 30, 90, null, tr("blockland.hud.sample.gun"), tr("blockland.gun.fire_mode.auto"), -1);
            }
        }
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

    /**
     * Spectating through someone's eyes: their gun and the rounds in it (its synced state); what they carry in reserve
     * is theirs alone and not shown.
     */
    private static void watchedAmmo(GuiGraphics g, GunStats stats, int right, int bottom) {
        int ammo = WatchedGun.ammo(), capacity = stats.capacity();
        int ammoColor = ammo == 0 ? EMPTY : ammo <= Math.max(1, capacity / 4) ? LOW : TEXT;
        Draw.glow(g, right - 30, bottom - 22, 70, 36, 0x66000000);
        String count = String.valueOf(ammo);
        float scale = 2.2f;
        Draw.text(g, count, right - UiFont.width(count) * scale, bottom - 9 - UiFont.LINE_HEIGHT * scale, scale, ammoColor, true);
        String name = stats.gun().displayName().getString();
        Draw.text(g, name, right - UiFont.width(name), bottom - 50, 1f, TEXT, true);
    }

    private static void ammo(GuiGraphics g, GunStats stats, int right, int bottom) {
        int ammo = ClientGuns.ammo();
        int capacity = stats.capacity();
        if (ammo < lastAmmo) ammoPulse.impulse(9);
        lastAmmo = ammo;
        boolean dual = ClientGuns.isDual();
        String name = stats.gun().displayName().getString();
        // a dual pair has no sight: its use key fires a volley
        String sightText = dual ? tr("blockland.gun.hud.dual") : stats.gun().describe("optic", stats.sight().id).name().getString();
        String detail = tr("blockland.gun.fire_mode." + ClientGuns.fireMode().name().toLowerCase(java.util.Locale.ROOT))
                + " · " + sightText
                + (stats.laser().hasBeam() ? " · " + stats.gun().describe("laser", stats.laser().id).name().getString() : "");
        counter(g, right, bottom, ammo, capacity, ClientGuns.reserve(), dual ? ClientGuns.dualRounds() : null, name, detail,
                ClientGuns.isReloading() ? ClientGuns.reloadProgress() : -1);
    }

    /**
     * The counter drawn from the panel's right edge and the pips' bottom: pips, the round count and reserve, the name and
     * the detail line, and a reload line when {@code reload} is 0 … 1.
     *
     * @param split a dual pair's rounds per gun, or null
     */
    private static void counter(GuiGraphics g, int right, int bottom, int ammo, int capacity, int reserve, @Nullable int[] split,
                                String name, String detail, float reload) {
        boolean low = ammo <= Math.max(1, capacity / 4);
        int ammoColor = ammo == 0 ? EMPTY : low ? LOW : TEXT;
        Draw.glow(g, right - 30, bottom - 22, 70, 36, 0x66000000);
        // magazine pips, one per round (compressed for big magazines); a dual pair: the left gun's, a gap, the right's
        boolean dual = split != null;
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
        Draw.text(g,name, right - UiFont.width(name), bottom - 50, 1f, TEXT, true);
        Draw.text(g,detail, right - UiFont.width(detail), bottom - 38, 1f, MUTED, true);
        if (reload >= 0) {
            g.fill(pipX, pipY + 6, right, pipY + 7, 0x30FFFFFF);
            g.fill(pipX, pipY + 6, pipX + Math.round((right - pipX) * reload), pipY + 7, 0xFFFFFFFF);
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
