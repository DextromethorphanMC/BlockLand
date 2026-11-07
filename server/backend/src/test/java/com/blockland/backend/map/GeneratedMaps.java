package com.blockland.backend.map;

import com.blockland.backend.gametype.battleroyale.BattleRoyaleDefinition;
import com.blockland.backend.gametype.battleroyale.BattleRoyaleDemoIsland;
import com.blockland.backend.gametype.battleroyale.BattleRoyaleMapSettings;
import com.blockland.backend.gametype.bomb.BombDefinition;
import com.blockland.backend.gametype.bomb.BombDemoArena;
import com.blockland.backend.gametype.bomb.BombMapSettings;
import com.blockland.backend.gametype.bomb.DustAArena;
import com.blockland.backend.gametype.fastpvp.FastPvpDefinition;
import com.blockland.backend.gametype.fastpvp.FastPvpDemoArena;
import com.blockland.backend.gametype.fastpvp.RushDemoArena;
import com.blockland.backend.gametype.infection.InfectionDefinition;
import com.blockland.backend.gametype.infection.InfectionDemoArena;
import com.blockland.backend.gametype.infection.InfectionHarborArena;
import com.blockland.backend.gametype.infection.InfectionMapSettings;
import com.blockland.backend.gametype.pillars.LuckyPillarsDefinition;
import com.blockland.backend.gametype.pillars.LuckyPillarsDemoMap;
import com.blockland.backend.gametype.skywars.OriginalSkyWarsMaps;
import com.blockland.backend.gametype.skywars.SkyWarsDefinition;
import com.blockland.backend.gametype.skywars.SkyWarsDemoMap;
import com.blockland.backend.gametype.skywars.SkyWarsMapSettings;
import com.blockland.backend.seed.CoverArt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The generators the shipped maps came from ({@link ShippedMaps}). They are tools, not part of the backend: a map is
 * whatever its shipped file says, and a changed generator reaches it only when {@link GeneratedMapsTest} exports it
 * ({@code tools/maps/MAPS.md}). Each recipe gives what the map's seed stored when it still generated the map.
 */
final class GeneratedMaps {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GeneratedMaps() {
    }

    /**
     * A generated map.
     *
     * @param settings the game type's map settings (null = the type's defaults); an export keeps the map's other keys
     *                 (its ambience) as they are
     * @param cover    the cover a fresh map gets (null = none); an export never replaces a cover the map already has
     */
    record Generated(String name, String gameType, int voidY, byte[] data, List<GameMap.TeamSpawn> spawns, ObjectNode settings,
                     byte[] cover) {
    }

    /** Every generated map by id; each generates only when asked. */
    static Map<String, Supplier<Generated>> recipes() {
        Map<String, Supplier<Generated>> out = new LinkedHashMap<>();
        out.put("demo_islands", () -> {
            SkyWarsDemoMap.Result map = SkyWarsDemoMap.generate();
            return new Generated("示例空岛（8 岛）", SkyWarsDefinition.ID, -10, map.data(), map.spawns(),
                    MAPPER.valueToTree(new SkyWarsMapSettings(map.chests(), "island")), null);
        });
        for (OriginalSkyWarsMaps.Design design : OriginalSkyWarsMaps.catalog()) {
            out.put(design.id(), () -> {
                OriginalSkyWarsMaps.Result map = OriginalSkyWarsMaps.generate(design);
                return new Generated(design.name(), SkyWarsDefinition.ID, -10, map.data(), map.spawns(),
                        MAPPER.valueToTree(new SkyWarsMapSettings(map.chests(), "island")), null);
            });
        }
        out.put("demo_arena", () -> {
            FastPvpDemoArena.Result map = FastPvpDemoArena.generate();
            return new Generated("示例竞技场", FastPvpDefinition.ID, -5, map.data(), map.spawns(), null, null);
        });
        out.put("rush_arena", () -> {
            RushDemoArena.Result map = RushDemoArena.generate();
            return new Generated("Rush 双岛", FastPvpDefinition.ID, RushDemoArena.VOID_Y, map.data(), map.spawns(), null,
                    CoverArt.bridge(51));
        });
        out.put("infection_demo", () -> {
            InfectionDemoArena.Result map = InfectionDemoArena.generate();
            return new Generated("示例检疫站", InfectionDefinition.ID, -5, map.data(), map.spawns(),
                    InfectionMapSettings.toJson(MAPPER, map.roleSpawns()), null);
        });
        out.put("infection_harbor", () -> {
            InfectionHarborArena.Result map = InfectionHarborArena.generate();
            return new Generated("示例港湾", InfectionDefinition.ID, -5, map.data(), map.spawns(),
                    InfectionMapSettings.toJson(MAPPER, map.roleSpawns()), CoverArt.infection(29));
        });
        out.put("bomb_demo", () -> {
            BombDemoArena.Result map = BombDemoArena.generate();
            return new Generated("示例爆破场", BombDefinition.ID, -5, map.data(), map.spawns(),
                    BombMapSettings.toJson(MAPPER, map.sites()), CoverArt.pistol(23));
        });
        out.put("dust_a", () -> {
            DustAArena.Result map = DustAArena.generate();
            ObjectNode settings = BombMapSettings.toJson(MAPPER, map.sites());
            settings.putObject(MapAmbient.KEY).put(MapAmbient.TUMBLEWEEDS, true);
            return new Generated("沙漠小镇 DustA", BombDefinition.ID, -5, map.data(), map.spawns(), settings,
                    CoverArt.desertTown(31));
        });
        out.put("br_island", () -> {
            BattleRoyaleDemoIsland.Result map = BattleRoyaleDemoIsland.generate();
            return new Generated("示例岛屿", BattleRoyaleDefinition.ID, -8, map.data(), List.of(),
                    BattleRoyaleMapSettings.toJson(MAPPER, map.lootSpots()), CoverArt.islands(false, 41));
        });
        out.put("pillars_duel", () -> {
            LuckyPillarsDemoMap.Result map = LuckyPillarsDemoMap.generate(2, 7, 26, 51);
            return new Generated("双柱对决", LuckyPillarsDefinition.ID, LuckyPillarsDemoMap.VOID_Y, map.data(), map.spawns(), null,
                    CoverArt.pillars(false, 52));
        });
        out.put("pillars_ring16", () -> {
            LuckyPillarsDemoMap.Result map = LuckyPillarsDemoMap.generate(16, 20, 32, 53);
            return new Generated("十六高柱", LuckyPillarsDefinition.ID, LuckyPillarsDemoMap.VOID_Y, map.data(), map.spawns(), null,
                    CoverArt.pillars(true, 54));
        });
        return out;
    }
}
