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
import java.util.function.Consumer;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * Unread announcements in the top right of the lobby: at most two cards, the oldest unread first. The next one only
 * moves up once the player has marked a shown one as read ("已阅"); clicking a card opens it on the announcements tab.
 */
public final class AnnouncementReminder {
    public static final int WIDTH = 210;
    private static final int HEIGHT = 58;
    private static final int SHOWN = 2;

    private final Consumer<String> open;
    private final Map<String, AnimValue> cardIn = new HashMap<>();
    private final Map<String, Spring> hover = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    /** @param open shows an announcement on the announcements tab */
    public AnnouncementReminder(Consumer<String> open) {
        this.open = open;
    }

    /**
     * @param maxBottom cards stop above this y (the match panel)
     * @return the y below the last thing drawn
     */
    public int render(GuiGraphics g, int mouseX, int mouseY, int right, int top, int maxBottom, float alpha) {
        hits.clear();
        List<LobbyPackets.Announcement> unread = ClientAnnouncements.unread();
        cardIn.keySet().removeIf(id -> unread.stream().noneMatch(a -> a.id().equals(id)));
        int y = top;
        int shown = 0;
        for (LobbyPackets.Announcement a : unread) {
            if (shown == SHOWN || y + HEIGHT > maxBottom) break;
            AnimValue in = cardIn.computeIfAbsent(a.id(), id -> {
                AnimValue value = new AnimValue(0);
                Anim.to(value, 1, 0.55).ease(Ease.BACK_OUT).delay(0.15);
                return value;
            });
            card(g, mouseX, mouseY, a, right, y, alpha, in.get());
            y += HEIGHT + 5;
            shown++;
        }
        int more = unread.size() - shown;
        if (more > 0 && shown > 0) {
            String text = tr("blockland.news.more_unread", more);
            Draw.text(g, text, right - UiFont.width(text), y, 1f, ColorMath.fade(MUTED, alpha), false);
            y += 12;
        }
        return y;
    }

    private void card(GuiGraphics g, int mouseX, int mouseY, LobbyPackets.Announcement a, int right, int y, float alpha, float in) {
        float e = Math.min(1, Math.max(0, in));
        float ca = alpha * e;
        int x = Math.round(right - WIDTH + (1 - in) * 50);
        int accent = a.pinned() ? GOLD : INFO;
        boolean hovered = inside(mouseX, mouseY, x, y, WIDTH, HEIGHT);
        float hv = spring("card" + a.id(), hovered);
        Draw.glow(g, x + WIDTH / 2f, y + HEIGHT / 2f, WIDTH * 0.6f, HEIGHT, ColorMath.fade(accent, (0.08f + 0.08f * hv) * ca));
        Draw.rect(g, x, y, WIDTH, HEIGHT, ColorMath.fade(ColorMath.mix(0xF2141C29, 0xF21B2537, hv), ca));
        Draw.rect(g, x, y, 3, HEIGHT, ColorMath.fade(accent, ca));

        String tag = tr(a.pinned() ? "blockland.news.tag_pinned" : "blockland.news.tag");
        int tagW = UiFont.width(tag) + 6;
        Draw.rect(g, x + 9, y + 6, tagW, 10, ColorMath.fade(ColorMath.withAlpha(accent, 0x33), ca));
        Draw.text(g, tag, x + 12, y + 7, 1f, ColorMath.fade(accent, ca), false);
        String time = AnnouncementTime.at(a.publishedAt());
        Draw.text(g, time, x + 15 + tagW, y + 7, 1f, ColorMath.fade(DIM, ca), false);

        Draw.text(g, Draw.fit(a.title(), WIDTH - 18), x + 9, y + 20, 1f, ColorMath.fade(TEXT, ca), false);
        List<FormattedCharSequence> lines = Draw.wrap(a.content().replace('\n', ' '), WIDTH - 60);
        if (!lines.isEmpty()) Draw.text(g, lines.get(0), x + 9, y + 32, ColorMath.fade(MUTED, ca));
        if (lines.size() > 1) Draw.text(g, lines.get(1), x + 9, y + 43, ColorMath.fade(MUTED, ca));

        // 已阅
        String readLabel = tr("blockland.news.mark_read");
        int bw = UiFont.width(readLabel) + 10, bh = 13, bx = x + WIDTH - bw - 6, by = y + HEIGHT - bh - 5;
        boolean overButton = inside(mouseX, mouseY, bx, by, bw, bh);
        float bhv = spring("read" + a.id(), overButton);
        Draw.rect(g, bx, by, bw, bh, ColorMath.fade(ColorMath.lighten(GO, 0.12f * bhv), ca));
        Draw.text(g, readLabel, bx + 5, by + 3, 1f, ColorMath.fade(TEXT, ca), false);
        String id = a.id();
        hits.add(new Hit(bx, by, bw, bh, () -> ClientAnnouncements.markRead(List.of(id))));
        hits.add(new Hit(x, y, WIDTH, HEIGHT, () -> open.accept(id)));
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        for (Hit hit : hits) {
            if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        return false;
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
