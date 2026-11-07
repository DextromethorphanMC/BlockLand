package com.blockland.backend.gametype.skywars;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.map.MapService;
import com.blockland.backend.map.ShippedMaps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "blockland.seed-demo-data=false")
class OriginalSkyWarsSeedTest {
    @TempDir static Path data;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("blockland.data-dir", () -> data.toString());
    }

    @Autowired SkyWarsDemoSeed demo;
    @Autowired OriginalSkyWarsSeed originals;
    @Autowired MapService maps;
    @Autowired ShippedMaps shipped;
    @Autowired GameModeService modes;

    @Test
    void upgradesExistingPoolsWithoutReplacingMapsAndIsIdempotent() throws Exception {
        demo.apply();
        var baseline = maps.get("demo_islands");
        byte[] baselineBytes = Files.readAllBytes(maps.file(baseline.id()));
        assertThat(maps.list()).hasSize(1);
        originals.apply();
        assertThat(maps.list()).hasSize(6);
        assertThat(maps.get("demo_islands")).isEqualTo(baseline);
        assertThat(Files.readAllBytes(maps.file(baseline.id()))).isEqualTo(baselineBytes);
        for (String id : OriginalSkyWarsSeed.MAPS) {
            var map = maps.get(id);
            assertThat(map.teamSpawns()).hasSize(8);
            assertThat(map.settings().path("chests").size()).isEqualTo(36);
            assertThat(Files.readAllBytes(maps.file(map.id()))).isEqualTo(shipped.get(id).data());
        }
        for (String id : new String[]{"skywars_1v1", "skywars_4v4"}) {
            assertThat(modes.get(id).settings().path("mapIds").size()).isEqualTo(6);
            assertThat(modes.warnings(modes.get(id))).isEmpty();
        }
        var beforeMaps = maps.list();
        var beforeModes = modes.list();
        originals.apply();
        assertThat(maps.list()).isEqualTo(beforeMaps);
        assertThat(modes.list()).isEqualTo(beforeModes);
        assertThat(originals.id()).isNotEqualTo(demo.id());
        System.out.println("seed: existing demo unchanged; 5 maps added; both pools=6; repeat unchanged PASS");
    }
}
