package com.blockland.games.battleroyale;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 大逃杀 mode settings (free for all: {@code players} single-player teams). The flight, the zone and the loot table;
 * the mode's time limit is an optional hard end. Mirrors the backend's {@code BattleRoyaleDefinition}.
 */
record BattleRoyaleSettings(int players, List<String> mapIds, int countdownSeconds, int endingSeconds, int introSeconds,
                            int planeHeight, float planeSpeed, int autoDeployHeight, boolean zoneEnabled, int zoneDelaySeconds,
                            int zoneShrinkSeconds, int zoneFinalRadius, float zoneDamage, int lootMin, int lootMax,
                            int autoLootSpots, List<LootEntry> loot, boolean allowBlockBreak, boolean allowBlockPlace) {

    /** One line of the loot table: an item id (optional components), how many, and how often relative to the others. */
    record LootEntry(String item, int min, int max, int weight) {
    }

    static BattleRoyaleSettings parse(JsonObject raw) {
        JsonObject json = raw == null ? new JsonObject() : raw;
        List<String> maps = new ArrayList<>();
        if (json.has("mapIds") && json.get("mapIds").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("mapIds")) maps.add(e.getAsString());
        }
        JsonObject perSpot = json.has("lootPerSpot") && json.get("lootPerSpot").isJsonObject() ? json.getAsJsonObject("lootPerSpot") : new JsonObject();
        int lootMin = TeamArenaSettings.intOr(perSpot, "min", 1, 0, 16);
        int lootMax = Math.max(lootMin, TeamArenaSettings.intOr(perSpot, "max", 3, 0, 16));
        List<LootEntry> loot = new ArrayList<>();
        if (json.has("loot") && json.get("loot").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("loot")) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (!o.has("item") || !o.get("item").isJsonPrimitive()) continue;
                int min = TeamArenaSettings.intOr(o, "min", 1, 1, 64);
                loot.add(new LootEntry(o.get("item").getAsString(), min, Math.max(min, TeamArenaSettings.intOr(o, "max", min, 1, 64)),
                        TeamArenaSettings.intOr(o, "weight", 10, 1, 1000)));
            }
        }
        return new BattleRoyaleSettings(
                TeamArenaSettings.intOr(json, "players", 16, 2, 64),
                List.copyOf(maps),
                TeamArenaSettings.intOr(json, "countdownSeconds", 5, 0, 120),
                TeamArenaSettings.intOr(json, "endingSeconds", 8, 1, 60),
                TeamArenaSettings.intOr(json, "introSeconds", 6, 0, 20),
                TeamArenaSettings.intOr(json, "planeHeight", 45, 20, 120),
                floatOr(json, "planeSpeed", 14, 4, 40),
                TeamArenaSettings.intOr(json, "autoDeployHeight", 18, 0, 200),
                TeamArenaSettings.boolOr(json, "zoneEnabled", true),
                TeamArenaSettings.intOr(json, "zoneDelaySeconds", 60, 0, 3600),
                TeamArenaSettings.intOr(json, "zoneShrinkSeconds", 240, 10, 3600),
                TeamArenaSettings.intOr(json, "zoneFinalRadius", 8, 1, 1000),
                floatOr(json, "zoneDamage", 1, 0, 100),
                lootMin, lootMax,
                TeamArenaSettings.intOr(json, "autoLootSpots", 80, 0, 400),
                List.copyOf(loot),
                TeamArenaSettings.boolOr(json, "allowBlockBreak", false),
                TeamArenaSettings.boolOr(json, "allowBlockPlace", false));
    }

    private static float floatOr(JsonObject json, String key, float fallback, float min, float max) {
        float value;
        try {
            value = json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsFloat() : fallback;
        } catch (Exception e) {
            value = fallback;
        }
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
