package com.blockland.client.combat;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.CombatPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

import static com.blockland.client.ui.Tr.tr;

/**
 * Our Tab board (the vanilla player list is switched off): while the key is held, the game, mode and map, how the match
 * stands, and every side with its players — state, name, kills, deaths, the game's own columns and ping. The server
 * sends it twice a second while the key is down; it fades in and out.
 */
final class MatchBoardHud {
    private static final int ROW = 12, COLUMN = 36, PING = 44, PAD = 8;

    @Nullable
    private static CombatPackets.S2CBoard board;
    private static final AnimValue shown = new AnimValue(0);
    private static boolean open;

    private MatchBoardHud() {
    }

    static void update(CombatPackets.S2CBoard received) {
        board = received;
    }

    static void setOpen(boolean value) {
        if (open == value) return;
        open = value;
        shown.to(value ? 1 : 0, value ? 0.12 : 0.18, value ? Ease.CUBIC_OUT : Ease.QUAD_IN);
    }

    static void clear() {
        board = null;
        open = false;
        shown.snap(0);
    }

    static void render(GuiGraphics g, int w, int h) {
        CombatPackets.S2CBoard b = board;
        float a = shown.get();
        if (b == null || a < 0.02f) return;
        Minecraft mc = Minecraft.getInstance();
        UUID self = mc.player == null ? null : mc.player.getUUID();

        int extra = b.columns().size();
        int width = Math.min(w - 24, 300 + extra * COLUMN + COLUMN * 2 + PING);
        int rows = 0;
        for (CombatPackets.S2CBoard.Team team : b.teams()) rows += team.rows().size();
        int teamHeaders = b.teams().size() * (ROW + 6);
        int headerHeight = 34;
        int height = headerHeight + ROW + teamHeaders + rows * ROW + PAD;
        int x = (w - width) / 2;
        int y = Math.max(8, Math.min(28, (h - height) / 2)) - Math.round((1 - a) * 6);

        Draw.rect(g, x, y, width, height, ColorMath.fade(0xE0101318, a));
        Draw.outline(g, x, y, width, height, ColorMath.fade(0x40FFFFFF, a));
        g.fill(x + 1, y, x + width - 1, y + 2, ColorMath.fade(0xFFFFA235, a));

        // header: game, mode and map, status on the right
        UiFont.draw(g, b.title(), x + PAD, y + 7, ColorMath.fade(0xFFF2F4F7, a), true);
        UiFont.draw(g, b.subtitle(), x + PAD, y + 19, ColorMath.fade(0xFF9AA4B2, a), false);
        int statusWidth = UiFont.width(b.status());
        UiFont.draw(g, b.status(), x + width - PAD - statusWidth, y + 7, ColorMath.fade(0xFFFFE082, a), true);

        // column titles
        int cy = y + headerHeight;
        int right = x + width - PAD;
        int nameX = x + PAD + 12;
        int labels = ColorMath.fade(0xFF7D8794, a);
        UiFont.draw(g, tr("blockland.board.column.player"), nameX, cy, labels, false);
        int col = right - PING;
        centered(g, tr("blockland.board.column.ping"), col + PING / 2f, cy, labels);
        for (int i = extra - 1; i >= 0; i--) {
            col -= COLUMN;
            centered(g, b.columns().get(i), col + COLUMN / 2f, cy, labels);
        }
        col -= COLUMN;
        centered(g, tr("blockland.board.column.deaths"), col + COLUMN / 2f, cy, labels);
        col -= COLUMN;
        centered(g, tr("blockland.board.column.kills"), col + COLUMN / 2f, cy, labels);
        int killsX = col;
        cy += ROW;

        for (CombatPackets.S2CBoard.Team team : b.teams()) {
            cy += 3;
            int color = ColorMath.fade(0xFF000000 | team.color(), a);
            g.fill(x + PAD, cy, x + PAD + 3, cy + ROW, color);
            g.fill(x + PAD + 3, cy, right, cy + ROW, ColorMath.fade(ColorMath.withAlpha(team.color(), 0x30), a));
            UiFont.draw(g, team.name(), x + PAD + 7, cy + 2, color, true);
            int infoWidth = UiFont.width(team.info());
            UiFont.draw(g, team.info(), right - 4 - infoWidth, cy + 2, ColorMath.fade(0xFFC9D0D9, a), false);
            cy += ROW + 3;
            int index = 0;
            for (CombatPackets.S2CBoard.Row row : team.rows()) {
                boolean me = row.player().equals(self);
                if (me) g.fill(x + PAD, cy, right, cy + ROW, ColorMath.fade(0x34FFFFFF, a));
                else if (index % 2 == 1) g.fill(x + PAD, cy, right, cy + ROW, ColorMath.fade(0x14FFFFFF, a));
                stateIcon(g, x + PAD + 5, cy + ROW / 2, row.state(), a);
                boolean down = row.state() != CombatPackets.State.ALIVE;
                int nameColor = ColorMath.fade(me ? 0xFFFFE082 : down ? 0xFF8A929C : 0xFFE8ECF2, a);
                String name = UiFont.fit(row.name(), killsX - nameX - 4);
                UiFont.draw(g, name, nameX, cy + 2, nameColor, true);
                int c = killsX;
                centered(g, String.valueOf(row.kills()), c + COLUMN / 2f, cy + 2, ColorMath.fade(0xFFF2F4F7, a));
                c += COLUMN;
                centered(g, String.valueOf(row.deaths()), c + COLUMN / 2f, cy + 2, ColorMath.fade(0xFFC9D0D9, a));
                c += COLUMN;
                for (int i = 0; i < extra; i++) {
                    Component value = i < row.values().size() ? row.values().get(i) : Component.empty();
                    centered(g, value, c + COLUMN / 2f, cy + 2, ColorMath.fade(0xFFC9D0D9, a));
                    c += COLUMN;
                }
                ping(g, c, cy, row.ping(), a);
                cy += ROW;
                index++;
            }
        }
    }

    private static void centered(GuiGraphics g, String text, float cx, float y, int color) {
        UiFont.draw(g, text, cx - UiFont.widthF(text) / 2, y, color, false);
    }

    private static void centered(GuiGraphics g, Component text, float cx, float y, int color) {
        UiFont.draw(g, text, cx - UiFont.width(text) / 2f, y, color, false);
    }

    /** Alive: a green dot; dead: a red cross; spectating: a grey ring. */
    private static void stateIcon(GuiGraphics g, int cx, int cy, CombatPackets.State state, float a) {
        switch (state) {
            case ALIVE -> g.fill(cx - 2, cy - 2, cx + 2, cy + 2, ColorMath.fade(0xFF5BD46A, a));
            case DEAD -> {
                int c = ColorMath.fade(0xFFFF5C5C, a);
                for (int i = -2; i <= 2; i++) {
                    g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, c);
                    g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, c);
                }
            }
            case SPECTATING -> Draw.outline(g, cx - 3, cy - 3, 6, 6, ColorMath.fade(0xFF8A929C, a));
        }
    }

    /** Four bars like vanilla's list, coloured by latency, and the milliseconds. */
    private static void ping(GuiGraphics g, int x, int y, int ms, float a) {
        if (ms < 0) {
            centered(g, "-", x + PING / 2f, y + 2, ColorMath.fade(0xFF7D8794, a));
            return;
        }
        int bars = ms < 80 ? 4 : ms < 160 ? 3 : ms < 300 ? 2 : 1;
        int color = ms < 80 ? 0xFF5BD46A : ms < 160 ? 0xFFB8E065 : ms < 300 ? 0xFFFFC14D : 0xFFFF5C5C;
        int bx = x + 4;
        for (int i = 0; i < 4; i++) {
            int bh = 2 + i * 2;
            g.fill(bx + i * 3, y + ROW - 2 - bh, bx + i * 3 + 2, y + ROW - 2, ColorMath.fade(i < bars ? color : 0x40FFFFFF, a));
        }
        UiFont.draw(g, String.valueOf(ms), bx + 15, y + 2, ColorMath.fade(0xFFC9D0D9, a), false);
    }
}
