package com.blockland.server.content.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * A playable mode configured in the backend ("小游戏管理").
 * {@link #settings} is opaque here and interpreted by the matching game type.
 */
public final class GameModeDef {
    public String id;
    public String title;
    /** Game type id, e.g. {@code SKYWARS}. */
    public String type;
    public String description = "";
    public boolean enabled = true;
    /** Optional match time limit ("存活时间") in seconds; null or <= 0 means unlimited. */
    public Integer timeLimitSeconds;
    public JsonObject settings = new JsonObject();
    /** Starting items, used by game types that support kits. */
    public List<KitItemDef> kit = new ArrayList<>();
    /** Version of the cover image ({@code mode/<id>}); null = no cover. */
    public String coverVersion;
    /** Optional end of a limited-time mode ("限时开放"), ISO-8601 instant; afterwards the mode closes by itself. */
    public String availableUntil;

    private transient long untilMillis = Long.MIN_VALUE;

    /** Epoch millis after which the mode is closed, or 0 for a permanent mode. */
    public long availableUntilMillis() {
        if (untilMillis == Long.MIN_VALUE) {
            long parsed = 0;
            if (availableUntil != null && !availableUntil.isBlank()) {
                try {
                    parsed = Instant.parse(availableUntil).toEpochMilli();
                } catch (DateTimeParseException ignored) {
                    // the backend validates this; a broken value must not close the mode
                }
            }
            untilMillis = parsed;
        }
        return untilMillis;
    }

    public boolean isExpired() {
        long until = availableUntilMillis();
        return until > 0 && System.currentTimeMillis() >= until;
    }

    /** Enabled and, for limited-time modes, not past its end. */
    public boolean isOpen() {
        return enabled && !isExpired();
    }

    public int timeLimitOrZero() {
        return timeLimitSeconds == null || timeLimitSeconds <= 0 ? 0 : timeLimitSeconds;
    }

    public List<KitItemDef> kitOrEmpty() {
        return kit == null ? List.of() : kit;
    }

    /** A yes/no setting every type may carry (e.g. {@code allowSlide}); {@code fallback} when it is missing or broken. */
    public boolean flag(String key, boolean fallback) {
        JsonElement value = settings == null ? null : settings.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
