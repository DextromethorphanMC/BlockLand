package com.blockland.backend.gametype.infection;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Map settings of 枪战感染 maps: {@code {"spawns": [{"x", "y", "z", "yaw", "role"}]}}. HUMAN points are where humans
 * start a round, MONSTER points are where killed monsters respawn, BOTH points serve either. With an empty list the mod
 * falls back to the map's team spawns (group 1 = HUMAN, group 2 = MONSTER, a single group = BOTH), see
 * {@link #effectiveSpawns}. Must stay in sync with the mod.
 */
public final class InfectionMapSettings {
    public enum Role {
        HUMAN, MONSTER, BOTH;

        public boolean forHumans() {
            return this != MONSTER;
        }

        public boolean forMonsters() {
            return this != HUMAN;
        }
    }

    /** Coordinates relative to the map's minimum corner, like team spawn points. */
    public record Spawn(double x, double y, double z, float yaw, Role role) {
    }

    private InfectionMapSettings() {
    }

    public static ObjectNode toJson(ObjectMapper mapper, List<Spawn> spawns) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode list = node.putArray("spawns");
        for (Spawn s : spawns) {
            list.addObject().put("x", s.x()).put("y", s.y()).put("z", s.z()).put("yaw", s.yaw()).put("role", s.role().name());
        }
        return node;
    }

    /** The stored spawn points; broken entries are skipped (settings were validated when saved). */
    public static List<Spawn> read(JsonNode settings) {
        List<Spawn> spawns = new ArrayList<>();
        if (settings == null) return spawns;
        for (JsonNode node : settings.path("spawns")) {
            Role role = role(node.get("role"));
            if (!node.path("x").isNumber() || !node.path("y").isNumber() || !node.path("z").isNumber() || role == null) continue;
            spawns.add(new Spawn(node.get("x").asDouble(), node.get("y").asDouble(), node.get("z").asDouble(), (float) node.path("yaw").asDouble(0), role));
        }
        return spawns;
    }

    /** Validates settings edited in the admin UI; a missing role means BOTH. */
    public static ObjectNode normalize(ObjectMapper mapper, JsonNode raw, List<String> errors) {
        List<Spawn> spawns = new ArrayList<>();
        if (raw == null || raw.isNull()) return toJson(mapper, spawns);
        if (!raw.isObject()) {
            errors.add("枪战感染地图设置格式错误");
            return toJson(mapper, spawns);
        }
        JsonNode list = raw.get("spawns");
        if (list != null && !list.isNull() && !list.isArray()) errors.add("出生点列表必须是数组");
        if (list == null || !list.isArray()) return toJson(mapper, spawns);

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode node = list.get(i);
            String row = "出生点 " + (i + 1) + ": ";
            if (!node.isObject() || !node.path("x").isNumber() || !node.path("y").isNumber() || !node.path("z").isNumber()) {
                errors.add(row + "坐标必须是数字");
                continue;
            }
            JsonNode yaw = node.get("yaw");
            if (yaw != null && !yaw.isNull() && !yaw.isNumber()) errors.add(row + "朝向必须是数字");
            JsonNode roleNode = node.get("role");
            Role role = roleNode == null || roleNode.isNull() ? Role.BOTH : role(roleNode);
            if (role == null) {
                errors.add(row + "角色必须是 HUMAN（人类）、MONSTER（怪物）或 BOTH（通用）");
                continue;
            }
            Spawn spawn = new Spawn(node.get("x").asDouble(), node.get("y").asDouble(), node.get("z").asDouble(),
                    yaw != null && yaw.isNumber() ? (float) yaw.asDouble() : 0f, role);
            if (!seen.add(key(spawn))) errors.add("出生点坐标重复: (" + plain(spawn.x()) + ", " + plain(spawn.y()) + ", " + plain(spawn.z()) + ")");
            spawns.add(spawn);
        }
        return toJson(mapper, spawns);
    }

    /**
     * Spawn points of a freshly imported or replaced file: every spawn marker becomes a BOTH point facing the map
     * centre, except where a previous point stood at the same coordinates (its role and yaw are kept).
     * A file without markers keeps the previous points.
     */
    public static ObjectNode fromFile(ObjectMapper mapper, MapFileAnalyzer.Result analysis, ObjectNode previous) {
        List<Spawn> old = read(previous);
        if (analysis.markers().isEmpty()) return toJson(mapper, old);
        Map<String, Spawn> byPos = new HashMap<>();
        for (Spawn spawn : old) byPos.putIfAbsent(key(spawn), spawn);
        double cx = analysis.size().x() / 2.0, cz = analysis.size().z() / 2.0;
        List<Spawn> spawns = new ArrayList<>();
        for (MapFileAnalyzer.Block b : analysis.markers()) {
            Spawn kept = byPos.get(b.x() + "," + b.y() + "," + b.z());
            spawns.add(kept != null ? kept : new Spawn(b.x(), b.y(), b.z(), MapFileAnalyzer.yawTowards(b, cx, cz), Role.BOTH));
        }
        return toJson(mapper, spawns);
    }

    /** The points the mod uses for a map: its own role spawns, otherwise the team spawn fallback. */
    public static List<Spawn> effectiveSpawns(GameMap map) {
        List<Spawn> own = read(map.settings());
        if (!own.isEmpty()) return own;
        List<GameMap.TeamSpawn> groups = map.teamSpawns() == null ? List.of()
                : map.teamSpawns().stream().filter(t -> t.points() != null && !t.points().isEmpty()).toList();
        List<Spawn> spawns = new ArrayList<>();
        for (int i = 0; i < Math.min(2, groups.size()); i++) {
            Role role = groups.size() == 1 ? Role.BOTH : i == 0 ? Role.HUMAN : Role.MONSTER;
            for (GameMap.SpawnPoint p : groups.get(i).points()) spawns.add(new Spawn(p.x(), p.y(), p.z(), p.yaw(), role));
        }
        return spawns;
    }

    private static Role role(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        try {
            return Role.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Block position, the way the mod places players. */
    private static String key(Spawn s) {
        return (int) Math.floor(s.x()) + "," + (int) Math.floor(s.y()) + "," + (int) Math.floor(s.z());
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
