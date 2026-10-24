package com.blockland.backend.gametype.battleroyale;

import com.blockland.backend.map.GameMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Map settings of 大逃杀 maps: {@code {"lootSpots": [{"x", "y", "z"}]}} — block coordinates relative to the map's minimum
 * corner of the blocks loot appears in (standing on the block below). Without spots the mod scatters the mode's
 * {@code autoLootSpots} over the island's surface. Team spawns are not used: everybody jumps from the plane.
 * Must stay in sync with the mod's {@code BattleRoyaleMap}.
 */
public final class BattleRoyaleMapSettings {
    public static final int MAX_SPOTS = 1000;

    public record Spot(int x, int y, int z) {
        boolean inside(GameMap.Size size) {
            return x >= 0 && y >= 0 && z >= 0 && x < size.x() && y < size.y() && z < size.z();
        }
    }

    private BattleRoyaleMapSettings() {
    }

    public static ObjectNode toJson(ObjectMapper mapper, List<Spot> spots) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode list = node.putArray("lootSpots");
        for (Spot s : spots) list.addObject().put("x", s.x()).put("y", s.y()).put("z", s.z());
        return node;
    }

    /** The stored spots; broken entries are skipped (settings were validated when saved). */
    public static List<Spot> read(JsonNode settings) {
        List<Spot> spots = new ArrayList<>();
        if (settings == null) return spots;
        for (JsonNode node : settings.path("lootSpots")) {
            if (!node.path("x").isNumber() || !node.path("y").isNumber() || !node.path("z").isNumber()) continue;
            spots.add(new Spot((int) Math.floor(node.get("x").asDouble()), (int) Math.floor(node.get("y").asDouble()),
                    (int) Math.floor(node.get("z").asDouble())));
        }
        return spots;
    }

    /** Validates settings edited in the admin UI: integer coordinates, no duplicates, at most {@value #MAX_SPOTS}. */
    public static ObjectNode normalize(ObjectMapper mapper, JsonNode raw, List<String> errors) {
        List<Spot> spots = new ArrayList<>();
        if (raw == null || raw.isNull()) return toJson(mapper, spots);
        if (!raw.isObject()) {
            errors.add("大逃杀地图设置格式错误");
            return toJson(mapper, spots);
        }
        JsonNode list = raw.get("lootSpots");
        if (list != null && !list.isNull() && !list.isArray()) errors.add("物资点列表必须是数组");
        if (list == null || !list.isArray()) return toJson(mapper, spots);
        if (list.size() > MAX_SPOTS) errors.add("物资点最多 " + MAX_SPOTS + " 个");
        Set<Spot> seen = new HashSet<>();
        for (int i = 0; i < list.size() && i < MAX_SPOTS; i++) {
            JsonNode node = list.get(i);
            if (!node.isObject() || !isInt(node.get("x")) || !isInt(node.get("y")) || !isInt(node.get("z"))) {
                errors.add("物资点 " + (i + 1) + ": 坐标必须是整数");
                continue;
            }
            Spot spot = new Spot(node.get("x").asInt(), node.get("y").asInt(), node.get("z").asInt());
            if (!seen.add(spot)) {
                errors.add("物资点坐标重复: (" + spot.x() + ", " + spot.y() + ", " + spot.z() + ")");
                continue;
            }
            spots.add(spot);
        }
        return toJson(mapper, spots);
    }

    /** A replaced map file keeps its spots (the admin decides whether they still fit). */
    public static ObjectNode fromFile(ObjectMapper mapper, ObjectNode previous) {
        return toJson(mapper, read(previous));
    }

    /** How many of the map's spots lie outside its box. */
    public static long outside(GameMap map) {
        return read(map.settings()).stream().filter(s -> !s.inside(map.size())).count();
    }

    private static boolean isInt(JsonNode node) {
        return node != null && node.isNumber() && node.asDouble() == Math.rint(node.asDouble());
    }
}
