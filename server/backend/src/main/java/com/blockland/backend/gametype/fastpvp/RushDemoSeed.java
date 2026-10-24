package com.blockland.backend.gametype.fastpvp;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.kit.KitItem;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapService;
import com.blockland.backend.modegroup.ModeGroup;
import com.blockland.backend.modegroup.ModeGroupService;
import com.blockland.backend.seed.CoverArt;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Rush 1v1 (FastPVP): a knockback X stick and wool that never runs out on {@link RushDemoArena}; hits only knock back,
 * so the only way to lose is the void. Added to the "PVP 竞技" group (also when that group was seeded long ago).
 */
@Component
@Order(69)
public class RushDemoSeed implements DemoSeed {
    static final String DEMO_MAP = "rush_arena";
    static final String MODE_ID = "rush_1v1";
    private static final String GROUP_ID = "pvp";

    private final GameModeService modes;
    private final ModeGroupService groups;
    private final MapService maps;
    private final FastPvpDefinition definition;
    private final ObjectMapper mapper;

    public RushDemoSeed(GameModeService modes, ModeGroupService groups, MapService maps, FastPvpDefinition definition, ObjectMapper mapper) {
        this.modes = modes;
        this.groups = groups;
        this.maps = maps;
        this.definition = definition;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "fastpvp-rush";
    }

    @Override
    public void apply() {
        if (!maps.exists(DEMO_MAP)) {
            RushDemoArena.Result arena = RushDemoArena.generate();
            GameMap map = maps.upload(DEMO_MAP, "Rush 双岛", FastPvpDefinition.ID, null, DEMO_MAP + ".nbt", arena.data());
            maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), arena.spawns(), RushDemoArena.VOID_Y, null));
            maps.setCover(DEMO_MAP, CoverArt.bridge(51));
        }
        if (!modes.exists(MODE_ID)) {
            ObjectNode settings = definition.defaultSettings();
            settings.put("teamSize", 1);
            settings.put("teamCount", 2);
            ArrayNode mapIds = settings.putArray("mapIds");
            if (maps.exists(DEMO_MAP) && FastPvpDefinition.ID.equalsIgnoreCase(maps.get(DEMO_MAP).gameType())) mapIds.add(DEMO_MAP);
            settings.put("countdownSeconds", 5);
            settings.put("endingSeconds", 5);
            settings.put("allowBlockBreak", false);
            settings.put("allowBlockPlace", true);
            settings.put("infiniteBlocks", true);
            settings.put("knockbackOnly", true);
            settings.put("allowSlide", false);
            List<KitItem> kit = List.of(
                    new KitItem("minecraft:stick[enchantments={knockback:10}]", 1, "0"),
                    new KitItem("minecraft:white_wool", 64, "1"),
                    new KitItem("minecraft:white_wool", 64, "2"));
            modes.create(new GameModeService.Request(MODE_ID, "Rush", FastPvpDefinition.ID,
                    "击退 X 木棒 + 无限羊毛：搭桥冲向对面，把对手打进虚空。不会掉血，掉下去就输。", true, 300, null, settings, kit));
        }
        if (modes.get(MODE_ID).coverVersion() == null) modes.setCover(MODE_ID, CoverArt.bridge(52));

        if (groups.exists(GROUP_ID)) {
            ModeGroup pvp = groups.get(GROUP_ID);
            List<String> modeIds = new ArrayList<>(pvp.modeIds().stream().filter(modes::exists).toList());
            if (!modeIds.contains(MODE_ID)) {
                modeIds.add(MODE_ID);
                groups.update(GROUP_ID, pvp.withModeIds(modeIds));
            }
        }
    }
}
