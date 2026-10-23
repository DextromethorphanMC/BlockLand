package com.blockland.client.result;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.client.ui.Tr;
import com.blockland.network.packet.S2CMatchResult;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.ui.Tr.tr;

/**
 * Draws the results card: a compact panel at the top of the screen (never covering the whole view) with the
 * player's outcome, why the match ended, a short statistics table and — when interactive — the two buttons
 * 「再来一局」 and 「返回大厅 (N)」. Pure drawing; {@link MatchResultScreen} handles the clicks.
 */
final class MatchResultCard {
    private static final int WIDTH = 300;
    private static final int TOP = 34;
    private static final int MAX_ROWS = 8;
    private static final int ROW_H = 11;
    private static final int STAT_W = 46;
    private static final int BUTTON_H = 18;
    static final float ENTER_SECONDS = 0.35f;
    static final float EXIT_SECONDS = 0.3f;

    static final int BG = 0xE6101318;
    static final int TEXT = 0xFFF2F4F7;
    static final int MUTED = 0xFF9AA4B5;
    static final int VICTORY = 0xFFFFC24A;
    static final int DEFEAT = 0xFFFF5A4E;
    static final int DRAW = 0xFFB8C2D3;

    /** Where the buttons were drawn (GUI coordinates); zero-sized when the card is not interactive. */
    record Layout(int x, int y, int w, int h, int playX, int lobbyX, int buttonY, int buttonW) {
        boolean overPlay(double mx, double my) {
            return buttonW > 0 && mx >= playX && mx < playX + buttonW && my >= buttonY && my < buttonY + BUTTON_H;
        }

        boolean overLobby(double mx, double my) {
            return buttonW > 0 && mx >= lobbyX && mx < lobbyX + buttonW && my >= buttonY && my < buttonY + BUTTON_H;
        }
    }

    private MatchResultCard() {
    }

    static int accent(S2CMatchResult r) {
        return r.outcome() == S2CMatchResult.VICTORY ? VICTORY : r.outcome() == S2CMatchResult.DEFEAT ? DEFEAT : DRAW;
    }

    /**
     * @param age  seconds since the result arrived
     * @param left seconds until the server sends the player to the lobby
     */
    static Layout render(GuiGraphics g, S2CMatchResult r, float age, float left, boolean interactive, double mouseX, double mouseY) {
        float enter = (float) Ease.BACK_OUT.apply(Mth.clamp(age / ENTER_SECONDS, 0, 1));
        float alpha = Mth.clamp(age / (ENTER_SECONDS * 0.6f), 0, 1) * Mth.clamp(left / EXIT_SECONDS, 0, 1);
        int gw = g.guiWidth();
        int w = Math.min(WIDTH, gw - 24);
        List<S2CMatchResult.Row> rows = visibleRows(r.rows());
        int footer = interactive ? BUTTON_H + 10 : 12;
        int h = 58 + 12 + rows.size() * ROW_H + footer;
        int x = (gw - w) / 2;
        int y = Math.round(TOP - (1 - enter) * 18);
        int accent = accent(r);
        if (alpha <= 0.01f) return new Layout(x, y, w, h, 0, 0, 0, 0);

        // card
        Draw.glow(g, x + w / 2f, y + 24, w * 0.6f, 40, ColorMath.fade(accent, 0.18f * alpha));
        Draw.rect(g, x, y, w, h, ColorMath.fade(BG, alpha));
        Draw.outline(g, x, y, w, h, ColorMath.fade(0x30FFFFFF, alpha));
        Draw.rect(g, x, y, w, 2, ColorMath.fade(accent, alpha));

        // outcome and match line
        String outcome = tr(r.outcome() == S2CMatchResult.VICTORY ? "blockland.result.victory"
                : r.outcome() == S2CMatchResult.DEFEAT ? "blockland.result.defeat" : "blockland.result.draw");
        float pop = 1 + (1 - enter) * 0.25f;
        Draw.text(g, outcome, x + 12, y + 10, 2.2f * pop, ColorMath.fade(accent, alpha), true);
        String title = Draw.fit(r.modeTitle(), w / 2);
        Draw.text(g, title, x + w - 10 - UiFont.width(title), y + 10, 1f, ColorMath.fade(TEXT, alpha), true);
        String meta = Tr.text(r.gameName()) + " · " + tr("blockland.result.duration", time(r.durationSeconds()));
        Draw.text(g, meta, x + w - 10 - UiFont.width(meta), y + 22, 1f, ColorMath.fade(MUTED, alpha), true);
        String headline = Tr.text(r.headline()) + (r.reason().isEmpty() ? "" : " · " + Tr.text(r.reason()));
        Draw.text(g, Draw.fit(headline, w - 24), x + 12, y + 38, 1f, ColorMath.fade(ColorMath.lighten(accent, 0.12f), alpha), true);

        // table
        int tableY = y + 54;
        Draw.rect(g, x + 8, tableY - 2, w - 16, 1, ColorMath.fade(0x22FFFFFF, alpha));
        Draw.text(g, tr("blockland.result.column.player"), x + 12, tableY + 2, 1f, ColorMath.fade(MUTED, alpha), false);
        List<String> columns = r.columns().stream().map(Tr::text).toList();
        for (int c = 0; c < columns.size(); c++) {
            int cx = statX(x, w, columns.size(), c);
            Draw.text(g, columns.get(c), cx - UiFont.width(columns.get(c)) / 2f, tableY + 2, 1f, ColorMath.fade(MUTED, alpha), false);
        }
        int rowY = tableY + 13;
        for (int i = 0; i < rows.size(); i++) {
            S2CMatchResult.Row row = rows.get(i);
            // rows drop in one after another
            float rowAlpha = alpha * Mth.clamp((age - 0.12f - i * 0.04f) / 0.18f, 0, 1);
            if (rowAlpha > 0.01f) drawRow(g, row, columns.size(), x, w, rowY, rowAlpha);
            rowY += ROW_H;
        }

        // footer: time left, and the buttons
        int footerY = y + h - footer;
        if (!interactive) {
            float progress = Mth.clamp(left / Math.max(1, r.showSeconds()), 0, 1);
            Draw.rect(g, x + 8, y + h - 5, w - 16, 1, ColorMath.fade(0x22FFFFFF, alpha));
            Draw.rect(g, x + 8, y + h - 5, Math.round((w - 16) * progress), 1, ColorMath.fade(accent, alpha * 0.9f));
            return new Layout(x, y, w, h, 0, 0, 0, 0);
        }
        int buttonW = (w - 24 - 8) / 2, buttonY = footerY + 4;
        int playX = x + 12, lobbyX = playX + buttonW + 8;
        Layout layout = new Layout(x, y, w, h, playX, lobbyX, buttonY, buttonW);
        boolean overPlay = layout.overPlay(mouseX, mouseY), overLobby = layout.overLobby(mouseX, mouseY);
        button(g, tr("blockland.result.play_again"), playX, buttonY, buttonW, overPlay, accent, true, alpha);
        String lobby = tr("blockland.result.back_to_lobby", Math.max(0, (int) Math.ceil(left)));
        button(g, lobby, lobbyX, buttonY, buttonW, overLobby, accent, false, alpha);
        // the countdown drains along the lobby button
        float progress = Mth.clamp(left / Math.max(1, r.showSeconds()), 0, 1);
        Draw.rect(g, lobbyX + 2, buttonY + BUTTON_H - 2, Math.round((buttonW - 4) * progress), 1, ColorMath.fade(accent, alpha * 0.8f));
        return layout;
    }

    private static void drawRow(GuiGraphics g, S2CMatchResult.Row row, int columns, int x, int w, int rowY, float a) {
        if (row.self()) Draw.rect(g, x + 8, rowY - 1, w - 16, ROW_H, ColorMath.fade(0x26FFFFFF, a));
        String team = Tr.text(row.team());
        String tag = team.isEmpty() ? "" : "[" + team + "] ";
        int nameX = x + 12;
        if (!tag.isEmpty()) {
            Draw.text(g, tag, nameX, rowY + 1, 1f, ColorMath.fade(row.teamColor(), a), true);
            nameX += UiFont.width(tag);
        }
        int nameMax = statX(x, w, columns, 0) - STAT_W / 2 - nameX - 12;
        String name = Draw.fit(row.name(), Math.max(20, nameMax));
        Draw.text(g, name, nameX, rowY + 1, 1f, ColorMath.fade(row.winner() ? TEXT : MUTED, a), true);
        if (row.mvp()) Draw.text(g, "★ MVP", nameX + UiFont.width(name) + 4, rowY + 1, 1f, ColorMath.fade(VICTORY, a), true);
        for (int c = 0; c < columns && c < row.values().size(); c++) {
            String value = Tr.text(row.values().get(c));
            int cx = statX(x, w, columns, c);
            Draw.text(g, value, cx - UiFont.width(value) / 2f, rowY + 1, 1f, ColorMath.fade(row.self() ? TEXT : 0xFFD5DAE3, a), true);
        }
    }

    private static void button(GuiGraphics g, String label, int x, int y, int w, boolean hover, int accent, boolean primary, float alpha) {
        int fill = primary ? ColorMath.fade(accent, hover ? 0.42f : 0.26f) : (hover ? 0x40FFFFFF : 0x22FFFFFF);
        Draw.rect(g, x, y, w, BUTTON_H, ColorMath.fade(fill, alpha));
        Draw.outline(g, x, y, w, BUTTON_H, ColorMath.fade(primary ? accent : 0x50FFFFFF, alpha * (hover ? 1f : 0.7f)));
        Draw.text(g, label, x + w / 2f - UiFont.width(label) / 2f, y + 5, 1f, ColorMath.fade(TEXT, alpha), true);
    }

    /** Centre of statistic column {@code c}, counted from the right edge. */
    private static int statX(int x, int w, int count, int c) {
        return x + w - 12 - STAT_W / 2 - (count - 1 - c) * STAT_W;
    }

    /** The top rows, plus the player's own row when it would not be among them. */
    private static List<S2CMatchResult.Row> visibleRows(List<S2CMatchResult.Row> rows) {
        if (rows.size() <= MAX_ROWS) return rows;
        List<S2CMatchResult.Row> shown = new ArrayList<>(rows.subList(0, MAX_ROWS));
        if (shown.stream().noneMatch(S2CMatchResult.Row::self)) {
            rows.stream().filter(S2CMatchResult.Row::self).findFirst().ifPresent(self -> shown.set(MAX_ROWS - 1, self));
        }
        return shown;
    }

    private static String time(int seconds) {
        int s = Math.max(0, seconds);
        return String.format("%02d:%02d", s / 60, s % 60);
    }
}
