package com.blockland.games.bomb;

import com.blockland.server.content.model.MapDef;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The bomb sites of a map, from its type-owned settings ({@code settings.sites}: {@code name} and the block corners
 * {@code x1 y1 z1} / {@code x2 y2 z2}, both inclusive, relative to the map origin; edited in the admin map editor).
 * Mirrors the backend's {@code BombMapSettings}.
 */
record BombSites(List<Site> sites) {
    /** A site as a box in map coordinates: {@code min} inclusive, {@code max} exclusive (whole blocks). */
    record Site(String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    boolean isEmpty() {
        return sites.isEmpty();
    }

    static BombSites of(MapDef map) {
        List<Site> sites = new ArrayList<>();
        JsonObject settings = map.settings;
        if (settings != null && settings.has("sites") && settings.get("sites").isJsonArray()) {
            for (JsonElement e : settings.getAsJsonArray("sites")) {
                if (!e.isJsonObject() || sites.size() >= 8) continue;
                JsonObject s = e.getAsJsonObject();
                if (!has(s, "x1") || !has(s, "y1") || !has(s, "z1") || !has(s, "x2") || !has(s, "y2") || !has(s, "z2")) continue;
                int x1 = number(s, "x1"), y1 = number(s, "y1"), z1 = number(s, "z1");
                int x2 = number(s, "x2"), y2 = number(s, "y2"), z2 = number(s, "z2");
                String name = s.has("name") && s.get("name").isJsonPrimitive() ? s.get("name").getAsString().trim() : "";
                if (name.isEmpty()) name = String.valueOf((char) ('A' + sites.size()));
                sites.add(new Site(name, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                        Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1));
            }
        }
        return new BombSites(List.copyOf(sites));
    }

    private static boolean has(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isNumber();
    }

    private static int number(JsonObject json, String key) {
        return (int) Math.floor(json.get(key).getAsDouble());
    }
}
