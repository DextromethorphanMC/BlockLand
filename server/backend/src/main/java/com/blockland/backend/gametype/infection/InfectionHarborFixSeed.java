package com.blockland.backend.gametype.infection;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapAmbient;
import com.blockland.backend.map.MapService;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The harbour stored before it stood on a layer of stone (in the arena its lake's sand floor fell into the void and the
 * lake drained): it gets the corrected file, with its spawns one block higher to match. A fresh install already has the
 * corrected file and nothing happens.
 */
@Component
@Order(63)
public class InfectionHarborFixSeed implements DemoSeed {
    private final MapService maps;
    private final ObjectMapper mapper;

    public InfectionHarborFixSeed(MapService maps, ObjectMapper mapper) {
        this.maps = maps;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "infection-harbor-void-fix-v1";
    }

    @Override
    public void apply() {
        String id = InfectionHarborSeed.MAP_ID;
        if (!maps.exists(id) || !InfectionDefinition.ID.equalsIgnoreCase(maps.get(id).gameType())) return;
        InfectionHarborArena.Result arena = InfectionHarborArena.generate();
        if (!maps.replaceGeneratedFile(id, id + ".nbt", arena.data())) return;
        GameMap map = maps.get(id);
        ObjectNode settings = InfectionMapSettings.toJson(mapper, arena.roleSpawns());
        MapAmbient.attach(settings, MapAmbient.of(map.settings()));
        maps.update(id, new MapService.UpdateRequest(map.name(), map.gameType(), arena.spawns(), map.voidY(), settings));
    }
}
