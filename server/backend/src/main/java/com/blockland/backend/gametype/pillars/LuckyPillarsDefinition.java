package com.blockland.backend.gametype.pillars;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.common.MovementSettings;
import com.blockland.backend.gametype.common.TeamArenaSettings;
import com.blockland.backend.gametype.common.WeightedLoot;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.registry.ItemSpecs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 幸运高柱: everyone starts on top of a tall pillar of their own (one team spawn group per pillar), random items arrive
 * at a fixed interval, players bridge, climb and fight; the last one standing wins. Free for all ({@code players}
 * one-player teams). Settings: {@code players} (2–64), {@code mapIds}, {@code countdownSeconds}, {@code endingSeconds},
 * {@code itemIntervalSeconds}, {@code itemsPerDrop}, the item table {@code items} ({@link WeightedLoot}),
 * {@code projectileKnockback} (snowballs, eggs and fishing bobbers knock players back; the mod's {@code ProjectileHits})
 * and the movement rules ({@code allowSlide}). The mode's {@code timeLimitSeconds} is an optional hard end (more than one left = draw).
 * Maps are ordinary map files whose team spawns sit on the pillar tops. Must stay in sync with the mod's
 * {@code LuckyPillarsSettings}.
 */
@Component
public class LuckyPillarsDefinition implements GameTypeDefinition {
    public static final String ID = "LUCKY_PILLARS";
    /** One block wide pillars over the void: no sliding unless a mode turns it on. */
    private static final boolean ALLOW_SLIDE = false;
    private static final String ROW = "物品";

    /** The default item table: [item, min, max, weight]. Blocks come most often (bridges), real weapons rarely. */
    private static final Object[][] DEFAULT_ITEMS = {
            {"minecraft:white_wool", 8, 16, 40},
            {"minecraft:oak_planks", 8, 16, 30},
            {"minecraft:cobblestone", 8, 16, 30},
            {"minecraft:sand", 4, 8, 8},
            {"minecraft:ladder", 4, 8, 10},
            {"minecraft:scaffolding", 4, 8, 6},
            {"minecraft:cobweb", 1, 2, 4},
            {"minecraft:wooden_sword", 1, 1, 10},
            {"minecraft:stone_sword", 1, 1, 10},
            {"minecraft:iron_sword", 1, 1, 6},
            {"minecraft:diamond_sword", 1, 1, 2},
            {"minecraft:iron_axe", 1, 1, 4},
            {"minecraft:stick[enchantments={knockback:2}]", 1, 1, 3},
            {"minecraft:bow", 1, 1, 6},
            {"minecraft:crossbow", 1, 1, 3},
            {"minecraft:arrow", 4, 12, 12},
            {"minecraft:fishing_rod", 1, 1, 6},
            {"minecraft:snowball", 4, 12, 12},
            {"minecraft:egg", 4, 12, 8},
            {"minecraft:wind_charge", 2, 4, 5},
            {"minecraft:shield", 1, 1, 4},
            {"minecraft:chainmail_chestplate", 1, 1, 4},
            {"minecraft:iron_helmet", 1, 1, 4},
            {"minecraft:iron_chestplate", 1, 1, 3},
            {"minecraft:iron_leggings", 1, 1, 3},
            {"minecraft:iron_boots", 1, 1, 4},
            {"minecraft:diamond_chestplate", 1, 1, 1},
            {"minecraft:water_bucket", 1, 1, 6},
            {"minecraft:lava_bucket", 1, 1, 3},
            {"minecraft:ender_pearl", 1, 2, 5},
            {"minecraft:tnt", 1, 3, 4},
            {"minecraft:flint_and_steel", 1, 1, 3},
            {"minecraft:cooked_beef", 2, 6, 10},
            {"minecraft:golden_apple", 1, 1, 5},
            {"minecraft:enchanted_golden_apple", 1, 1, 1},
            {"minecraft:totem_of_undying", 1, 1, 1},
            {"minecraft:potion[potion_contents={potion:\"minecraft:leaping\"}]", 1, 1, 3},
            {"blockland:usp", 1, 1, 4},
            {"blockland:pistol_ammo", 10, 20, 6},
            {"blockland:tommy", 1, 1, 2},
            {"blockland:smg_ammo", 15, 30, 3},
            {"blockland:ks3", 1, 1, 2},
            {"blockland:shotgun_ammo", 4, 8, 3},
            {"blockland:awp", 1, 1, 1},
            {"blockland:sniper_ammo", 4, 8, 1},
            {"blockland:frag_grenade", 1, 2, 4},
            {"blockland:adrenaline", 1, 1, 2},
    };

    private final ObjectMapper mapper;
    private final ItemSpecs items;

    public LuckyPillarsDefinition(ObjectMapper mapper, ItemSpecs items) {
        this.mapper = mapper;
        this.items = items;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "幸运高柱";
    }

    @Override
    public String description() {
        return "每人站在一根高柱顶端，每隔几秒获得一件随机物品，搭路、下柱或远程攻击，各自为战，最后存活的玩家获胜。";
    }

    @Override
    public ObjectNode defaultSettings() {
        ObjectNode node = mapper.createObjectNode();
        node.put("players", 16);
        node.putArray("mapIds");
        node.put("countdownSeconds", 10);
        node.put("endingSeconds", 8);
        node.put("itemIntervalSeconds", 8);
        node.put("itemsPerDrop", 1);
        node.set("items", WeightedLoot.of(mapper, DEFAULT_ITEMS));
        node.put("projectileKnockback", true);
        MovementSettings.putDefaults(node, ALLOW_SLIDE);
        return node;
    }

    @Override
    public ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors) {
        JsonNode in = raw == null || !raw.isObject() ? mapper.createObjectNode() : raw;
        ObjectNode out = defaultSettings();
        out.put("players", TeamArenaSettings.intField(in, "players", 16, 2, 64, "每局人数", errors));
        TeamArenaSettings.normalizeMapIds(in, out, ID, context, errors);
        out.put("countdownSeconds", TeamArenaSettings.intField(in, "countdownSeconds", 10, 0, 120, "开局倒计时", errors));
        out.put("endingSeconds", TeamArenaSettings.intField(in, "endingSeconds", 8, 1, 60, "结算时间", errors));
        out.put("itemIntervalSeconds", TeamArenaSettings.intField(in, "itemIntervalSeconds", 8, 1, 300, "发放间隔", errors));
        out.put("itemsPerDrop", TeamArenaSettings.intField(in, "itemsPerDrop", 1, 1, 8, "每次发放数量", errors));
        out.set("items", WeightedLoot.normalize(mapper, items, in.get("items"), WeightedLoot.of(mapper, DEFAULT_ITEMS), ROW, errors));
        out.put("projectileKnockback", TeamArenaSettings.boolField(in, "projectileKnockback", true));
        MovementSettings.normalizeInto(in, out, ALLOW_SLIDE);
        return out;
    }

    @Override
    public List<String> warnings(GameMode mode, Context context) {
        List<String> warnings = new ArrayList<>();
        JsonNode settings = mode.settings();
        int players = settings == null ? 16 : settings.path("players").asInt(16);
        List<GameMap> maps = TeamArenaSettings.mapPool(mode, ID, context);
        if (maps.isEmpty()) {
            warnings.add("当前没有任何幸运高柱地图，玩家无法匹配");
        } else {
            long playable = 0;
            for (GameMap map : maps) {
                long pillars = map.usableTeamSpawns();
                if (pillars >= players) {
                    playable++;
                } else {
                    warnings.add("地图「" + map.name() + "」只有 " + pillars + " 根高柱（出生点），需要 " + players + " 根");
                }
            }
            if (playable == 0) warnings.add(0, "当前没有任何地图满足该模式，玩家无法匹配");
        }
        JsonNode table = settings == null ? null : settings.get("items");
        if (table == null || !table.isArray() || table.isEmpty()) {
            warnings.add("物品表为空：玩家不会获得任何物品");
        } else {
            WeightedLoot.registryWarnings(items, table, ROW, warnings);
        }
        return warnings;
    }
}
