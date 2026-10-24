package com.blockland.client.spectate;

import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.profile.NameCards;
import com.blockland.client.rank.RankBadge;
import com.blockland.client.ui.Draw;
import com.blockland.common.RankTier;
import com.blockland.network.packet.SpectatePackets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import static com.blockland.client.ui.Tr.tr;

/**
 * The spectator HUD: at the top who killed you and what you watch; at the bottom the watched player's name card with
 * their tier, score, place on the ladder, playtime and health; the countdown to the respawn (or the return to the
 * lobby) and what the keys do. Everything shown comes from the server's spectate state.
 */
final class SpectateHud {
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;
    private static final int KILLER = 0xFFFF6B5A;
    private static final int RESPAWN = 0xFF6EE07A;
    private static final int LEAVE = 0xFFFFC24A;
    private static final int CARD_W = 220;

    private SpectateHud() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || !ClientSpectate.active()) return;
        SpectatePackets.S2CState state = ClientSpectate.state();
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2;

        // ---- top: what this is, who killed you
        int y = 8;
        String title = tr(state.mode() == SpectatePackets.Mode.FREE ? "blockland.spectate.free" : "blockland.spectate.watching");
        Draw.glow(g, cx, y + 10, 120, 22, 0x55000000);
        Draw.text(g, title, cx - UiFont.width(title) / 2f, y, 1f, MUTED, true);
        y += UiFont.LINE_HEIGHT + 2;
        if (!state.killer().isEmpty()) {
            String killed = tr("blockland.spectate.killed_by", state.killer());
            Draw.text(g, killed, cx - UiFont.width(killed) * 1.1f / 2f, y, 1.1f, KILLER, true);
            y += UiFont.LINE_HEIGHT + 3;
        }

        // ---- the countdown
        float left = ClientSpectate.ticksLeft(partial);
        if (left >= 0) {
            int seconds = (int) Math.ceil(left / 20f);
            String line = tr(state.respawn() ? "blockland.spectate.respawn_in" : "blockland.spectate.lobby_in", seconds);
            int color = state.respawn() ? RESPAWN : LEAVE;
            Draw.text(g, line, cx - UiFont.width(line) / 2f, y + 2, 1f, color, true);
            int half = 70, by = y + 13;
            g.fill(cx - half, by, cx + half, by + 2, 0x40FFFFFF);
            float done = 1 - Mth.clamp(left / Math.max(1f, state.totalTicks()), 0, 1);
            g.fill(cx - half, by, cx - half + Math.round(2 * half * done), by + 2, ColorMath.fade(color, 0.95f));
        }

        // ---- bottom: the watched player
        int bottom = h - 34;
        if (state.mode() == SpectatePackets.Mode.TARGET) {
            int cardH = Math.round(CARD_W / NameCards.ASPECT);
            int cardX = cx - CARD_W / 2, cardY = bottom - cardH - 16;
            RankTier tier = state.tier().isEmpty() ? null : RankTier.of(state.tier());
            Draw.glow(g, cx, cardY + cardH / 2f + 6, CARD_W * 0.7f, cardH + 24, 0x66000000);
            NameCards.draw(g, state.cardId().isEmpty() ? null : state.cardId(), cardX, cardY, CARD_W, cardH, state.name(), tier,
                    (float) (System.nanoTime() / 1e9 % 3600));
            // health along the card's bottom edge
            float health = state.maxHealth() <= 0 ? 0 : Mth.clamp(state.health() / state.maxHealth(), 0, 1);
            g.fill(cardX, cardY + cardH + 1, cardX + CARD_W, cardY + cardH + 3, 0x50000000);
            g.fill(cardX, cardY + cardH + 1, cardX + Math.round(CARD_W * health), cardY + cardH + 3, ColorMath.mix(0xFFFF5A4A, 0xFF6EE07A, health));
            // tier, score, place, playtime in one line under it
            float lineY = cardY + cardH + 6;
            StringBuilder info = new StringBuilder();
            if (state.position() > 0) info.append(tr("blockland.spectate.score", state.score(), state.position())).append("  ·  ");
            info.append(tr("blockland.spectate.playtime", playtime(state.playtime())));
            String infoText = info.toString();
            float badge = tier == null ? 0 : RankBadge.width(tier, 1) + 6;
            float total = badge + UiFont.width(infoText);
            float x = cx - total / 2f;
            if (tier != null) RankBadge.draw(g, tier, x, lineY - 2, 1, 1);
            Draw.text(g, infoText, x + badge, lineY, 1f, TEXT, true);
        }

        // ---- the keys
        String hint = state.mode() == SpectatePackets.Mode.FREE
                ? tr("blockland.spectate.hint.free", key(mc.options.keyAttack), key(mc.options.keyUse))
                : state.canFree()
                ? tr("blockland.spectate.hint.target_free", key(mc.options.keyAttack), key(mc.options.keyUse), key(mc.options.keySwapOffhand),
                key(mc.options.keyJump))
                : tr("blockland.spectate.hint.target", key(mc.options.keyAttack), key(mc.options.keyUse), key(mc.options.keySwapOffhand));
        if (state.mode() == SpectatePackets.Mode.TARGET && ClientSpectate.thirdPerson()) hint += "  " + tr("blockland.spectate.hint.zoom");
        Draw.text(g, hint, cx - UiFont.width(hint) * 0.85f / 2f, h - 14, 0.85f, MUTED, true);
        if (state.targets() == 0 && state.mode() == SpectatePackets.Mode.FREE) {
            String none = tr("blockland.spectate.nobody");
            Draw.text(g, none, cx - UiFont.width(none) / 2f, h - 28, 1f, MUTED, true);
        }
    }

    private static String key(KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    /** 12 小时 30 分 / 45 分钟. */
    private static String playtime(long seconds) {
        long minutes = seconds / 60;
        if (minutes < 60) return tr("blockland.spectate.minutes", minutes);
        return tr("blockland.spectate.hours", minutes / 60, minutes % 60);
    }
}
