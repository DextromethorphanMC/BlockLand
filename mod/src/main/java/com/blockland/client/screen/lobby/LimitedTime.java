package com.blockland.client.screen.lobby;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Ticker;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import net.minecraft.client.gui.GuiGraphics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/** Wording and the gold badge for limited-time modes ("限时"). */
public final class LimitedTime {
    private static final DateTimeFormatter END = DateTimeFormatter.ofPattern("M/d HH:mm");
    private static final DateTimeFormatter END_YEAR = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm");

    private LimitedTime() {
    }

    public static boolean isOver(long until) {
        return until > 0 && System.currentTimeMillis() >= until;
    }

    /** "剩余 38 天", "剩余 5 小时 12 分", "剩余 04:32", "已结束". */
    public static String remaining(long until) {
        long seconds = (until - System.currentTimeMillis()) / 1000L;
        if (seconds <= 0) return tr("blockland.limited.over");
        long days = seconds / 86400;
        if (days >= 2) return tr("blockland.limited.days_left", days);
        long hours = seconds / 3600;
        if (hours >= 1) return tr("blockland.limited.hours_left", hours, seconds % 3600 / 60);
        return tr("blockland.limited.clock_left", String.format("%02d:%02d", seconds / 60, seconds % 60));
    }

    /** "截至 11/30 23:59" in the viewer's time zone (with the year when it is not this year). */
    public static String endsAt(long until) {
        var end = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault());
        boolean thisYear = end.getYear() == java.time.Year.now().getValue();
        return tr("blockland.limited.ends_at", (thisYear ? END : END_YEAR).format(end));
    }

    /** Ending within two days: the badge pulses to draw attention. */
    private static boolean urgent(long until) {
        return until - System.currentTimeMillis() < 2 * 86_400_000L;
    }

    /**
     * Gold pill with a light sweep.
     *
     * @return the pill width
     */
    public static int badge(GuiGraphics g, String text, int x, int y, float alpha) {
        int w = UiFont.width(text) + 10;
        double t = Ticker.global().time();
        Draw.glow(g, x + w / 2f, y + 6, w * 0.7f, 10, ColorMath.fade(GOLD, alpha * 0.25f));
        Draw.rect(g, x, y, w, 12, ColorMath.fade(0xF0E2A72E, alpha));
        // sweep: a soft highlight that crosses the pill every ~2.4 s
        float sweep = (float) ((t * 0.42) % 1.0) * (w + 24) - 12;
        g.enableScissor(x, y, x + w, y + 12);
        Draw.glow(g, x + sweep, y + 6, 8, 9, ColorMath.fade(0xFFFFFFFF, alpha * 0.55f));
        g.disableScissor();
        Draw.text(g, text, x + 5, y + 2, 1f, ColorMath.fade(0xFF1A1206, alpha), false);
        return w;
    }

    /** Badge for a mode card, right-aligned at {@code right}: "限时 · 剩余 38 天" (pulses when it ends soon). */
    public static int modeBadge(GuiGraphics g, long until, int right, int y, float alpha) {
        String over = tr("blockland.limited.over");
        String text = isOver(until) ? over : tr("blockland.limited.badge", remaining(until));
        int x = right - UiFont.width(text) - 10;
        if (isOver(until)) {
            int w = UiFont.width(over) + 10;
            Draw.rect(g, x, y, w, 12, ColorMath.fade(0xCC2A3140, alpha));
            Draw.text(g, over, x + 5, y + 2, 1f, ColorMath.fade(MUTED, alpha), false);
            return w;
        }
        float pulse = urgent(until) ? (float) (0.75 + 0.25 * Math.sin(Ticker.global().time() * 5)) : 1f;
        return badge(g, text, x, y, alpha * pulse);
    }
}
