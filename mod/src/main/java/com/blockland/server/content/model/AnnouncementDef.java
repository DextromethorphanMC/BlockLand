package com.blockland.server.content.model;

import java.time.Instant;

/** An announcement published in the admin panel (公告). Mirrors the backend's {@code Announcement}; times are ISO-8601. */
public final class AnnouncementDef {
    public String id;
    public String title = "";
    public String content = "";
    public boolean pinned;
    public String publishedAt;
    public String updatedAt;

    public long publishedMillis() {
        return millis(publishedAt);
    }

    public long updatedMillis() {
        return updatedAt == null ? publishedMillis() : millis(updatedAt);
    }

    private static long millis(String iso) {
        try {
            return iso == null ? 0 : Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
