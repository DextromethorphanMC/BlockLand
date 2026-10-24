package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.ClientActivities;
import com.blockland.client.ClientMedia;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.ActivityPackets;
import com.blockland.network.packet.MediaPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The activities tab ("活动"): every activity the server sent as a card in a grid (pinned first). Clicking a card asks
 * the owner to open that activity's own screen; this panel only lists.
 */
public final class ActivitiesPanel {
    private static final int HEADER = 22;
    private static final int GAP = 10;
    private static final int MIN_CARD_W = 220;
    private static final int CARD_H = 104;

    private final Consumer<String> onOpen;
    private final Map<String, Spring> hover = new HashMap<>();
    private final AnimValue cardsIn = new AnimValue(1);
    private final List<Hit> hits = new ArrayList<>();
    private float scroll;
    private int gridX, gridY, gridW, gridH, content;

    private record Hit(int x, int y, int w, int h, String id) {
    }

    /** @param onOpen called with the id of the activity the player opened */
    public ActivitiesPanel(Consumer<String> onOpen) {
        this.onOpen = onOpen;
    }

    /** The tab was switched to: the cards rise in again. */
    public void open() {
        Anim.fromTo(cardsIn, 0, 1, 0.5).ease(Ease.CUBIC_OUT);
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float alpha) {
        hits.clear();
        List<ActivityPackets.Activity> list = ClientActivities.forList();

        g.fill(x, y + 3, x + 3, y + 13, ColorMath.fade(ACCENT, alpha));
        Draw.text(g, tr("blockland.activity.all"), x + 8, y + 4, 1f, ColorMath.fade(TEXT, alpha), false);
        String count = tr("blockland.activity.count", list.size());
        Draw.text(g, count, x + w - UiFont.width(count), y + 4, 1f, ColorMath.fade(DIM, alpha), false);

        gridX = x;
        gridY = y + HEADER;
        gridW = w;
        gridH = h - HEADER;
        if (list.isEmpty()) {
            empty(g, alpha);
            return;
        }

        int columns = Math.max(1, Math.min(3, (gridW + GAP) / (MIN_CARD_W + GAP)));
        int cardW = (gridW - GAP * (columns - 1)) / columns;
        int rows = (list.size() + columns - 1) / columns;
        content = rows * CARD_H + (rows - 1) * GAP;
        scroll = clampScroll(scroll);
        g.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        for (int i = 0; i < list.size(); i++) {
            int cx = gridX + i % columns * (cardW + GAP);
            int cy = gridY + i / columns * (CARD_H + GAP) - Math.round(scroll);
            if (cy + CARD_H <= gridY || cy >= gridY + gridH) continue;
            // staggered entrance: each card starts a little after the previous one
            float in = Math.max(0, Math.min(1, cardsIn.get() * 1.6f - i * 0.08f));
            card(g, mouseX, mouseY, list.get(i), cx, cy + Math.round((1 - in) * 14), cardW, alpha * in);
        }
        g.disableScissor();
        if (content > gridH) {
            float frac = gridH / (float) content;
            int barH = Math.max(12, Math.round(gridH * frac));
            int barY = gridY + Math.round((gridH - barH) * (scroll / Math.max(1, content - gridH)));
            g.fill(gridX + gridW + 4, barY, gridX + gridW + 6, barY + barH, ColorMath.fade(0x55FFFFFF, alpha));
        }
    }

    private void empty(GuiGraphics g, float alpha) {
        Draw.rect(g, gridX, gridY, gridW, gridH, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, gridX, gridY, gridW, gridH, ColorMath.fade(LINE, alpha));
        float cx = gridX + gridW / 2f, cy = gridY + gridH / 2f;
        Draw.glow(g, cx, cy - 8, 90, 40, ColorMath.fade(0x225EC8FF, alpha));
        String title = tr("blockland.activity.empty");
        g.pose().pushMatrix();
        g.pose().translate(cx - UiFont.width(title) * 1.4f / 2f, cy - 16);
        g.pose().scale(1.4f, 1.4f);
        Draw.text(g, title, 0, 0, 1f, ColorMath.fade(MUTED, alpha), false);
        g.pose().popMatrix();
        String hint = tr("blockland.activity.empty_hint");
        Draw.text(g, hint, cx - UiFont.width(hint) / 2f, cy + 4, 1f, ColorMath.fade(DIM, alpha), false);
    }

    private void card(GuiGraphics g, int mouseX, int mouseY, ActivityPackets.Activity a, int x, int y, int w, float alpha) {
        boolean over = inside(mouseX, mouseY, x, y, w, CARD_H) && inside(mouseX, mouseY, gridX, gridY, gridW, gridH);
        float hv = spring(a.id(), over);
        boolean ended = ActivityStatus.phase(a) == ActivityStatus.Phase.ENDED;
        int accent = ActivityStatus.accent(a);
        int lift = Math.round(hv * 2);
        y -= lift;

        Draw.rect(g, x, y, w, CARD_H, ColorMath.fade(ColorMath.mix(CARD, CARD_HOVER, hv), alpha));
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        ClientMedia.Image cover = a.coverVersion().isEmpty() ? null : ClientMedia.get(MediaPackets.ACTIVITY, a.id(), a.coverVersion());
        if (cover != null) {
            // the cover fills the card, shaded on the left so the text stays legible
            Draw.cover(g, cover, 1, 1, w - 2, CARD_H - 2, 1 + 0.05f * hv, ColorMath.fade(0xFFFFFFFF, alpha * (ended ? 0.35f : 0.85f + 0.15f * hv)));
            Draw.fadeX(g, 1, 1, w * 2 / 3, CARD_H - 2, ColorMath.fade(0xE00A0F18, alpha), true);
        } else {
            Draw.glow(g, w * 0.85f, CARD_H * 0.2f, w * 0.45f, CARD_H * 0.7f, ColorMath.fade(ColorMath.withAlpha(accent, 0x30), alpha * (ended ? 0.4f : 1)));
        }
        g.pose().popMatrix();

        Draw.rect(g, x, y, 2, CARD_H, ColorMath.fade(ended ? DIM : accent, alpha));
        Draw.outline(g, x, y, w, CARD_H, ColorMath.fade(over ? ColorMath.withAlpha(accent, 0xAA) : LINE, alpha));

        int tx = x + 12, tw = w - 24;
        int bx = tx;
        if (a.pinned()) {
            String pin = tr("blockland.activity.pinned");
            int pw = UiFont.width(pin) + 10;
            Draw.rect(g, bx, y + 10, pw, 12, ColorMath.fade(0x33FFD54F, alpha));
            Draw.text(g, pin, bx + 5, y + 12, 1f, ColorMath.fade(GOLD, alpha), false);
            bx += pw + 4;
        }
        ActivityStatus.badge(g, a, bx, y + 10, alpha);

        g.pose().pushMatrix();
        g.pose().translate(tx, y + 30);
        g.pose().scale(1.25f, 1.25f);
        Draw.text(g, Draw.fit(a.title(), tw / 1.25f), 0, 0, 1f, ColorMath.fade(ended ? MUTED : TEXT, alpha), false);
        g.pose().popMatrix();

        List<FormattedCharSequence> summary = Draw.wrap(a.summary(), tw);
        float sy = y + 48;
        for (FormattedCharSequence line : summary.subList(0, Math.min(2, summary.size()))) {
            Draw.text(g, line, tx, sy, ColorMath.fade(MUTED, alpha));
            sy += UiFont.LINE_HEIGHT + 2;
        }

        String status = ActivityStatus.line(a);
        Draw.text(g, Draw.fit(status, tw - 40), tx, y + CARD_H - 16, 1f, ColorMath.fade(DIM, alpha), false);
        String more = tr("blockland.activity.view");
        float mx = x + w - 12 - UiFont.width(more) + hv * 2;
        Draw.text(g, more, mx, y + CARD_H - 16, 1f, ColorMath.fade(ColorMath.mix(DIM, accent, hv), alpha), false);

        int top = Math.max(y, gridY), bottom = Math.min(y + CARD_H, gridY + gridH);
        hits.add(new Hit(x, top, w, bottom - top, a.id()));
    }

    /** @return true when a card was clicked (its activity is being opened) */
    public boolean mouseClicked(double mouseX, double mouseY) {
        for (Hit hit : hits) {
            if (hit.h > 0 && inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                onOpen.accept(hit.id);
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!inside(mouseX, mouseY, gridX, gridY, gridW, gridH)) return false;
        scroll = clampScroll(scroll - (float) amount * 28);
        return true;
    }

    private float clampScroll(float value) {
        return Math.max(0, Math.min(value, Math.max(0, content - gridH)));
    }

    private float spring(String key, boolean on) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(0).config(320, 26));
        spring.setTarget(on ? 1 : 0);
        return spring.get();
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
