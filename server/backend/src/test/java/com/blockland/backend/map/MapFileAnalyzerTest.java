package com.blockland.backend.map;

import com.blockland.backend.gametype.fastpvp.FastPvpDemoArena;
import com.blockland.backend.gametype.skywars.SkyWarsDemoMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MapFileAnalyzerTest {
    private final MapFileAnalyzer analyzer = new MapFileAnalyzer();

    @Test
    void analyzesGeneratedSkyWarsMap() {
        SkyWarsDemoMap.Result demo = SkyWarsDemoMap.generate();
        MapFileAnalyzer.Result result = analyzer.analyze(demo.data(), "nbt", "minecraft:gold_block");

        assertThat(result.size()).isEqualTo(new GameMap.Size(SkyWarsDemoMap.SIZE_X, SkyWarsDemoMap.SIZE_Y, SkyWarsDemoMap.SIZE_Z));
        assertThat(result.containers()).hasSize(SkyWarsDemoMap.ISLANDS + 4);
        assertThat(result.markers()).hasSize(1);
        assertThat(result.topView().palette()).contains("minecraft:grass_block");
        // every configured chest coordinate really is a container in the file
        List<MapFileAnalyzer.Block> configured = demo.chests().stream()
                .map(c -> new MapFileAnalyzer.Block(c.x(), c.y(), c.z())).toList();
        assertThat(result.containers()).containsExactlyInAnyOrderElementsOf(configured);
    }

    @Test
    void analyzesGeneratedArena() {
        MapFileAnalyzer.Result result = analyzer.analyze(FastPvpDemoArena.generate().data(), "nbt", null);
        assertThat(result.size()).isEqualTo(new GameMap.Size(FastPvpDemoArena.SIZE_X, FastPvpDemoArena.SIZE_Y, FastPvpDemoArena.SIZE_Z));
        assertThat(result.containers()).isEmpty();
        assertThat(result.topView().palette()).contains("minecraft:smooth_stone", "minecraft:stone_bricks");
    }

    @Test
    void clustersNearbyMarkersIntoTeams() {
        List<MapFileAnalyzer.Block> markers = List.of(
                new MapFileAnalyzer.Block(10, 5, 10), new MapFileAnalyzer.Block(12, 5, 10),
                new MapFileAnalyzer.Block(80, 5, 80), new MapFileAnalyzer.Block(81, 5, 82));
        List<GameMap.TeamSpawn> spawns = MapFileAnalyzer.clusterMarkers(markers, new GameMap.Size(90, 20, 90));
        assertThat(spawns).hasSize(2);
        assertThat(spawns).allSatisfy(s -> assertThat(s.points()).hasSize(2));
    }
}
