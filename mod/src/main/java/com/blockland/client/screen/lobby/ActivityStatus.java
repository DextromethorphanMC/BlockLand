package com.blockland.client.screen.lobby;

import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.ActivityPackets;
import net.minecraft.client.gui.GuiGraphics;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/** Where an activity stands in time, how that reads, and its colours — shared by the activities page and its detail screen. */
public final class ActivityStatus {
    private static final DateTimeFormatter SAME_YEAR = DateTimeFormatter.ofPattern("M/d HH:mm");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm");

    public enum Phase { UPCOMING, ONGOING, ENDED }

    private ActivityStatus() {
    }

    public static Phase phase(ActivityPackets.Activity a) {
        long now = System.currentTimeMillis();
        if (a.startsAt() > 0 && now < a.startsAt()) return Phase.UPCOMING;
        if (a.endsAt() > 0 && now >= a.endsAt()) return Phase.ENDED;
        return Phase.ONGOING;
    }

    /** The activity's own colour, or the lobby's information blue. */
    public static int accent(ActivityPackets.Activity a) {
        return a.accent() == 0 ? INFO : ColorMath.withAlpha(a.accent(), 0xFF);
    }

    /** "即将开始 · 11/30 20:00 开启", "进行中 · 剩余 3 天", "长期开放", "已结束". */
    public static String line(ActivityPackets.Activity a) {
        return switch (phase(a)) {
            case UPCOMING -> tr("blockland.activity.upcoming") + " · " + tr("blockland.activity.opens_at", at(a.startsAt()));
            case ENDED -> tr("blockland.activity.ended");
            case ONGOING -> a.endsAt() > 0 ? tr("blockland.activity.ongoing") + " · " + LimitedTime.remaining(a.endsAt())
                    : tr("blockland.activity.permanent");
        };
    }

    /** "11/1 00:00 – 11/30 23:59", "11/1 00:00 起", "截至 11/30 23:59", or empty for an activity without times. */
    public static String range(ActivityPackets.Activity a) {
        if (a.startsAt() > 0 && a.endsAt() > 0) return at(a.startsAt()) + " – " + at(a.endsAt());
        if (a.startsAt() > 0) return tr("blockland.activity.from", at(a.startsAt()));
        if (a.endsAt() > 0) return LimitedTime.endsAt(a.endsAt());
        return "";
    }

    /**
     * The phase as a small pill: gold while it runs out within two days, the accent while running, grey otherwise.
     *
     * @return the pill width
     */
    public static int badge(GuiGraphics g, ActivityPackets.Activity a, int x, int y, float alpha) {
        Phase phase = phase(a);
        if (phase == Phase.ONGOING && a.endsAt() > 0 && a.endsAt() - System.currentTimeMillis() < 2 * 86_400_000L) {
            return LimitedTime.badge(g, tr("blockland.activity.ending_soon"), x, y, alpha);
        }
        String text = tr(switch (phase) {
            case UPCOMING -> "blockland.activity.upcoming";
            case ENDED -> "blockland.activity.ended";
            case ONGOING -> "blockland.activity.ongoing";
        });
        int w = UiFont.width(text) + 10;
        int color = phase == Phase.ONGOING ? accent(a) : phase == Phase.UPCOMING ? INFO : MUTED;
        Draw.rect(g, x, y, w, 12, ColorMath.fade(phase == Phase.ENDED ? 0xCC2A3140 : ColorMath.withAlpha(color, 0x33), alpha));
        Draw.text(g, text, x + 5, y + 2, 1f, ColorMath.fade(color, alpha), false);
        return w;
    }

    private static String at(long epochMillis) {
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        return (time.getYear() == LocalDateTime.now().getYear() ? SAME_YEAR : FULL).format(time);
    }
}
