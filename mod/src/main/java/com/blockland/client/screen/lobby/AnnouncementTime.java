package com.blockland.client.screen.lobby;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.blockland.client.ui.Tr.tr;

/** How announcement times read in the lobby: the local date and time, plus how long ago. */
final class AnnouncementTime {
    private static final DateTimeFormatter SAME_YEAR = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private AnnouncementTime() {
    }

    static String at(long epochMillis) {
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        boolean sameYear = time.getYear() == LocalDateTime.now().getYear();
        return (sameYear ? SAME_YEAR : FULL).format(time);
    }

    static String ago(long epochMillis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
        if (seconds < 60) return tr("blockland.news.ago.now");
        if (seconds < 3600) return tr("blockland.news.ago.minutes", seconds / 60);
        if (seconds < 86400) return tr("blockland.news.ago.hours", seconds / 3600);
        return tr("blockland.news.ago.days", seconds / 86400);
    }
}
