package com.blockland.backend.gametype.battleroyale;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.common.MovementSettings;
import com.blockland.backend.gametype.common.TeamArenaSettings;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.blockland.backend.registry.ItemSpecs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 大逃杀: everybody rides a plane across an island, jumps when they like, opens the parachute they were given, loots
 * what lies around and fights inside a shrinking zone; the last player alive wins. Free for all ({@code players}
 * one-player teams).
 * Settings: {@code players} (2–64), {@code mapIds}, {@code countdownSeconds}, {@code endingSeconds}, {@code introSeconds}
 * (the opening cinematic), {@code planeHeight} (blocks above the map's top), {@code planeSpeed} (blocks/s),
 * {@code autoDeployHeight} (blocks above ground at which a closed parachute opens by itself, 0 = never), the zone
 * ({@code zoneEnabled}, {@code zoneDelaySeconds}, {@code zoneShrinkSeconds}, {@code zoneFinalRadius}, {@code zoneDamage}
 * per second), the loot ({@code loot}: {@code [{item, min, max, weight}]}, {@code lootPerSpot: {min, max}},
 * {@code autoLootSpots} used when a map has no spots), {@code allowBlockBreak} / {@code allowBlockPlace} and the movement
 * rules ({@code allowSlide}). The mode's {@code timeLimitSeconds} is an optional hard limit.
 * Map settings: {@link BattleRoyaleMapSettings}. Must stay in sync with the mod's {@code BattleRoyaleSettings}.
 */
@Component
public class BattleRoyaleDefinition implements GameTypeDefinition {
    public static final String ID = "BATTLE_ROYALE";
    public static final int MAX_LOOT_ENTRIES = 100;
    private static final boolean ALLOW_SLIDE = true;

    /** The default loot table: [item, min, max, weight]. */
    private static final Object[][] DEFAULT_LOOT = {
            {"blockland:ak47", 1, 1, 6},
            {"blockland:usp", 1, 1, 10},
            {"blockland:tommy", 1, 1, 6},
            {"blockland:ks3", 1, 1, 5},
            {"blockland:rpd", 1, 1, 2},
            {"blockland:awp", 1, 1, 2},
            {"blockland:rifle_ammo", 20, 45, 14},
            {"blockland:pistol_ammo", 15, 30, 14},
            {"blockland:smg_ammo", 20, 45, 10},
            {"blockland:shotgun_ammo", 6, 12, 8},
            {"blockland:sniper_ammo", 5, 10, 4},
            {"blockland:frag_grenade", 1, 2, 6},
            {"blockland:smoke_grenade", 1, 1, 4},
            {"blockland:adrenaline", 1, 1, 4},
            {"minecraft:iron_helmet", 1, 1, 5},
            {"minecraft:iron_chestplate", 1, 1, 3},
            {"minecraft:iron_leggings", 1, 1, 4},
            {"minecraft:iron_boots", 1, 1, 5},
            {"minecraft:golden_apple", 1, 2, 5},
            {"minecraft:cooked_beef", 3, 8, 10},
    };

    private final ObjectMapper mapper;
    private final ItemSpecs items;

    public BattleRoyaleDefinition(ObjectMapper mapper, ItemSpecs items) {
        this.mapper = mapper;
        this.items = items;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "大逃杀";
    }

    @Override
    public String description() {
        return "乘飞机飞越岛屿，自选时机跳伞落地，搜刮枪械与物资，在缩小的安全区内对战，最后存活的玩家获胜。";
    }

    @Override
    public ObjectNode defaultSettings() {
        ObjectNode node = mapper.createObjectNode();
        node.put("players", 16);
        node.putArray("mapIds");
        node.put("countdownSeconds", 5);
        node.put("endingSeconds", 8);
        node.put("introSeconds", 6);
        node.put("planeHeight", 45);
        node.put("planeSpeed", 14.0);
        node.put("autoDeployHeight", 18);
        node.put("zoneEnabled", true);
        node.put("zoneDelaySeconds", 60);
        node.put("zoneShrinkSeconds", 240);
        node.put("zoneFinalRadius", 8);
        node.put("zoneDamage", 1.0);
        node.putObject("lootPerSpot").put("min", 1).put("max", 3);
        node.put("autoLootSpots", 80);
        ArrayNode loot = node.putArray("loot");
        for (Object[] e : DEFAULT_LOOT) {
            loot.addObject().put("item", (String) e[0]).put("min", (int) e[1]).put("max", (int) e[2]).put("weight", (int) e[3]);
        }
        node.put("allowBlockBreak", false);
        node.put("allowBlockPlace", false);
        MovementSettings.putDefaults(node, ALLOW_SLIDE);
        return node;
    }

    @Override
    public ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors) {
        JsonNode in = raw == null || !raw.isObject() ? mapper.createObjectNode() : raw;
        ObjectNode out = defaultSettings();
        out.put("players", TeamArenaSettings.intField(in, "players", 16, 2, 64, "每局人数", errors));
        TeamArenaSettings.normalizeMapIds(in, out, ID, context, errors);
        out.put("countdownSeconds", TeamArenaSettings.intField(in, "countdownSeconds", 5, 0, 120, "开局倒计时", errors));
        out.put("endingSeconds", TeamArenaSettings.intField(in, "endingSeconds", 8, 1, 60, "结算时间", errors));
        out.put("introSeconds", TeamArenaSettings.intField(in, "introSeconds", 6, 0, 20, "开场动画时长", errors));
        out.put("planeHeight", TeamArenaSettings.intField(in, "planeHeight", 45, 20, 120, "飞机高度", errors));
        out.put("planeSpeed", TeamArenaSettings.doubleField(in, "planeSpeed", 14, 4, 40, "飞机速度", errors));
        out.put("autoDeployHeight", TeamArenaSettings.intField(in, "autoDeployHeight", 18, 0, 100, "自动开伞高度", errors));
        out.put("zoneEnabled", TeamArenaSettings.boolField(in, "zoneEnabled", true));
        out.put("zoneDelaySeconds", TeamArenaSettings.intField(in, "zoneDelaySeconds", 60, 0, 600, "安全区开始收缩", errors));
        out.put("zoneShrinkSeconds", TeamArenaSettings.intField(in, "zoneShrinkSeconds", 240, 10, 1800, "安全区收缩时长", errors));
        out.put("zoneFinalRadius", TeamArenaSettings.intField(in, "zoneFinalRadius", 8, 0, 200, "最终安全区半径", errors));
        out.put("zoneDamage", TeamArenaSettings.doubleField(in, "zoneDamage", 1, 0, 20, "安全区外伤害", errors));

        JsonNode perSpot = in.path("lootPerSpot");
        int min = TeamArenaSettings.intField(perSpot, "min", 1, 0, 10, "每个物资点最少物品数", errors);
        int max = TeamArenaSettings.intField(perSpot, "max", 3, 0, 10, "每个物资点最多物品数", errors);
        if (min > max) errors.add("每个物资点最少物品数不能大于最多物品数");
        out.putObject("lootPerSpot").put("min", Math.min(min, max)).put("max", max);
        out.put("autoLootSpots", TeamArenaSettings.intField(in, "autoLootSpots", 80, 0, 400, "自动物资点数量", errors));
        out.set("loot", normalizeLoot(in.get("loot"), errors));

        out.put("allowBlockBreak", TeamArenaSettings.boolField(in, "allowBlockBreak", false));
        out.put("allowBlockPlace", TeamArenaSettings.boolField(in, "allowBlockPlace", false));
        MovementSettings.normalizeInto(in, out, ALLOW_SLIDE);
        return out;
    }

    /** {@code loot} rows: an item spec in /give syntax, 1 ≤ min ≤ max ≤ 64, weight 1–1000. Missing = the default table. */
    private ArrayNode normalizeLoot(JsonNode raw, List<String> errors) {
        if (raw == null || raw.isNull()) return (ArrayNode) defaultSettings().get("loot");
        ArrayNode out = mapper.createArrayNode();
        if (!raw.isArray()) {
            errors.add("物资表必须是数组");
            return out;
        }
        if (raw.size() > MAX_LOOT_ENTRIES) errors.add("物资表最多 " + MAX_LOOT_ENTRIES + " 项");
        for (int i = 0; i < raw.size() && i < MAX_LOOT_ENTRIES; i++) {
            JsonNode row = raw.get(i);
            String label = "物资第 " + (i + 1) + " 项: ";
            if (!row.isObject()) {
                errors.add(label + "格式错误");
                continue;
            }
            String item = row.path("item").asText("").trim();
            items.checkFormat(item, label, errors);
            int min = TeamArenaSettings.intField(row, "min", 1, 1, 64, label + "最少数量", errors);
            int max = TeamArenaSettings.intField(row, "max", min, 1, 64, label + "最多数量", errors);
            if (min > max) errors.add(label + "最少数量不能大于最多数量");
            int weight = TeamArenaSettings.intField(row, "weight", 10, 1, 1000, label + "权重", errors);
            out.addObject().put("item", item).put("min", Math.min(min, max)).put("max", max).put("weight", weight);
        }
        return out;
    }

    @Override
    public List<String> warnings(GameMode mode, Context context) {
        List<String> warnings = new ArrayList<>();
        JsonNode settings = mode.settings();
        List<GameMap> maps = TeamArenaSettings.mapPool(mode, ID, context);
        if (maps.isEmpty()) warnings.add("当前没有任何大逃杀地图，玩家无法匹配");
        int autoSpots = settings == null ? 80 : settings.path("autoLootSpots").asInt(80);
        for (GameMap map : maps) {
            List<BattleRoyaleMapSettings.Spot> spots = BattleRoyaleMapSettings.read(map.settings());
            if (spots.isEmpty() && autoSpots == 0) {
                warnings.add("地图「" + map.name() + "」没有物资点，且自动物资点数量为 0：岛上不会有任何物资");
            }
            long outside = BattleRoyaleMapSettings.outside(map);
            if (outside > 0) warnings.add("地图「" + map.name() + "」有 " + outside + " 个物资点超出了地图范围");
        }
        JsonNode loot = settings == null ? null : settings.get("loot");
        if (loot == null || !loot.isArray() || loot.isEmpty()) {
            warnings.add("物资表为空：岛上不会刷出任何物品");
        } else {
            for (int i = 0; i < loot.size(); i++) {
                items.checkRegistered(loot.get(i).path("item").asText(""), "物资第 " + (i + 1) + " 项: ", warnings);
            }
        }
        return warnings;
    }

    /** Players land with nothing but their parachute (given by the game) and loot the rest. */
    @Override
    public boolean usesKit() {
        return true;
    }

    @Override
    public ObjectNode mapSettingsFromFile(MapFileAnalyzer.Result analysis, ObjectNode previous) {
        return BattleRoyaleMapSettings.fromFile(mapper, previous);
    }

    @Override
    public ObjectNode normalizeMapSettings(JsonNode raw, List<String> errors) {
        return BattleRoyaleMapSettings.normalize(mapper, raw, errors);
    }
}
