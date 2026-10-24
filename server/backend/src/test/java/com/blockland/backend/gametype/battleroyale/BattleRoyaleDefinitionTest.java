package com.blockland.backend.gametype.battleroyale;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.blockland.backend.registry.ItemRegistryService;
import com.blockland.backend.registry.ItemSpecs;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BattleRoyaleDefinitionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BattleRoyaleDefinition definition = new BattleRoyaleDefinition(mapper, new ItemSpecs(Mockito.mock(ItemRegistryService.class)));
    private final GameTypeDefinition.Context noMaps = new GameTypeDefinition.Context(List.of());

    @Test
    void missingSettingsBecomeDefaults() {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(null, noMaps, errors);
        assertThat(errors).isEmpty();
        assertThat(out).isEqualTo(definition.defaultSettings());
        assertThat(out.get("players").asInt()).isEqualTo(16);
        assertThat(out.get("planeHeight").asInt()).isEqualTo(45);
        assertThat(out.get("autoDeployHeight").asInt()).isEqualTo(18);
        assertThat(out.get("zoneEnabled").asBoolean()).isTrue();
        assertThat(out.get("lootPerSpot").get("max").asInt()).isEqualTo(3);
        assertThat(out.get("loot").size()).isGreaterThan(10);
        assertThat(out.get("allowSlide").asBoolean()).isTrue();
    }

    @Test
    void clampsNumbersAndValidatesLoot() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(mapper.readTree("""
                {"players": 1, "planeSpeed": 99, "lootPerSpot": {"min": 4, "max": 2},
                 "loot": [{"item": "blockland:ak47", "min": 1, "max": 1, "weight": 5},
                          {"item": "Not An Item", "min": 3, "max": 2, "weight": 0}]}"""), noMaps, errors);
        assertThat(out.get("players").asInt()).isEqualTo(2);
        assertThat(out.get("planeSpeed").asDouble()).isEqualTo(40);
        assertThat(out.get("lootPerSpot").get("min").asInt()).isEqualTo(2);
        assertThat(out.get("loot")).hasSize(2);
        assertThat(errors).contains("每局人数 必须在 2 到 64 之间", "飞机速度 必须在 4 到 40 之间", "每个物资点最少物品数不能大于最多物品数",
                "物资第 2 项: 最少数量不能大于最多数量", "物资第 2 项: 权重 必须在 1 到 1000 之间");
        assertThat(errors).anyMatch(e -> e.startsWith("物资第 2 项: 物品格式错误"));
    }

    @Test
    void warnsAboutMissingMapsAndEmptyLoot() throws Exception {
        ObjectNode settings = definition.defaultSettings();
        settings.putArray("loot");
        GameMode mode = new GameMode("m", "m", BattleRoyaleDefinition.ID, "", true, null, null, settings, List.of(), null, Instant.now());
        assertThat(definition.warnings(mode, noMaps)).contains("当前没有任何大逃杀地图，玩家无法匹配", "物资表为空：岛上不会刷出任何物品");

        GameMap outside = new GameMap("a", "越界", BattleRoyaleDefinition.ID, "nbt", "a.nbt", "", 0, new GameMap.Size(10, 5, 10), null,
                List.of(), -5, BattleRoyaleMapSettings.toJson(mapper, List.of(new BattleRoyaleMapSettings.Spot(12, 1, 1))), null, Instant.now());
        GameMode good = new GameMode("m", "m", BattleRoyaleDefinition.ID, "", true, null, null, definition.defaultSettings(), List.of(),
                null, Instant.now());
        assertThat(definition.warnings(good, new GameTypeDefinition.Context(List.of(outside))))
                .containsExactly("地图「越界」有 1 个物资点超出了地图范围");
    }

    @Test
    void mapSettingsRejectDuplicatesAndFractions() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = BattleRoyaleMapSettings.normalize(mapper, mapper.readTree("""
                {"lootSpots": [{"x": 1, "y": 2, "z": 3}, {"x": 1, "y": 2, "z": 3}, {"x": 1.5, "y": 2, "z": 3}]}"""), errors);
        assertThat(BattleRoyaleMapSettings.read(out)).containsExactly(new BattleRoyaleMapSettings.Spot(1, 2, 3));
        assertThat(errors).containsExactly("物资点坐标重复: (1, 2, 3)", "物资点 3: 坐标必须是整数");
    }

    @Test
    void demoIslandHasLootOnTheGroundInsideTheMap() {
        BattleRoyaleDemoIsland.Result island = BattleRoyaleDemoIsland.generate();
        MapFileAnalyzer.Result result = new MapFileAnalyzer().analyze(island.data(), "nbt", null);
        GameMap.Size size = new GameMap.Size(BattleRoyaleDemoIsland.SIZE_X, BattleRoyaleDemoIsland.SIZE_Y, BattleRoyaleDemoIsland.SIZE_Z);
        assertThat(result.size()).isEqualTo(size);
        assertThat(island.lootSpots()).hasSizeBetween(50, 80);
        assertThat(new HashSet<>(island.lootSpots())).hasSameSizeAs(island.lootSpots());
        assertThat(island.lootSpots()).allSatisfy(s -> assertThat(s.inside(size)).isTrue());
        assertThat(island.lootSpots()).allSatisfy(s -> assertThat(s.y()).isGreaterThan(BattleRoyaleDemoIsland.SEA));
        List<String> errors = new ArrayList<>();
        BattleRoyaleMapSettings.normalize(mapper, BattleRoyaleMapSettings.toJson(mapper, island.lootSpots()), errors);
        assertThat(errors).isEmpty();
    }
}
