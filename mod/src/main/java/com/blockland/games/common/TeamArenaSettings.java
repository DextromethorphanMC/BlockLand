package com.blockland.games.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings shared by team based arena games (mode {@code settings}): {@code teamSize}, {@code teamCount},
 * {@code mapIds} (empty = every map of the type), {@code countdownSeconds}, {@code endingSeconds}.
 * Mirrors the backend's {@code TeamArenaSettings}.
 */
public record TeamArenaSettings(int teamSize, int teamCount, List<String> mapIds, int countdownSeconds, int endingSeconds) {

    public static TeamArenaSettings parse(JsonObject json) {
        JsonObject s = json == null ? new JsonObject() : json;
        List<String> maps = new ArrayList<>();
        if (s.has("mapIds") && s.get("mapIds").isJsonArray()) {
            for (JsonElement e : s.getAsJsonArray("mapIds")) maps.add(e.getAsString());
        }
        return new TeamArenaSettings(
                intOr(s, "teamSize", 1, 1, 64),
                intOr(s, "teamCount", 2, 1, 64),
                List.copyOf(maps),
                intOr(s, "countdownSeconds", 10, 0, 120),
                intOr(s, "endingSeconds", 8, 1, 60));
    }

    public static int intOr(JsonObject json, String key, int fallback, int min, int max) {
        int value;
        try {
            value = json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            value = fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static boolean boolOr(JsonObject json, String key, boolean fallback) {
        try {
            return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
