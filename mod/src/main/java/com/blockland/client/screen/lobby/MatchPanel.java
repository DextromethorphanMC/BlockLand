package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimColor;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.LobbyModeEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * Bottom-right block: information about the selected (or queued) mode and the big matchmaking button. The
 * button colour morphs between states, the label cross-fades, and while queued a light sweep runs across it.
 */
public final class MatchPanel {
    private static final int KIT_W = 60;
    private static final int KIT_H = 15;

    public enum Action { NONE, MATCH, KIT }

    private final Font font;
    private final AnimColor buttonColor = new AnimColor(DISABLED);
    private final Spring hover = new Spring(0).config(300, 24);
    private final Spring press = new Spring(0).config(600, 28);
    private final Spring kitHover = new Spring(0).config(300, 24);
    private final AnimValue labelIn = new AnimValue(1);
    private final AnimValue timerPop = new AnimValue(1);
    private final AnimValue infoIn = new AnimValue(1);
    private String label = "";
    private String infoKey = "";
    private int lastQueuedSeconds = -1;
    private int x, infoY, w, infoH, btnY, btnH;
    private boolean kitVisible;

    public MatchPanel(Font font) {
        this.font = font;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph, int by, int bh,
                       @Nullable LobbyModeEntry selected, float alpha) {
        x = px;
        infoY = py;
        w = pw;
        infoH = ph;
        btnY = by;
        btnH = bh;
        boolean queued = ClientLobbyState.isQueued();
        boolean inGame = ClientLobbyState.inGame();
        LobbyModeEntry shown = queued ? ClientLobbyState.mode(ClientLobbyState.queuedModeId()).orElse(selected) : selected;

        // info swaps slide in when the content changes
        String key = (queued ? "q:" : "s:") + (shown == null ? "" : shown.id());
        if (!key.equals(infoKey)) {
            if (!infoKey.isEmpty()) Anim.fromTo(infoIn, 0, 1, 0.35).ease(Ease.CUBIC_OUT);
            infoKey = key;
        }
        renderInfo(g, mouseX, mouseY, shown, queued, alpha);

        // button state
        String newLabel;
        int color;
        boolean enabled = true;
        if (inGame) {
            newLabel = "对局进行中";
            color = DISABLED;
            enabled = false;
        } else if (queued) {
            newLabel = "取消匹配";
            color = CANCEL;
        } else if (!isLeader()) {
            newLabel = "等待队长开始";
            color = DISABLED;
            enabled = false;
        } else if (selected != null && selected.available()) {
            newLabel = "开始匹配";
            color = GO;
        } else {
            newLabel = "开始匹配";
            color = DISABLED;
            enabled = false;
        }
        if (!newLabel.equals(label)) {
            if (!label.isEmpty()) Anim.fromTo(labelIn, 0, 1, 0.3).ease(Ease.CUBIC_OUT);
            label = newLabel;
        }
        buttonColor.toIfChanged(color, 0.35, Ease.QUAD_OUT);
        boolean hovered = enabled && inside(mouseX, mouseY, x, btnY, w, btnH);
        hover.setTarget(hovered ? 1 : 0);

        float hv = hover.get(), pr = press.get();
        int base = ColorMath.lighten(buttonColor.get(), 0.08f * hv);
        g.pose().pushMatrix();
        g.pose().translate(x + w / 2f, btnY + btnH / 2f);
        float scale = 1 + 0.015f * hv - 0.03f * pr;
        g.pose().scale(scale, scale);
        g.pose().translate(-w / 2f, -btnH / 2f);
        Draw.glow(g, w / 2f, btnH / 2f, w * 0.6f, btnH * 1.1f, ColorMath.fade(buttonColor.get(), alpha * (enabled ? 0.18f + 0.22f * hv : 0)));
        Draw.rect(g, 0, 0, w, btnH, ColorMath.fade(base, alpha));
        Draw.fadeY(g, 0, 0, w, btnH / 2, ColorMath.fade(0x26FFFFFF, alpha), true);
        g.fill(0, btnH - 3, w, btnH, ColorMath.fade(0x40000000, alpha));
        if (queued) {
            // light sweep
            double t = (Ticker.global().time() * 0.55) % 1.0;
            float sx = (float) (-0.25 + t * 1.5) * w;
            g.enableScissor(0, 0, w, btnH); // scissor follows the pose
            Draw.glow(g, sx, btnH / 2f, w * 0.18f, btnH * 0.9f, ColorMath.fade(0x55FFFFFF, alpha));
            g.disableScissor();
        }
        float li = labelIn.get();
        float labelScale = 1.6f;
        float labelW = font.width(label) * labelScale;
        Draw.text(g, font, label, (w - labelW) / 2, (btnH - font.lineHeight * labelScale) / 2 + 1 + (1 - li) * 6,
                labelScale, ColorMath.fade(enabled ? TEXT : MUTED, alpha * li), true);
        g.pose().popMatrix();
    }

    private void renderInfo(GuiGraphics g, int mouseX, int mouseY, @Nullable LobbyModeEntry mode, boolean queued, float alpha) {
        Draw.rect(g, x, infoY, w, infoH, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, x, infoY, w, infoH, ColorMath.fade(LINE, alpha));
        float a = alpha * infoIn.get();
        float shift = (1 - infoIn.get()) * 8;
        int tx = x + 10;
        kitVisible = false;

        if (queued) {
            int seconds = ClientLobbyState.queuedSeconds();
            if (seconds != lastQueuedSeconds) {
                if (lastQueuedSeconds >= 0) Anim.fromTo(timerPop, 1.12f, 1, 0.35).ease(Ease.BACK_OUT);
                lastQueuedSeconds = seconds;
            }
            float pulse = (float) (0.5 + 0.5 * Math.sin(Ticker.global().time() * 5));
            Draw.glow(g, tx + 3, infoY + 11, 6, 6, ColorMath.fade(ACCENT, a * 0.6f * pulse));
            Draw.rect(g, tx, infoY + 8, 5, 5, ColorMath.fade(ACCENT, a));
            String dots = ".".repeat((int) (Ticker.global().time() * 2.5 % 4));
            Draw.text(g, font, "正在匹配" + dots, tx + 10 + shift, infoY + 7, 1f, ColorMath.fade(ACCENT, a), false);
            if (ClientLobbyState.priorityQueue()) {
                int badgeX = tx + 10 + font.width("正在匹配...") + 8;
                int badgeW = font.width("★ 优先匹配") + 8;
                Draw.rect(g, badgeX, infoY + 5, badgeW, 12, ColorMath.fade(0x33FFD54F, a));
                Draw.text(g, font, "★ 优先匹配", badgeX + 4, infoY + 7, 1f, ColorMath.fade(GOLD, a), false);
            }
            String timer = clock(seconds);
            float ts = 1.6f * timerPop.get();
            float tw = font.width(timer) * ts;
            Draw.text(g, font, timer, x + w - 10 - tw, infoY + 6 - (ts - 1.6f) * 4, ts, ColorMath.fade(TEXT, a), true);
            if (mode != null) {
                Draw.text(g, font, Draw.fit(font, mode.title(), w - 20), tx + shift, infoY + 24, 1f, ColorMath.fade(TEXT, a), false);
                // queue fill bar
                float fill = Math.min(1, mode.queued() / (float) Math.max(1, mode.requiredPlayers()));
                int barW = w - 20;
                Draw.rect(g, tx, infoY + 40, barW, 3, ColorMath.fade(0x22FFFFFF, a));
                Draw.rect(g, tx, infoY + 40, Math.max(3, Math.round(barW * fill)), 3, ColorMath.fade(ACCENT, a));
                Draw.text(g, font, "队列 " + mode.queued() + "/" + mode.requiredPlayers() + " 名玩家", tx, infoY + 46, 1f,
                        ColorMath.fade(MUTED, a), false);
            }
            return;
        }
        lastQueuedSeconds = -1;
        if (mode == null) {
            Draw.text(g, font, "请选择一个游戏模式", tx, infoY + infoH / 2f - 4, 1f, ColorMath.fade(MUTED, alpha), false);
            return;
        }
        int accent = mode.isLimited() ? GOLD : mode.accentColor() | 0xFF000000;
        Draw.text(g, font, "已选择", tx + shift, infoY + 7, 1f, ColorMath.fade(DIM, a), false);
        Draw.text(g, font, Draw.fit(font, mode.title(), (int) ((w - KIT_W - 30) / 1.25f)), tx + shift, infoY + 19, 1.25f,
                ColorMath.fade(TEXT, a), true);
        String desc = mode.description().isBlank() ? mode.typeName() + " · " + mode.formatLabel() : mode.description();
        if (mode.isLimited()) {
            // limited-time mode: when it ends replaces the description line
            String ends = LimitedTime.isOver(mode.availableUntil()) ? "限时模式已结束"
                    : LimitedTime.remaining(mode.availableUntil()) + " · " + LimitedTime.endsAt(mode.availableUntil());
            Draw.text(g, font, Draw.fit(font, ends, w - 20), tx + shift, infoY + 38, 1f, ColorMath.fade(GOLD, a), false);
            Draw.text(g, font, Draw.fit(font, desc, w - 20), tx + shift, infoY + 49, 1f, ColorMath.fade(DIM, a), false);
        } else {
            Draw.text(g, font, Draw.fit(font, desc, w - 20), tx + shift, infoY + 38, 1f, ColorMath.fade(MUTED, a), false);
        }
        g.fill(x, infoY + 4, x + 2, infoY + infoH - 4, ColorMath.fade(accent, a));

        if (mode.hasKit()) {
            kitVisible = true;
            int kx = kitX(), ky = kitY();
            kitHover.setTarget(inside(mouseX, mouseY, kx, ky, KIT_W, KIT_H) ? 1 : 0);
            float kh = kitHover.get();
            Draw.rect(g, kx, ky, KIT_W, KIT_H, ColorMath.fade(ColorMath.mix(0xFF243048, 0xFF34446A, kh), a));
            g.fill(kx + 1, ky + KIT_H - 1, kx + 1 + Math.round((KIT_W - 2) * (0.35f + 0.65f * kh)), ky + KIT_H, ColorMath.fade(ACCENT, a));
            float lw = font.width("装备布局");
            Draw.text(g, font, "装备布局", kx + (KIT_W - lw) / 2, ky + 4, 1f, ColorMath.fade(TEXT, a), false);
        }
    }

    private static boolean isLeader() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc.player == null || ClientLobbyState.isPartyLeader(mc.player.getUUID());
    }

    private int kitX() {
        return x + w - KIT_W - 8;
    }

    private int kitY() {
        return infoY + 6;
    }

    public Action mouseClicked(double mouseX, double mouseY) {
        if (inside(mouseX, mouseY, x, btnY, w, btnH)) {
            press.setTarget(1);
            return Action.MATCH;
        }
        if (kitVisible && !ClientLobbyState.isQueued() && inside(mouseX, mouseY, kitX(), kitY(), KIT_W, KIT_H)) {
            return Action.KIT;
        }
        return Action.NONE;
    }

    public void mouseReleased() {
        press.setTarget(0);
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static String clock(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
