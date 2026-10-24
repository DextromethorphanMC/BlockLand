package com.blockland.backend.gametype.fastpvp;

import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RushDemoArenaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fastPvpCarriesTheRushSwitches() throws Exception {
        FastPvpDefinition definition = new FastPvpDefinition(mapper);
        List<String> errors = new ArrayList<>();
        ObjectNode defaults = definition.normalizeSettings(null, new GameTypeDefinition.Context(List.of()), errors);
        assertThat(defaults.get("infiniteBlocks").asBoolean()).isFalse();
        assertThat(defaults.get("knockbackOnly").asBoolean()).isFalse();
        ObjectNode rush = definition.normalizeSettings(mapper.readTree("""
                {"infiniteBlocks": true, "knockbackOnly": true}"""), new GameTypeDefinition.Context(List.of()), errors);
        assertThat(rush.get("infiniteBlocks").asBoolean()).isTrue();
        assertThat(rush.get("knockbackOnly").asBoolean()).isTrue();
        assertThat(errors).isEmpty();
    }

    @Test
    void twoPlatformsAcrossAVoidGap() {
        RushDemoArena.Result arena = RushDemoArena.generate();
        MapFileAnalyzer.Result result = new MapFileAnalyzer().analyze(arena.data(), "nbt", null);
        assertThat(result.size()).isEqualTo(new GameMap.Size(RushDemoArena.SIZE_X, RushDemoArena.SIZE_Y, RushDemoArena.SIZE_Z));
        assertThat(arena.spawns()).hasSize(2).allSatisfy(t -> assertThat(t.points()).isNotEmpty());
        MapFileAnalyzer.TopView top = result.topView();
        // spawns stand on the platforms; the middle row of the gap is open to the void
        arena.spawns().forEach(t -> t.points().forEach(p ->
                assertThat(top.heights()[(int) p.x() + (int) p.z() * top.width()]).isEqualTo((int) p.y() - 1)));
        int middle = RushDemoArena.SIZE_Z / 2;
        for (int x = 0; x < RushDemoArena.SIZE_X; x++) assertThat(top.cells()[x + middle * top.width()]).isNegative();
    }
}
