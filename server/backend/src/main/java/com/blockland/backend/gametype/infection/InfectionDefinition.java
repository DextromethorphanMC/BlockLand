package com.blockland.backend.gametype.infection;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.common.MovementSettings;
import com.blockland.backend.gametype.common.TeamArenaSettings;
import com.blockland.backend.kit.KitItem;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.blockland.backend.registry.GameTypeRegistryData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 枪战感染, played over several rounds: everyone starts a round as a human with the mode's kit; after
 * {@code infectionDelaySeconds} {@code startMonsters} random players become mother monsters, and a human killed by a
 * monster is infected and picks a monster type. Humans win the round when anyone is still human at the mode's time
 * limit ("存活时间", per round), monsters when every human is infected; the side that wins more rounds wins.
 * Map settings: {@link InfectionMapSettings} (spawn points with roles). The monsters on offer are published by the game
 * server through {@link GameTypeRegistryData}. Must stay in sync with the mod's {@code InfectionSettings}.
 */
@Component
public class InfectionDefinition implements GameTypeDefinition {
    public static final String ID = "INFECTION";
    /** Humans run (and slide) from the monsters; the mod never lets monsters slide. */
    private static final boolean ALLOW_SLIDE = true;
    /** Survival time per round the mod uses when the mode has no time limit. */
    public static final int DEFAULT_TIME_LIMIT_SECONDS = 180;
    private static final Pattern MONSTER_ID = Pattern.compile("^[a-z0-9_]{1,32}$");

    private final ObjectMapper mapper;
    private final GameTypeRegistryData registry;

    public InfectionDefinition(ObjectMapper mapper, GameTypeRegistryData registry) {
        this.mapper = mapper;
        this.registry = registry;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "枪战感染";
    }

    @Override
    public String description() {
        return "多回合的人类与感染者对抗：每回合开局后出现母体怪物，被怪物击杀的人类会被感染；人类坚持到存活时间结束赢下回合，"
                + "感染全部人类则怪物赢下回合，赢得回合多的一方获胜。";
    }

    @Override
    public ObjectNode defaultSettings() {
        ObjectNode node = mapper.createObjectNode();
        node.put("players", 8);
        node.putArray("mapIds");
        node.put("countdownSeconds", 10);
        node.put("endingSeconds", 8);
        node.put("rounds", 3);
        node.put("roundBreakSeconds", 6);
        node.put("startMonsters", 1);
        node.put("infectionDelaySeconds", 15);
        node.put("motherHealthMultiplier", 2.0);
        node.put("monsterDamage", 6.0);
        node.put("monsterRespawnSeconds", 5);
        node.put("selectSeconds", 10);
        node.putArray("monsters");
        MovementSettings.putDefaults(node, ALLOW_SLIDE);
        return node;
    }

    @Override
    public ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors) {
        JsonNode in = raw == null || !raw.isObject() ? mapper.createObjectNode() : raw;
        ObjectNode out = defaultSettings();
        int players = TeamArenaSettings.intField(in, "players", 8, 2, 32, "每局人数", errors);
        out.put("players", players);
        TeamArenaSettings.normalizeMapIds(in, out, ID, context, errors);
        out.put("countdownSeconds", TeamArenaSettings.intField(in, "countdownSeconds", 10, 0, 120, "开局倒计时", errors));
        out.put("endingSeconds", TeamArenaSettings.intField(in, "endingSeconds", 8, 1, 60, "结算时间", errors));
        out.put("rounds", TeamArenaSettings.intField(in, "rounds", 3, 1, 15, "回合数", errors));
        out.put("roundBreakSeconds", TeamArenaSettings.intField(in, "roundBreakSeconds", 6, 3, 30, "回合间隔", errors));
        int startMonsters = TeamArenaSettings.intField(in, "startMonsters", 1, 1, 31, "开局怪物人数", errors);
        if (startMonsters >= players) {
            errors.add("开局怪物人数 必须小于每局人数 " + players);
            startMonsters = players - 1;
        }
        out.put("startMonsters", startMonsters);
        out.put("infectionDelaySeconds", TeamArenaSettings.intField(in, "infectionDelaySeconds", 15, 3, 60, "母体出现时间", errors));
        out.put("motherHealthMultiplier", TeamArenaSettings.doubleField(in, "motherHealthMultiplier", 2.0, 1.0, 5.0, "母体生命倍率", errors));
        out.put("monsterDamage", TeamArenaSettings.doubleField(in, "monsterDamage", 6.0, 1, 40, "怪物伤害", errors));
        out.put("monsterRespawnSeconds", TeamArenaSettings.intField(in, "monsterRespawnSeconds", 5, 1, 30, "怪物复活时间", errors));
        out.put("selectSeconds", TeamArenaSettings.intField(in, "selectSeconds", 10, 3, 30, "怪物选择时间", errors));
        if (in.hasNonNull("monsters")) normalizeMonsters(in.get("monsters"), out, errors);
        MovementSettings.normalizeInto(in, out, ALLOW_SLIDE);
        return out;
    }

    /** Trimmed, lower case, without duplicates; an empty list means every monster the server has. */
    private static void normalizeMonsters(JsonNode in, ObjectNode out, List<String> errors) {
        if (!in.isArray()) {
            errors.add("怪物列表必须是数组");
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : in) {
            String id = node.asText("").trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            if (!MONSTER_ID.matcher(id).matches()) {
                errors.add("怪物 ID 只能包含小写字母、数字和下划线: " + id);
            } else {
                ids.add(id);
            }
        }
        ArrayNode monsters = out.putArray("monsters");
        ids.forEach(monsters::add);
    }

    @Override
    public List<String> warnings(GameMode mode, Context context) {
        List<String> warnings = new ArrayList<>();
        JsonNode settings = mode.settings();
        int players = settings == null ? 8 : settings.path("players").asInt(8);

        List<GameMap> maps = TeamArenaSettings.mapPool(mode, ID, context);
        if (maps.isEmpty()) {
            warnings.add("没有可用的地图");
        } else {
            long playable = 0;
            for (GameMap map : maps) {
                List<InfectionMapSettings.Spawn> spawns = InfectionMapSettings.effectiveSpawns(map);
                long humans = spawns.stream().filter(s -> s.role().forHumans()).count();
                long monsters = spawns.stream().filter(s -> s.role().forMonsters()).count();
                if (humans == 0) {
                    warnings.add("地图「" + map.name() + "」没有人类可用的出生点（角色为人类或通用）");
                    continue;
                }
                playable++;
                if (monsters == 0) warnings.add("地图「" + map.name() + "」没有怪物复活点，怪物将在人类出生点复活");
                if (humans < players) {
                    warnings.add("地图「" + map.name() + "」只有 " + humans + " 个人类可用的出生点，少于每局人数 " + players + "，玩家会共用出生位置");
                }
            }
            if (playable == 0) warnings.add(0, "当前没有任何地图满足该模式，玩家无法匹配");
        }

        if (mode.timeLimitSeconds() == null) {
            warnings.add("未设置存活时间，每回合按 " + DEFAULT_TIME_LIMIT_SECONDS + " 秒计算（时间结束时仍有人类存活则人类赢下该回合）");
        }
        if (mode.kit().isEmpty()) warnings.add("没有配置开局装备，人类将空手进入对局");
        Set<String> published = publishedMonsters();
        if (!published.isEmpty() && settings != null) {
            for (JsonNode id : settings.path("monsters")) {
                if (!published.contains(id.asText())) warnings.add("游戏服务器没有怪物「" + id.asText() + "」，模组会忽略它");
            }
        }
        return warnings;
    }

    /** Monster ids the game server published; empty while no server has published any. */
    private Set<String> publishedMonsters() {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode monster : registry.get(ID).path("monsters")) {
            String id = monster.path("id").asText("");
            if (!id.isBlank()) ids.add(id);
        }
        return ids;
    }

    @Override
    public ObjectNode mapSettingsFromFile(MapFileAnalyzer.Result analysis, ObjectNode previous) {
        return InfectionMapSettings.fromFile(mapper, analysis, previous);
    }

    @Override
    public ObjectNode normalizeMapSettings(JsonNode raw, List<String> errors) {
        return InfectionMapSettings.normalize(mapper, raw, errors);
    }

    @Override
    public boolean usesKit() {
        return true;
    }

    /**
     * P9A, 120 pistol rounds and a butterfly knife. Kit stacks hold at most 64, so the rounds are split into a
     * hotbar stack and a backpack stack (reloading takes ammo from the whole inventory).
     */
    @Override
    public List<KitItem> defaultKit() {
        return List.of(
                new KitItem("blockland:p9a", 1, "0"),
                new KitItem("blockland:pistol_ammo", 60, "1"),
                new KitItem("blockland:butterfly", 1, "2"),
                new KitItem("blockland:pistol_ammo", 60, "9"));
    }
}
