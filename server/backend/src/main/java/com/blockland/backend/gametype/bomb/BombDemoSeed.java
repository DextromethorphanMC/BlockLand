package com.blockland.backend.gametype.bomb;

import com.blockland.backend.gamemode.GameModeService;
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
 * 枪战爆破 on a generated demo yard ({@link BombDemoArena}, sites A and B): a 5v5 mode and a 1v1 one (two clients are
 * enough to try planting and defusing), in a lobby group of their own. The guns come with the mod, so the kit may name
 * items the connected server has not reported yet; the modes are created anyway and warn until the server knows them.
 */
@Component
@Order(66)
public class BombDemoSeed implements DemoSeed {
    static final String DEMO_MAP = "bomb_demo";
    static final String GROUP_ID = "bomb";
    private static final String DESCRIPTION = "进攻方携带炸药包在 A / B 包点安放，防守方阻止安放或在倒计时内拆除；炸弹爆炸则进攻方获胜。";

    private final GameModeService modes;
    private final ModeGroupService groups;
    private final MapService maps;
    private final BombDefinition definition;
    private final ObjectMapper mapper;

    public BombDemoSeed(GameModeService modes, ModeGroupService groups, MapService maps, BombDefinition definition, ObjectMapper mapper) {
        this.modes = modes;
        this.groups = groups;
        this.maps = maps;
        this.definition = definition;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "bomb-demo";
    }

    @Override
    public void apply() {
        if (!maps.exists(DEMO_MAP)) {
            BombDemoArena.Result arena = BombDemoArena.generate();
            GameMap map = maps.upload(DEMO_MAP, "示例爆破场", BombDefinition.ID, null, DEMO_MAP + ".nbt", arena.data());
            maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), arena.spawns(), -5,
                    BombMapSettings.toJson(mapper, arena.sites())));
            maps.setCover(DEMO_MAP, CoverArt.pistol(23));
        }
        mode("bomb_5v5", "枪战爆破 5v5", 5, 21);
        mode("bomb_1v1", "枪战爆破 1v1", 1, 22);

        List<String> ids = new ArrayList<>(List.of("bomb_5v5", "bomb_1v1").stream().filter(modes::exists).toList());
        if (ids.isEmpty()) return;
        if (!groups.exists(GROUP_ID)) {
            groups.create(new ModeGroup(GROUP_ID, "枪战爆破", "攻防对抗：安放炸药包，或守住包点并拆除炸弹。", ids, true, 3, null, null));
        }
        ModeGroup group = groups.get(GROUP_ID);
        if (group.coverVersion() == null) groups.setCover(GROUP_ID, CoverArt.pistol(24));
    }

    private void mode(String id, String title, int teamSize, long coverSeed) {
        if (!modes.exists(id)) {
            ObjectNode settings = definition.defaultSettings();
            settings.put("teamSize", teamSize);
            settings.put("countdownSeconds", 5);
            settings.put("endingSeconds", 6);
            // an empty pool means every 枪战爆破 map, in case an admin removed the demo map
            ArrayNode mapIds = settings.putArray("mapIds");
            if (maps.exists(DEMO_MAP) && BombDefinition.ID.equalsIgnoreCase(maps.get(DEMO_MAP).gameType())) mapIds.add(DEMO_MAP);
            modes.createAllowingUnknownItems(new GameModeService.Request(id, title, BombDefinition.ID, DESCRIPTION, true, 120, null,
                    settings, definition.defaultKit()));
        }
        if (modes.get(id).coverVersion() == null) modes.setCover(id, CoverArt.pistol(coverSeed));
    }
}
