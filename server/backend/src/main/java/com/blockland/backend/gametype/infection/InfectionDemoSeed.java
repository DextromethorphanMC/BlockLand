package com.blockland.backend.gametype.infection;

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
 * 枪战感染 on a generated quarantine station map (with role spawn points), with a generated cover, in its own lobby
 * group 「枪战感染」 (it is a game type of its own, not a PVP 竞技 mode). Every step checks what already exists.
 * Installs that ran the first version are brought to the same state by {@link InfectionUpgradeSeed}.
 */
@Component
@Order(60)
public class InfectionDemoSeed implements DemoSeed {
    static final String MODE_ID = "infection_gun";
    static final String GROUP_ID = "infection";
    static final String DEMO_MAP = "infection_demo";
    static final String DESCRIPTION = "多回合对抗：人类持枪坚守到时间结束赢下回合，被怪物击杀即被感染加入怪物一方；赢得回合多的一方获胜。";

    private final GameModeService modes;
    private final ModeGroupService groups;
    private final MapService maps;
    private final InfectionDefinition definition;
    private final ObjectMapper mapper;

    public InfectionDemoSeed(GameModeService modes, ModeGroupService groups, MapService maps, InfectionDefinition definition,
                             ObjectMapper mapper) {
        this.modes = modes;
        this.groups = groups;
        this.maps = maps;
        this.definition = definition;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "infection-gun";
    }

    @Override
    public void apply() {
        if (!maps.exists(DEMO_MAP)) {
            InfectionDemoArena.Result arena = InfectionDemoArena.generate();
            GameMap map = maps.upload(DEMO_MAP, "示例检疫站", InfectionDefinition.ID, null, DEMO_MAP + ".nbt", arena.data());
            maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), arena.spawns(), -5,
                    InfectionMapSettings.toJson(mapper, arena.roleSpawns())));
        }
        if (!modes.exists(MODE_ID)) {
            ObjectNode settings = definition.defaultSettings();
            // two players so a match can be tested with two clients; admins raise it for real rounds
            settings.put("players", 2);
            // an empty pool means every 枪战感染 map, in case an admin removed the demo map
            ArrayNode mapIds = settings.putArray("mapIds");
            if (maps.exists(DEMO_MAP) && InfectionDefinition.ID.equalsIgnoreCase(maps.get(DEMO_MAP).gameType())) mapIds.add(DEMO_MAP);
            modes.createAllowingUnknownItems(new GameModeService.Request(MODE_ID, "枪战感染", InfectionDefinition.ID,
                    DESCRIPTION, true, InfectionDefinition.DEFAULT_TIME_LIMIT_SECONDS, null, settings, definition.defaultKit()));
        }
        if (modes.get(MODE_ID).coverVersion() == null) modes.setCover(MODE_ID, CoverArt.infection(11));
        ensureOwnGroup();
    }

    /** The lobby group 「枪战感染」 holding the demo mode (created with its cover when missing). */
    void ensureOwnGroup() {
        if (!modes.exists(MODE_ID)) return;
        if (!groups.exists(GROUP_ID)) {
            groups.create(new ModeGroup(GROUP_ID, "枪战感染", "人类与感染者的多回合生存对抗：坚守到时间结束，或感染所有人类。",
                    List.of(MODE_ID), true, 2, null, null));
        }
        ModeGroup group = groups.get(GROUP_ID);
        List<String> modeIds = new ArrayList<>(group.modeIds().stream().filter(modes::exists).toList());
        if (!modeIds.contains(MODE_ID)) {
            modeIds.add(MODE_ID);
            group = groups.update(GROUP_ID, group.withModeIds(modeIds));
        }
        if (group.coverVersion() == null) groups.setCover(GROUP_ID, CoverArt.infection(13));
    }

    /** Role spawn points of the generated demo map, as map settings. */
    ObjectNode demoMapSettings() {
        return InfectionMapSettings.toJson(mapper, InfectionDemoArena.generate().roleSpawns());
    }
}
