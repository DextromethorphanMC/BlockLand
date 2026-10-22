package com.blockland.backend.gametype.skywars;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.gametype.skywars.loot.LootTable;
import com.blockland.backend.gametype.skywars.loot.LootTable.LootEntry;
import com.blockland.backend.gametype.skywars.loot.LootTableService;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapService;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 空岛战争 1v1 / 4v4, the island/center loot tables and a generated 8-island map. */
@Component
@Order(10)
public class SkyWarsDemoSeed implements DemoSeed {
    private static final String DEMO_MAP = "demo_islands";

    private final GameModeService modes;
    private final MapService maps;
    private final LootTableService lootTables;
    private final ObjectMapper mapper;

    public SkyWarsDemoSeed(GameModeService modes, MapService maps, LootTableService lootTables, ObjectMapper mapper) {
        this.modes = modes;
        this.maps = maps;
        this.lootTables = lootTables;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "skywars-demo";
    }

    @Override
    public void apply() {
        if (!lootTables.exists("island")) lootTables.create(new LootTable("island", "岛屿箱", 4, 9, List.of(
                new LootEntry("minecraft:stone", 16, 32, 70),
                new LootEntry("minecraft:oak_planks", 16, 32, 60),
                new LootEntry("minecraft:stone_sword", 1, 1, 50),
                new LootEntry("minecraft:iron_sword", 1, 1, 30),
                new LootEntry("minecraft:iron_helmet", 1, 1, 25),
                new LootEntry("minecraft:iron_chestplate", 1, 1, 25),
                new LootEntry("minecraft:leather_leggings", 1, 1, 40),
                new LootEntry("minecraft:iron_pickaxe", 1, 1, 30),
                new LootEntry("minecraft:bow", 1, 1, 20),
                new LootEntry("minecraft:arrow", 4, 12, 35),
                new LootEntry("minecraft:snowball", 4, 12, 40),
                new LootEntry("minecraft:egg", 4, 12, 25),
                new LootEntry("minecraft:cooked_beef", 3, 8, 60),
                new LootEntry("minecraft:water_bucket", 1, 1, 25),
                new LootEntry("minecraft:golden_apple", 1, 1, 10)), null));
        if (!lootTables.exists("center")) lootTables.create(new LootTable("center", "中心箱", 5, 10, List.of(
                new LootEntry("minecraft:diamond_sword", 1, 1, 25),
                new LootEntry("minecraft:iron_sword[enchantments={sharpness:2}]", 1, 1, 30),
                new LootEntry("minecraft:diamond_helmet", 1, 1, 20),
                new LootEntry("minecraft:diamond_chestplate", 1, 1, 20),
                new LootEntry("minecraft:bow[enchantments={power:2}]", 1, 1, 25),
                new LootEntry("minecraft:arrow", 8, 16, 50),
                new LootEntry("minecraft:golden_apple", 1, 2, 35),
                new LootEntry("minecraft:ender_pearl", 1, 2, 30),
                new LootEntry("minecraft:lava_bucket", 1, 1, 20),
                new LootEntry("minecraft:tnt", 2, 4, 20),
                new LootEntry("minecraft:flint_and_steel", 1, 1, 20),
                new LootEntry("minecraft:experience_bottle", 4, 8, 30),
                new LootEntry("minecraft:cobblestone", 32, 64, 50)), null));

        if (!maps.exists(DEMO_MAP)) {
            SkyWarsDemoMap.Result demo = SkyWarsDemoMap.generate();
            GameMap map = maps.upload(DEMO_MAP, "示例空岛（8 岛）", SkyWarsDefinition.ID, null, DEMO_MAP + ".nbt", demo.data());
            ObjectNode settings = mapper.valueToTree(new SkyWarsMapSettings(demo.chests(), "island"));
            maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), demo.spawns(), -10, settings));
        }

        if (!modes.exists("skywars_1v1")) modes.create(new GameModeService.Request("skywars_1v1", "空岛战争 1v1", SkyWarsDefinition.ID,
                "经典单挑，两名玩家各占一座空岛。", true, 600, null, settings(1, 2, 180), List.of()));
        if (!modes.exists("skywars_4v4")) modes.create(new GameModeService.Request("skywars_4v4", "空岛战争 4v4", SkyWarsDefinition.ID,
                "四人组队对抗，团队配合抢占中心资源。", true, 900, null, settings(4, 2, 240), List.of()));
    }

    private ObjectNode settings(int teamSize, int teamCount, int refill) {
        ObjectNode settings = mapper.createObjectNode();
        settings.put("teamSize", teamSize);
        settings.put("teamCount", teamCount);
        settings.putArray("mapIds").add(DEMO_MAP);
        settings.put("countdownSeconds", 10);
        settings.put("chestRefillSeconds", refill);
        settings.put("endingSeconds", 8);
        return settings;
    }
}
