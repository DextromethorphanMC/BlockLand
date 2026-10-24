package com.blockland.client.battleroyale;

import com.blockland.anim.ColorMath;
import com.blockland.battleroyale.BrPlane;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.BattleRoyalePackets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import static com.blockland.client.ui.Tr.tr;

/**
 * 大逃杀 HUD: the intro's letterbox and title, the jump prompt with the plane's route while aboard, then a minimap
 * (top right: the island, the zone now and where it is going, the player), the zone's clock, who is still alive and a
 * warning outside the zone.
 */
final class BrHud {
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int ZONE = 0xFF3A8CFF;
    private static final int TARGET = 0xFFFFFFFF;
    private static final int DANGER = 0xFFFF4D4D;
    private static final int MAP_SIZE = 84;

    private BrHud() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        BattleRoyalePackets.S2CState state = ClientBattleRoyale.state();
        if (player == null || mc.options.hideGui || !ClientBattleRoyale.inMatch()) return;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2;
        switch (state.phase()) {
            case INTRO -> intro(g, state, partial, w, h);
            case WAITING -> {
                String line = tr("blockland.br.hud.boarding");
                Draw.text(g, line, cx - UiFont.width(line) / 2f, h - 64, 1f, MUTED, true);
            }
            case FLIGHT, FIGHT -> {
                BrPlane plane = ClientBattleRoyale.plane();
                if (plane != null) flight(g, plane, partial, cx, h);
                if (!state.aboard() && !player.isSpectator()) outsideWarning(g, player, state, partial, cx, h);
                minimap(g, player, state, partial, w);
            }
            default -> {
            }
        }
    }

    /** Black bars, the game's name and the map, the time to take-off. */
    private static void intro(GuiGraphics g, BattleRoyalePackets.S2CState state, float partial, int w, int h) {
        float t = ClientBattleRoyale.introProgress(partial);
        float bars = Mth.clamp(Math.min(t, 1 - t) * 8, 0, 1);
        int bar = Math.round(h * 0.11f * bars);
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);
        float fade = Mth.clamp(Math.min((t - 0.08f) * 6, (0.92f - t) * 6), 0, 1);
        String title = tr("blockland.game.battle_royale");
        Draw.text(g, title, w / 2f - UiFont.width(title) * 3f / 2, h * 0.36f, 3f, ColorMath.fade(TEXT, fade), true);
        String map = state.mapName();
        Draw.text(g, map, w / 2f - UiFont.width(map) * 1.3f / 2, h * 0.36f + 34, 1.3f, ColorMath.fade(ACCENT, fade), true);
        float left = Math.max(0, state.phaseTicks() - ClientBattleRoyale.ticksSinceState(partial)) / 20f;
        String takeOff = tr("blockland.br.hud.take_off_in", (int) Math.ceil(left));
        Draw.text(g, takeOff, w / 2f - UiFont.width(takeOff) / 2f, h - bar - 16, 1f, ColorMath.fade(MUTED, fade), true);
    }

    /** Aboard: when to jump, and how much of the route is left. */
    private static void flight(GuiGraphics g, BrPlane plane, float partial, int cx, int h) {
        String key = Minecraft.getInstance().options.keyJump.getTranslatedKeyMessage().getString();
        String prompt = plane.departed() ? tr("blockland.br.hud.jump", key) : tr("blockland.br.hud.boarding");
        int y = h - 70;
        Draw.glow(g, cx, y + 8, 120, 22, 0x66000000);
        Draw.text(g, prompt, cx - UiFont.width(prompt) * 1.2f / 2, y, 1.2f, TEXT, true);
        int half = 70;
        float progress = plane.progress(partial);
        g.fill(cx - half, y + 15, cx + half, y + 17, 0x40FFFFFF);
        g.fill(cx - half, y + 15, cx - half + Math.round(2 * half * progress), y + 17, ACCENT);
        String left = tr("blockland.br.hud.route_left", (int) Math.ceil(plane.ticksLeft() / 20f));
        Draw.text(g, left, cx - UiFont.width(left) * 0.8f / 2, y + 20, 0.8f, MUTED, true);
    }

    private static void outsideWarning(GuiGraphics g, LocalPlayer player, BattleRoyalePackets.S2CState state, float partial, int cx, int h) {
        if (!state.zone()) return;
        BattleRoyalePackets.Circle zone = ClientBattleRoyale.zone(partial);
        if (Math.hypot(player.getX() - zone.x(), player.getZ() - zone.z()) <= zone.radius()) return;
        float pulse = 0.6f + 0.4f * Mth.sin((float) (System.nanoTime() / 1e9 * 6));
        String warning = tr("blockland.br.hud.outside");
        Draw.text(g, warning, cx - UiFont.width(warning) * 1.3f / 2, h / 2f + 40, 1.3f, ColorMath.fade(DANGER, pulse), true);
    }

    /** Top right: the island's box, both circles, the player and which way they look; the clocks below. */
    private static void minimap(GuiGraphics g, LocalPlayer player, BattleRoyalePackets.S2CState state, float partial, int w) {
        int x0 = w - MAP_SIZE - 10, y0 = 10;
        double spanX = Math.max(1, state.maxX() - state.minX()), spanZ = Math.max(1, state.maxZ() - state.minZ());
        double span = Math.max(spanX, spanZ) * 1.25;
        double ox = (state.minX() + state.maxX()) / 2 - span / 2, oz = (state.minZ() + state.maxZ()) / 2 - span / 2;
        float k = (float) (MAP_SIZE / span);
        g.fill(x0 - 1, y0 - 1, x0 + MAP_SIZE + 1, y0 + MAP_SIZE + 1, 0x80000000);
        // the island's box
        int ix0 = x0 + Math.round((float) (state.minX() - ox) * k), iz0 = y0 + Math.round((float) (state.minZ() - oz) * k);
        int ix1 = x0 + Math.round((float) (state.maxX() - ox) * k), iz1 = y0 + Math.round((float) (state.maxZ() - oz) * k);
        g.fill(ix0, iz0, ix1, iz1, 0x3058A060);
        Draw.outline(g, ix0, iz0, ix1 - ix0, iz1 - iz0, 0x6058A060);
        if (state.zone()) {
            circle(g, state.zoneTo(), ox, oz, k, x0, y0, TARGET);
            circle(g, ClientBattleRoyale.zone(partial), ox, oz, k, x0, y0, ZONE);
        }
        // the player: a dot and a short line for the view
        float px = x0 + (float) (player.getX() - ox) * k, pz = y0 + (float) (player.getZ() - oz) * k;
        px = Mth.clamp(px, x0, x0 + MAP_SIZE);
        pz = Mth.clamp(pz, y0, y0 + MAP_SIZE);
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        for (int i = 1; i <= 5; i++) {
            g.fill(Math.round(px - Mth.sin(yaw) * i), Math.round(pz + Mth.cos(yaw) * i), Math.round(px - Mth.sin(yaw) * i) + 1,
                    Math.round(pz + Mth.cos(yaw) * i) + 1, 0xC0FFFFFF);
        }
        g.fill(Math.round(px) - 1, Math.round(pz) - 1, Math.round(px) + 2, Math.round(pz) + 2, 0xFFFFD84A);

        int y = y0 + MAP_SIZE + 4;
        String alive = tr("blockland.br.hud.alive_of", state.alive(), state.total());
        Draw.text(g, alive, x0 + MAP_SIZE - UiFont.width(alive), y, 1f, TEXT, true);
        String kills = tr("blockland.br.hud.kills", state.kills());
        Draw.text(g, kills, x0 + MAP_SIZE - UiFont.width(kills) * 0.9f, y + 11, 0.9f, MUTED, true);
        if (!state.zone()) return;
        float since = ClientBattleRoyale.ticksSinceState(partial);
        float start = (state.shrinkStartIn() - since) / 20f, end = (state.shrinkEndIn() - since) / 20f;
        String clock = start > 0 ? tr("blockland.br.hud.zone_in", time(start))
                : end > 0 ? tr("blockland.br.hud.zone_shrinking", time(end)) : tr("blockland.br.hud.zone_final");
        Draw.text(g, clock, x0 + MAP_SIZE - UiFont.width(clock) * 0.9f, y + 22, 0.9f, start > 0 ? MUTED : ZONE, true);
    }

    private static void circle(GuiGraphics g, BattleRoyalePackets.Circle c, double ox, double oz, float k, int x0, int y0, int color) {
        float cx = x0 + (float) (c.x() - ox) * k, cz = y0 + (float) (c.z() - oz) * k, r = (float) c.radius() * k;
        int steps = Math.max(24, Math.round(r * 3));
        for (int i = 0; i < steps; i++) {
            double a = i * Math.PI * 2 / steps;
            int px = Math.round(cx + (float) Math.cos(a) * r), pz = Math.round(cz + (float) Math.sin(a) * r);
            if (px < x0 || pz < y0 || px >= x0 + MAP_SIZE || pz >= y0 + MAP_SIZE) continue;
            g.fill(px, pz, px + 1, pz + 1, color);
        }
    }

    private static String time(float seconds) {
        int s = (int) Math.ceil(Math.max(0, seconds));
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
