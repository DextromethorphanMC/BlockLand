package com.blockland.client.bomb;

import com.blockland.anim.ColorMath;
import com.blockland.bomb.PlantedBomb;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.BombPackets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import static com.blockland.client.ui.Tr.tr;

/**
 * 枪战爆破 HUD: at the top the side, the clock (time to plant, then the bomb's fuse, red and pulsing) and a compass strip
 * with the sites, the dropped bomb (attackers only) and the planted bomb; under the crosshair the plant / defuse
 * progress and what the player can do where they stand. Everything comes from the server's state, counted on locally
 * between its packets.
 */
final class BombHud {
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;
    private static final int ATTACK = 0xFFFF6B4A;
    private static final int DEFEND = 0xFF5AA9FF;
    private static final int SITE = 0xFFFFC24A;
    private static final int BOMB = 0xFFFF3B30;
    private static final int OK = 0xFF6EE07A;
    /** Degrees either side of the view the compass shows. */
    private static final float COMPASS_SPAN = 70;
    private static final int COMPASS_HALF = 110;

    private BombHud() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        BombPackets.S2CState state = ClientBomb.state();
        if (player == null || mc.options.hideGui || state.side() == BombPackets.Side.NONE) return;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float since = ClientBomb.ticksSinceState(partial);
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2;
        boolean attacker = state.side() == BombPackets.Side.ATTACKER;
        PlantedBomb bomb = ClientBomb.plantedBomb();

        // ---- top: side, phase, clock ----
        int top = 6;
        String side = tr(attacker ? "blockland.bomb.hud.side.attackers" : "blockland.bomb.hud.side.defenders");
        Draw.glow(g, cx, top + 14, 150, 26, 0x55000000);
        String clock = "";
        int clockColor = TEXT;
        float clockScale = 1.6f;
        String line;
        int lineColor = MUTED;
        switch (state.phase()) {
            case WAITING -> line = tr("blockland.bomb.hud.waiting");
            case LIVE -> {
                line = attacker ? tr(state.carrier() ? "blockland.bomb.hud.you_carry" : state.dropped() ? "blockland.bomb.hud.dropped"
                        : state.carrierName().isEmpty() ? "blockland.bomb.hud.plant_goal" : "blockland.bomb.hud.carrier", state.carrierName())
                        : tr("blockland.bomb.hud.defend_goal");
                if (attacker && (state.carrier() || state.dropped())) lineColor = SITE;
                if (state.roundTicks() >= 0) {
                    float left = Math.max(0, state.roundTicks() - since) / 20f;
                    clock = time(left);
                    clockColor = left <= 10 ? SITE : TEXT;
                }
            }
            case PLANTED -> {
                line = tr("blockland.bomb.hud.planted_at", state.plantedSite());
                lineColor = BOMB;
                float left = bomb != null ? bomb.secondsLeft(partial) : Math.max(0, state.bombTicks() - since) / 20f;
                clock = time(left);
                // the red clock pulses with the beep, faster when it is nearly over
                float rate = left < 10 ? 4 : 1.5f;
                float pulse = 0.5f + 0.5f * Mth.cos((float) (System.nanoTime() / 1e9 * Math.PI * 2 * rate));
                clockColor = ColorMath.mix(BOMB, 0xFFFFD0C8, pulse * (left < 10 ? 0.8f : 0.35f));
                clockScale = left < 10 ? 2.0f : 1.8f;
            }
            case DEFUSED -> {
                line = tr("blockland.bomb.hud.defused");
                lineColor = DEFEND;
            }
            case EXPLODED -> {
                line = tr("blockland.bomb.hud.exploded");
                lineColor = ATTACK;
            }
            default -> line = "";
        }
        Draw.text(g, side, cx - UiFont.width(side) / 2f, top, 1f, attacker ? ATTACK : DEFEND, true);
        int y = top + 11;
        if (!clock.isEmpty()) {
            Draw.text(g, clock, cx - UiFont.width(clock) * clockScale / 2f, y, clockScale, clockColor, true);
            y += Math.round(UiFont.LINE_HEIGHT * clockScale) + 1;
        }
        if (!line.isEmpty()) {
            Draw.text(g, line, cx - UiFont.width(line) / 2f, y, 1f, lineColor, true);
            y += UiFont.LINE_HEIGHT + 3;
        }
        if (state.phase() == BombPackets.Phase.LIVE || state.phase() == BombPackets.Phase.PLANTED) compass(g, player, state, bomb, cx, y + 2);

        // ---- under the crosshair: progress, then what can be done here ----
        int hy = h / 2 + 22;
        if (state.action() != BombPackets.Action.NONE) {
            float done = Math.min(state.actionTicks(), state.actionDone() + since) / (float) state.actionTicks();
            boolean planting = state.action() == BombPackets.Action.PLANTING;
            String label = tr(planting ? "blockland.bomb.hud.planting" : "blockland.bomb.hud.defusing");
            progress(g, cx, hy, label, done, planting ? ATTACK : DEFEND);
        } else if (bomb != null && state.phase() == BombPackets.Phase.PLANTED && bomb.isDefusing()) {
            // someone else is at it (for everyone around: the bomb's own synced progress)
            if (!attacker) progress(g, cx, hy, tr("blockland.bomb.hud.teammate_defusing"), bomb.defuseProgress(partial), DEFEND);
        } else if (state.phase() == BombPackets.Phase.LIVE && state.carrier() && ClientBomb.holdingBomb()) {
            BombPackets.Site site = ClientBomb.siteHere();
            String key = Minecraft.getInstance().options.keyAttack.getTranslatedKeyMessage().getString();
            if (site != null && player.onGround()) hint(g, cx, hy, tr("blockland.bomb.hud.hold_to_plant", key, site.name()), OK);
            else hint(g, cx, hy, tr("blockland.bomb.hud.go_to_site"), MUTED);
        } else if (state.phase() == BombPackets.Phase.PLANTED && !attacker && bomb != null && ClientBomb.atBomb(bomb)) {
            hint(g, cx, hy, tr("blockland.bomb.hud.hold_to_defuse", ClientBomb.DEFUSE.getTranslatedKeyMessage().getString()), OK);
        }
    }

    /** The strip at the top: where the sites and the bomb are, relative to where the player looks. */
    private static void compass(GuiGraphics g, LocalPlayer player, BombPackets.S2CState state, PlantedBomb bomb, int cx, int y) {
        g.fill(cx - COMPASS_HALF, y + 6, cx + COMPASS_HALF, y + 7, 0x40FFFFFF);
        g.fill(cx, y + 3, cx + 1, y + 10, 0x80FFFFFF);
        BombPackets.Site here = ClientBomb.siteHere();
        for (BombPackets.Site site : state.sites()) {
            boolean planted = site.name().equals(state.plantedSite());
            marker(g, player, cx, y, site.centerX(), site.centerZ(), site.name(), planted ? BOMB : SITE, site == here);
        }
        if (state.dropped()) marker(g, player, cx, y, state.dropX(), state.dropZ(), "◆", ATTACK, false);
        if (bomb != null) marker(g, player, cx, y, bomb.getX(), bomb.getZ(), "●", BOMB, false);
    }

    private static void marker(GuiGraphics g, LocalPlayer player, int cx, int y, double x, double z, String label, int color, boolean here) {
        double dx = x - player.getX(), dz = z - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = Mth.wrapDegrees(yaw - player.getYRot());
        boolean clamped = Math.abs(rel) > COMPASS_SPAN;
        float t = Mth.clamp(rel / COMPASS_SPAN, -1, 1);
        float mx = cx + t * COMPASS_HALF;
        float scale = here ? 1.3f : 1f;
        int c = clamped ? ColorMath.fade(color, 0.55f) : color;
        Draw.text(g, label, mx - UiFont.width(label) * scale / 2f, y - 1, scale, c, true);
        if (!clamped) {
            String distance = tr("blockland.bomb.hud.meters", Math.round(Math.sqrt(dx * dx + dz * dz)));
            Draw.text(g, distance, mx - UiFont.width(distance) * 0.7f / 2f, y + 10, 0.7f, MUTED, true);
        }
    }

    private static void progress(GuiGraphics g, int cx, int y, String label, float done, int color) {
        int half = 64;
        Draw.glow(g, cx, y + 8, 90, 18, 0x55000000);
        Draw.text(g, label, cx - UiFont.width(label) / 2f, y, 1f, TEXT, true);
        g.fill(cx - half, y + 12, cx + half, y + 15, 0x50FFFFFF);
        g.fill(cx - half, y + 12, cx - half + Math.round(2 * half * Mth.clamp(done, 0, 1)), y + 15, color);
    }

    private static void hint(GuiGraphics g, int cx, int y, String text, int color) {
        Draw.text(g, text, cx - UiFont.width(text) / 2f, y, 1f, color, true);
    }

    /** m:ss, and tenths in the last ten seconds. */
    private static String time(float seconds) {
        if (seconds < 10) return String.format("%.1f", Math.max(0, seconds));
        int s = (int) Math.ceil(seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
