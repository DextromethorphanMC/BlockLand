package com.blockland.server.content.model;

import com.google.gson.JsonObject;

import java.time.Instant;

/**
 * A lobby activity ("活动") published in the admin panel. Mirrors the backend's {@code Activity} (reserved: the backend
 * does not publish activities yet, so the list stays empty). Times are ISO-8601 instants.
 */
public final class ActivityDef {
    public String id;
    public String title = "";
    /** One line under the title on the activity card. */
    public String summary = "";
    /** Body text of the detail page; blank lines separate paragraphs. */
    public String content = "";
    /** How the detail page presents it; {@code info} (text only) when missing. */
    public String kind = "info";
    /** Settings only that kind understands (e.g. tasks and rewards), passed through to the client untouched. */
    public JsonObject data = new JsonObject();
    /** Optional accent colour, {@code #RRGGBB}. */
    public String accent;
    /** Version of the cover image ({@code activity/<id>}); null = no cover. */
    public String coverVersion;
    public boolean pinned;
    /** Smaller first among activities that are both pinned or both not. */
    public int order;
    /** Optional opening time; before it the activity shows as upcoming. */
    public String startsAt;
    /** Optional end; afterwards it shows as ended until the backend removes it. */
    public String endsAt;

    public long startsMillis() {
        return millis(startsAt);
    }

    public long endsMillis() {
        return millis(endsAt);
    }

    /** ARGB of {@link #accent}, or 0 when it is missing or broken. */
    public int accentArgb() {
        if (accent == null) return 0;
        String hex = accent.startsWith("#") ? accent.substring(1) : accent;
        if (hex.length() != 6) return 0;
        try {
            return 0xFF000000 | Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long millis(String iso) {
        try {
            return iso == null || iso.isBlank() ? 0 : Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
