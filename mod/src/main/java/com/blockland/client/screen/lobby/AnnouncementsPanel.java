package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.ClientAnnouncements;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.LobbyPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The announcements tab: every announcement (pinned first, then newest) on the left, the chosen one in full on the
 * right. Showing an announcement marks it as read.
 */
public final class AnnouncementsPanel {
    private static final int ROW_H = 40;

    private final Map<String, Spring> hover = new HashMap<>();
    private final AnimValue detailIn = new AnimValue(1);
    private final List<Hit> hits = new ArrayList<>();
    private String selected = "";
    private float listScroll, detailScroll;
    private int listX, listY, listW, listH, detailX, detailY, detailW, detailH;
    private int listContent, detailContent;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    public AnnouncementsPanel() {
    }

    /** Shows (and marks as read) one announcement, e.g. from the reminder. */
    public void select(String id) {
        if (!id.equals(selected)) {
            selected = id;
            detailScroll = 0;
            Anim.fromTo(detailIn, 0, 1, 0.35).ease(Ease.CUBIC_OUT);
        }
        ClientAnnouncements.markRead(List.of(id));
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float alpha) {
        hits.clear();
        List<LobbyPackets.Announcement> list = ClientAnnouncements.forList();
        if (list.isEmpty()) {
            String empty = tr("blockland.news.empty");
            Draw.text(g, empty, x + (w - UiFont.width(empty)) / 2f, y + h / 2f - 4, 1f, ColorMath.fade(MUTED, alpha), false);
            return;
        }
        if (list.stream().noneMatch(a -> a.id().equals(selected))) select(list.get(0).id());

        listX = x;
        listY = y + 22;
        listW = Math.min(260, (int) (w * 0.36f));
        listH = h - 22;
        detailX = x + listW + 14;
        detailY = y;
        detailW = w - listW - 14;
        detailH = h;

        // header of the list
        Draw.text(g, tr("blockland.news.title"), x, y + 4, 1f, ColorMath.fade(TEXT, alpha), false);
        int unread = ClientAnnouncements.unread().size();
        if (unread > 0) {
            String all = tr("blockland.news.mark_all_read");
            int bw = UiFont.width(all) + 10, bx = x + listW - bw;
            boolean over = inside(mouseX, mouseY, bx, y, bw, 14);
            float hv = spring("all", over);
            Draw.rect(g, bx, y, bw, 14, ColorMath.fade(ColorMath.mix(0xFF1A2333, 0xFF2A3650, hv), alpha));
            Draw.text(g, all, bx + 5, y + 3, 1f, ColorMath.fade(ColorMath.mix(MUTED, TEXT, hv), alpha), false);
            hits.add(new Hit(bx, y, bw, 14, () -> ClientAnnouncements.markRead(list.stream().map(LobbyPackets.Announcement::id).toList())));
        }

        // list
        Draw.rect(g, listX, listY, listW, listH, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, listX, listY, listW, listH, ColorMath.fade(LINE, alpha));
        listContent = list.size() * (ROW_H + 2);
        listScroll = clampScroll(listScroll, listContent, listH - 4);
        g.enableScissor(listX + 1, listY + 1, listX + listW - 1, listY + listH - 1);
        int ry = listY + 2 - Math.round(listScroll);
        for (LobbyPackets.Announcement a : list) {
            if (ry + ROW_H > listY && ry < listY + listH) row(g, mouseX, mouseY, a, listX + 2, ry, listW - 4, alpha);
            ry += ROW_H + 2;
        }
        g.disableScissor();

        // detail
        LobbyPackets.Announcement shown = list.stream().filter(a -> a.id().equals(selected)).findFirst().orElse(list.get(0));
        detail(g, shown, alpha * detailIn.get(), (1 - detailIn.get()) * 8);
    }

    private void row(GuiGraphics g, int mouseX, int mouseY, LobbyPackets.Announcement a, int x, int y, int w, float alpha) {
        boolean over = inside(mouseX, mouseY, x, y, w, ROW_H) && inside(mouseX, mouseY, listX, listY, listW, listH);
        boolean chosen = a.id().equals(selected);
        float hv = spring("row" + a.id(), over);
        int bg = chosen ? CARD_SELECTED : ColorMath.mix(CARD, CARD_HOVER, hv);
        Draw.rect(g, x, y, w, ROW_H, ColorMath.fade(bg, alpha));
        int accent = a.pinned() ? GOLD : INFO;
        if (chosen) Draw.rect(g, x, y, 2, ROW_H, ColorMath.fade(accent, alpha));
        boolean unread = !ClientAnnouncements.isRead(a.id());
        int tx = x + 8;
        if (unread) {
            Draw.glow(g, tx + 2, y + 10, 4, 4, ColorMath.fade(ERROR, alpha * 0.6f));
            Draw.rect(g, tx, y + 8, 4, 4, ColorMath.fade(ERROR, alpha));
            tx += 8;
        }
        if (a.pinned()) {
            String pin = tr("blockland.news.pinned");
            Draw.rect(g, tx, y + 5, UiFont.width(pin) + 6, 10, ColorMath.fade(0x33FFD54F, alpha));
            Draw.text(g, pin, tx + 3, y + 6, 1f, ColorMath.fade(GOLD, alpha), false);
            tx += UiFont.width(pin) + 10;
        }
        Draw.text(g, Draw.fit(a.title(), x + w - 8 - tx), tx, y + 6, 1f, ColorMath.fade(unread ? TEXT : MUTED, alpha), false);
        String time = AnnouncementTime.at(a.publishedAt()) + " · " + AnnouncementTime.ago(a.publishedAt());
        Draw.text(g, Draw.fit(time, w - 16), x + 8, y + 22, 1f, ColorMath.fade(DIM, alpha), false);
        String id = a.id();
        hits.add(new Hit(x, Math.max(y, listY), w, Math.min(y + ROW_H, listY + listH) - Math.max(y, listY), () -> select(id)));
    }

    private void detail(GuiGraphics g, LobbyPackets.Announcement a, float alpha, float shift) {
        int x = detailX, y = detailY, w = detailW, h = detailH;
        Draw.rect(g, x, y, w, h, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, x, y, w, h, ColorMath.fade(LINE, alpha));
        int accent = a.pinned() ? GOLD : INFO;
        g.fill(x, y + 6, x + 2, y + 34, ColorMath.fade(accent, alpha));
        int tx = x + 14;
        List<FormattedCharSequence> title = Draw.wrap(a.title(), (int) ((w - 28) / 1.4f));
        float ty = y + 10 + shift;
        for (FormattedCharSequence line : title.subList(0, Math.min(2, title.size()))) {
            g.pose().pushMatrix();
            g.pose().translate(tx, ty);
            g.pose().scale(1.4f, 1.4f);
            Draw.text(g, line, 0, 0, ColorMath.fade(TEXT, alpha));
            g.pose().popMatrix();
            ty += UiFont.LINE_HEIGHT * 1.4f + 2;
        }
        String published = tr("blockland.news.published", AnnouncementTime.at(a.publishedAt()));
        if (a.updatedAt() > a.publishedAt() + 60_000) published += "  ·  " + tr("blockland.news.edited", AnnouncementTime.at(a.updatedAt()));
        Draw.text(g, published, tx, ty + 2, 1f, ColorMath.fade(DIM, alpha), false);
        int bodyTop = Math.round(ty + 18);
        g.fill(tx, bodyTop - 5, x + w - 14, bodyTop - 4, ColorMath.fade(LINE, alpha));

        // body: paragraphs kept, wrapped, scrollable
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String paragraph : a.content().split("\n", -1)) {
            if (paragraph.isBlank()) lines.add(FormattedCharSequence.EMPTY);
            else lines.addAll(Draw.wrap(paragraph, w - 30));
        }
        int lineH = UiFont.LINE_HEIGHT + 3;
        int bodyH = y + h - 8 - bodyTop;
        detailContent = lines.size() * lineH;
        detailScroll = clampScroll(detailScroll, detailContent, bodyH);
        g.enableScissor(x + 1, bodyTop, x + w - 1, y + h - 6);
        float ly = bodyTop - detailScroll;
        for (FormattedCharSequence line : lines) {
            if (ly + lineH > bodyTop && ly < y + h) Draw.text(g, line, tx, ly, ColorMath.fade(0xFFD9E1EC, alpha));
            ly += lineH;
        }
        g.disableScissor();
        if (detailContent > bodyH) {
            // scroll bar
            float frac = bodyH / (float) detailContent;
            int barH = Math.max(12, Math.round(bodyH * frac));
            int barY = bodyTop + Math.round((bodyH - barH) * (detailScroll / Math.max(1, detailContent - bodyH)));
            g.fill(x + w - 5, barY, x + w - 3, barY + barH, ColorMath.fade(0x55FFFFFF, alpha));
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        for (Hit hit : hits) {
            if (hit.h > 0 && inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (inside(mouseX, mouseY, listX, listY, listW, listH)) {
            listScroll = clampScroll(listScroll - (float) amount * 24, listContent, listH - 4);
            return true;
        }
        if (inside(mouseX, mouseY, detailX, detailY, detailW, detailH)) {
            detailScroll = clampScroll(detailScroll - (float) amount * 24, detailContent, detailH - 60);
            return true;
        }
        return false;
    }

    private static float clampScroll(float value, int content, int view) {
        return Math.max(0, Math.min(value, Math.max(0, content - view)));
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
