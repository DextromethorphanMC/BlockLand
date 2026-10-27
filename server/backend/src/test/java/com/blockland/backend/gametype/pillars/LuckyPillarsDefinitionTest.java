package com.blockland.backend.gametype.pillars;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LuckyPillarsDefinitionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LuckyPillarsDefinition definition = new LuckyPillarsDefinition(mapper, new ItemSpecs(Mockito.mock(ItemRegistryService.class)));
    private final GameTypeDefinition.Context noMaps = new GameTypeDefinition.Context(List.of());

    @Test
    void missingSettingsBecomeDefaults() {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(null, noMaps, errors);
        assertThat(errors).isEmpty();
        assertThat(out).isEqualTo(definition.defaultSettings());
        assertThat(out.get("players").asInt()).isEqualTo(16);
        assertThat(out.get("itemIntervalSeconds").asInt()).isEqualTo(8);
        assertThat(out.get("itemsPerDrop").asInt()).isEqualTo(1);
        assertThat(out.get("items").size()).isGreaterThan(20);
        assertThat(out.get("allowSlide").asBoolean()).isFalse();
        assertThat(out.get("projectileKnockback").asBoolean()).isTrue();
    }

    @Test
    void defaultItemsAreValidRows() {
        List<String> errors = new ArrayList<>();
        definition.normalizeSettings(mapper.createObjectNode().set("items", definition.defaultSettings().get("items")), noMaps, errors);
        assertThat(errors).isEmpty();
    }

    @Test
    void clampsNumbersAndValidatesItems() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(mapper.readTree("""
                {"players": 1, "itemIntervalSeconds": 0, "itemsPerDrop": 20,
                 "items": [{"item": "minecraft:white_wool", "min": 8, "max": 16, "weight": 40},
                           {"item": "Not An Item", "min": 3, "max": 2, "weight": 0}]}"""), noMaps, errors);
        assertThat(out.get("players").asInt()).isEqualTo(2);
        assertThat(out.get("itemIntervalSeconds").asInt()).isEqualTo(1);
        assertThat(out.get("itemsPerDrop").asInt()).isEqualTo(8);
        assertThat(out.get("items")).hasSize(2);
        assertThat(errors).contains("每局人数 必须在 2 到 64 之间", "发放间隔 必须在 1 到 300 之间", "每次发放数量 必须在 1 到 8 之间",
                "物品第 2 项: 最少数量不能大于最多数量", "物品第 2 项: 权重 必须在 1 到 1000 之间");
        assertThat(errors).anyMatch(e -> e.startsWith("物品第 2 项: 物品格式错误"));
    }

    @Test
    void warnsAboutMissingMapsTooFewPillarsAndNoItems() {
        ObjectNode settings = definition.defaultSettings();
        settings.putArray("items");
        assertThat(definition.warnings(mode(settings), noMaps)).contains("当前没有任何幸运高柱地图，玩家无法匹配", "物品表为空：玩家不会获得任何物品");

        LuckyPillarsDemoMap.Result duel = LuckyPillarsDemoMap.generate(2, 7, 26, 1);
        GameMap small = new GameMap("duel", "双柱", LuckyPillarsDefinition.ID, "nbt", "duel.nbt", "", 0,
                new GameMap.Size(duel.sizeX(), duel.sizeY(), duel.sizeZ()), null, duel.spawns(), LuckyPillarsDemoMap.VOID_Y,
                mapper.createObjectNode(), null, Instant.now());
        assertThat(definition.warnings(mode(definition.defaultSettings()), new GameTypeDefinition.Context(List.of(small))))
                .containsExactly("当前没有任何地图满足该模式，玩家无法匹配", "地图「双柱」只有 2 根高柱（出生点），需要 16 根");
        ObjectNode two = definition.defaultSettings();
        two.put("players", 2);
        assertThat(definition.warnings(mode(two), new GameTypeDefinition.Context(List.of(small)))).isEmpty();
    }

    @Test
    void demoMapsSpawnEveryoneOnTopOfTheirOwnPillar() {
        for (int pillars : new int[]{2, 16}) {
            LuckyPillarsDemoMap.Result generated = LuckyPillarsDemoMap.generate(pillars, pillars == 2 ? 7 : 20, 30, 7);
            MapFileAnalyzer.Result analysis = new MapFileAnalyzer().analyze(generated.data(), "nbt", null);
            assertThat(analysis.size()).isEqualTo(new GameMap.Size(generated.sizeX(), generated.sizeY(), generated.sizeZ()));
            assertThat(generated.spawns()).hasSize(pillars);
            MapFileAnalyzer.TopView top = analysis.topView();
            List<GameMap.SpawnPoint> points = generated.spawns().stream().map(t -> t.points().get(0)).toList();
            for (GameMap.SpawnPoint p : points) {
                int column = (int) p.x() + (int) p.z() * top.width();
                assertThat(top.palette().get(top.cells()[column])).isEqualTo("minecraft:bedrock");
                assertThat(top.heights()[column]).isEqualTo(generated.pillarTop());
                assertThat((int) p.y()).isEqualTo(generated.pillarTop() + 1).isLessThan(generated.sizeY());
            }
            // neighbouring pillars are far enough apart that nobody starts in reach of another
            for (int i = 0; i < points.size(); i++) {
                GameMap.SpawnPoint a = points.get(i), b = points.get((i + 1) % points.size());
                assertThat(Math.hypot(a.x() - b.x(), a.z() - b.z())).isGreaterThan(6);
            }
        }
    }

    private static GameMode mode(ObjectNode settings) {
        return new GameMode("m", "m", LuckyPillarsDefinition.ID, "", true, null, null, settings, List.of(), null, Instant.now());
    }
}
