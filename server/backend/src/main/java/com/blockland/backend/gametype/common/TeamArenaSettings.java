package com.blockland.backend.gametype.common;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.map.GameMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Settings shared by team based arena games: {@code teamSize}, {@code teamCount}, {@code mapIds},
 * {@code countdownSeconds}, {@code endingSeconds}. Mirrors the mod's {@code TeamArenaSettings}.
 */
public final class TeamArenaSettings {
    private TeamArenaSettings() {
    }

    public static void putDefaults(ObjectNode node) {
        node.put("teamSize", 1);
        node.put("teamCount", 2);
        node.putArray("mapIds");
        node.put("countdownSeconds", 10);
        node.put("endingSeconds", 8);
    }

    public static void normalizeInto(JsonNode in, ObjectNode out, String typeId, GameTypeDefinition.Context context, List<String> errors) {
        out.put("teamSize", intField(in, "teamSize", 1, 1, 64, "每队人数", errors));
        out.put("teamCount", intField(in, "teamCount", 2, 1, 64, "队伍数量", errors));
        out.put("countdownSeconds", intField(in, "countdownSeconds", 10, 0, 120, "开局倒计时", errors));
        out.put("endingSeconds", intField(in, "endingSeconds", 8, 1, 60, "结算时间", errors));

        ArrayNode mapIds = out.putArray("mapIds");
        if (in.has("mapIds") && in.get("mapIds").isArray()) {
            for (JsonNode idNode : in.get("mapIds")) {
                String id = idNode.asText();
                Optional<GameMap> map = context.maps().stream().filter(m -> m.id().equals(id)).findFirst();
                if (map.isEmpty()) {
                    errors.add("地图不存在: " + id);
                } else if (!typeId.equalsIgnoreCase(map.get().gameType())) {
                    errors.add("地图 " + map.get().name() + " 不属于该游戏类型");
                } else {
                    mapIds.add(id);
                }
            }
        }
    }

    /** Maps in the mode's pool (all maps of the type when the pool is empty). */
    public static List<GameMap> mapPool(GameMode mode, String typeId, GameTypeDefinition.Context context) {
        List<GameMap> maps = new ArrayList<>();
        JsonNode ids = mode.settings() == null ? null : mode.settings().path("mapIds");
        if (ids != null && ids.isArray() && !ids.isEmpty()) {
            for (JsonNode id : ids) context.maps().stream().filter(m -> m.id().equals(id.asText())).forEach(maps::add);
        } else {
            context.maps().stream().filter(m -> typeId.equalsIgnoreCase(m.gameType())).forEach(maps::add);
        }
        return maps;
    }

    /** Warnings about the map pool not fitting the team format. */
    public static List<String> mapWarnings(GameMode mode, String typeId, GameTypeDefinition.Context context) {
        List<String> warnings = new ArrayList<>();
        JsonNode settings = mode.settings();
        int teamCount = settings == null ? 2 : settings.path("teamCount").asInt(2);
        int teamSize = settings == null ? 1 : settings.path("teamSize").asInt(1);
        List<GameMap> maps = mapPool(mode, typeId, context);
        if (maps.isEmpty()) {
            warnings.add("没有可用的地图");
            return warnings;
        }
        long playable = 0;
        for (GameMap map : maps) {
            long groups = map.usableTeamSpawns();
            if (groups < teamCount) {
                warnings.add("地图「" + map.name() + "」只有 " + groups + " 个队伍出生点，需要 " + teamCount + " 个");
                continue;
            }
            playable++;
            boolean shortSpawns = map.teamSpawns().stream().anyMatch(t -> t.points() != null && !t.points().isEmpty() && t.points().size() < teamSize);
            if (shortSpawns) warnings.add("地图「" + map.name() + "」部分队伍出生点少于每队人数，队友会共用出生位置");
        }
        if (playable == 0) warnings.add(0, "当前没有任何地图满足该模式，玩家无法匹配");
        return warnings;
    }

    public static int intField(JsonNode node, String key, int fallback, int min, int max, String label, List<String> errors) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return fallback;
        if (!value.canConvertToInt()) {
            errors.add(label + " 必须是整数");
            return fallback;
        }
        int v = value.asInt();
        if (v < min || v > max) errors.add(label + " 必须在 " + min + " 到 " + max + " 之间");
        return Math.max(min, Math.min(max, v));
    }

    public static boolean boolField(JsonNode node, String key, boolean fallback) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? fallback : value.asBoolean(fallback);
    }
}
