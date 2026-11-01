package com.blockland.backend.map;

import com.blockland.backend.gametype.battleroyale.BattleRoyaleDemoIsland;
import com.blockland.backend.gametype.bomb.BombDemoArena;
import com.blockland.backend.gametype.bomb.DustAArena;
import com.blockland.backend.gametype.fastpvp.FastPvpDemoArena;
import com.blockland.backend.gametype.fastpvp.RushDemoArena;
import com.blockland.backend.gametype.infection.InfectionDemoArena;
import com.blockland.backend.gametype.infection.InfectionHarborArena;
import com.blockland.backend.gametype.pillars.LuckyPillarsDemoMap;
import com.blockland.backend.gametype.skywars.OriginalSkyWarsMaps;
import com.blockland.backend.gametype.skywars.SkyWarsDemoMap;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every map the backend generates, as the arena will hold it: nothing falls into the void and no water or lava pours
 * out into it ({@link StructureSupport}). A new generator belongs in {@link #maps()}.
 */
class GeneratedMapsTest {
    static Map<String, byte[]> maps() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put("bomb_demo", BombDemoArena.generate().data());
        out.put("dust_a", DustAArena.generate().data());
        out.put("br_island", BattleRoyaleDemoIsland.generate().data());
        out.put("demo_arena", FastPvpDemoArena.generate().data());
        out.put("rush_arena", RushDemoArena.generate().data());
        out.put("infection_demo", InfectionDemoArena.generate().data());
        out.put("infection_harbor", InfectionHarborArena.generate().data());
        out.put("pillars_duel", LuckyPillarsDemoMap.generate(2, 7, 26, 51).data());
        out.put("pillars_ring16", LuckyPillarsDemoMap.generate(16, 20, 32, 53).data());
        out.put("demo_islands", SkyWarsDemoMap.generate().data());
        for (var design : OriginalSkyWarsMaps.catalog()) out.put(design.id(), OriginalSkyWarsMaps.generate(design).data());
        return out;
    }

    @Test
    void nothingFallsOrPoursIntoTheVoid() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> map : maps().entrySet()) {
            List<String> problems = StructureSupport.problems(map.getValue());
            counts.put(map.getKey(), problems.size());
            if (!problems.isEmpty()) examples.put(map.getKey(), problems.subList(0, Math.min(5, problems.size())));
        }
        System.out.println("Generated maps, blocks that would fall or pour into the void: " + counts);
        assertThat(examples).as("maps with blocks over the void").isEmpty();
    }
}
