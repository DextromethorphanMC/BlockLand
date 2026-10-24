package com.blockland.backend.gametype.bomb;

import com.blockland.backend.map.GameMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Map settings of 枪战爆破 maps: {@code {"sites": [{"name": "A", "x1", "y1", "z1", "x2", "y2", "z2"}]}} — the bomb sites
 * as boxes of block coordinates relative to the map's minimum corner, both corners inclusive (the mod plants inside
 * [min, max + 1)). Stored with {@code x1 <= x2}, {@code y1 <= y2}, {@code z1 <= z2}. Must stay in sync with the mod's
 * {@code BombSites}.
 */
public final class BombMapSettings {
    public static final int MAX_SITES = 8;
    public static final int MAX_NAME_LENGTH = 8;

    public record Site(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        /** Whether the box reaches outside the map's bounds. */
        public boolean leaves(GameMap.Size size) {
            return size != null && (x1 < 0 || y1 < 0 || z1 < 0 || x2 >= size.x() || y2 >= size.y() || z2 >= size.z());
        }
    }

    private BombMapSettings() {
    }

    public static ObjectNode toJson(ObjectMapper mapper, List<Site> sites) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode list = node.putArray("sites");
        for (Site s : sites) {
            list.addObject().put("name", s.name())
                    .put("x1", s.x1()).put("y1", s.y1()).put("z1", s.z1())
                    .put("x2", s.x2()).put("y2", s.y2()).put("z2", s.z2());
        }
        return node;
    }

    /** The stored sites; broken entries are skipped (settings were validated when saved). */
    public static List<Site> read(JsonNode settings) {
        List<Site> sites = new ArrayList<>();
        if (settings == null) return sites;
        for (JsonNode node : settings.path("sites")) {
            String name = node.path("name").asText("").trim();
            if (name.isEmpty() || !coordinates(node)) continue;
            sites.add(box(name, node));
        }
        return sites;
    }

    /** Validates settings edited in the admin UI. */
    public static ObjectNode normalize(ObjectMapper mapper, JsonNode raw, List<String> errors) {
        List<Site> sites = new ArrayList<>();
        if (raw == null || raw.isNull()) return toJson(mapper, sites);
        if (!raw.isObject()) {
            errors.add("枪战爆破地图设置格式错误");
            return toJson(mapper, sites);
        }
        JsonNode list = raw.get("sites");
        if (list != null && !list.isNull() && !list.isArray()) errors.add("包点列表必须是数组");
        if (list == null || !list.isArray()) return toJson(mapper, sites);
        if (list.size() > MAX_SITES) errors.add("最多 " + MAX_SITES + " 个包点");

        Set<String> names = new HashSet<>();
        for (int i = 0; i < list.size() && i < MAX_SITES; i++) {
            JsonNode node = list.get(i);
            String row = "包点 " + (i + 1) + ": ";
            if (!node.isObject()) {
                errors.add(row + "格式错误");
                continue;
            }
            String name = node.path("name").isTextual() ? node.get("name").asText().trim() : "";
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
                errors.add(row + "名称必须是 1 到 " + MAX_NAME_LENGTH + " 个字符");
                continue;
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                errors.add(row + "名称「" + name + "」重复");
                continue;
            }
            if (!coordinates(node)) {
                errors.add(row + "坐标必须是整数");
                continue;
            }
            sites.add(box(name, node));
        }
        return toJson(mapper, sites);
    }

    /** A replaced map file keeps the sites (they are coordinates the admin drew, not markers in the file). */
    public static ObjectNode fromFile(ObjectMapper mapper, ObjectNode previous) {
        return toJson(mapper, read(previous));
    }

    private static boolean coordinates(JsonNode node) {
        for (String key : List.of("x1", "y1", "z1", "x2", "y2", "z2")) {
            JsonNode value = node.get(key);
            if (value == null || !value.isNumber() || !value.canConvertToInt() || value.asDouble() != Math.rint(value.asDouble())) {
                return false;
            }
        }
        return true;
    }

    private static Site box(String name, JsonNode n) {
        int x1 = n.get("x1").asInt(), y1 = n.get("y1").asInt(), z1 = n.get("z1").asInt();
        int x2 = n.get("x2").asInt(), y2 = n.get("y2").asInt(), z2 = n.get("z2").asInt();
        return new Site(name, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }
}
