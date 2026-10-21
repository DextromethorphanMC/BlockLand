package com.blockland.backend.gametype.fastpvp;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.kit.KitItem;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapService;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** ClassPVP 1v1 (iron armour + iron sword) on a generated arena map. */
@Component
@Order(20)
public class FastPvpDemoSeed implements DemoSeed {
    private static final String DEMO_MAP = "demo_arena";

    private final GameModeService modes;
    private final MapService maps;
    private final ObjectMapper mapper;

    public FastPvpDemoSeed(GameModeService modes, MapService maps, ObjectMapper mapper) {
        this.modes = modes;
        this.maps = maps;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "fastpvp-classpvp";
    }

    @Override
    public void apply() {
        if (!maps.exists(DEMO_MAP)) {
            FastPvpDemoArena.Result arena = FastPvpDemoArena.generate();
            GameMap map = maps.upload(DEMO_MAP, "示例竞技场", FastPvpDefinition.ID, null, DEMO_MAP + ".nbt", arena.data());
            maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), arena.spawns(), -5, null));
        }
        if (!modes.exists("classpvp_1v1")) {
            ObjectNode settings = mapper.createObjectNode();
            settings.put("teamSize", 1);
            settings.put("teamCount", 2);
            settings.putArray("mapIds").add(DEMO_MAP);
            settings.put("countdownSeconds", 5);
            settings.put("endingSeconds", 5);
            settings.put("allowBlockBreak", false);
            settings.put("allowBlockPlace", false);
            List<KitItem> kit = List.of(
                    new KitItem("minecraft:iron_helmet", 1, "head"),
                    new KitItem("minecraft:iron_chestplate", 1, "chest"),
                    new KitItem("minecraft:iron_leggings", 1, "legs"),
                    new KitItem("minecraft:iron_boots", 1, "feet"),
                    new KitItem("minecraft:iron_sword", 1, "0"));
            modes.create(new GameModeService.Request("classpvp_1v1", "ClassPVP", FastPvpDefinition.ID,
                    "经典 1v1 对决：铁套装备 + 铁剑。", true, 300, settings, kit));
        }
    }
}
