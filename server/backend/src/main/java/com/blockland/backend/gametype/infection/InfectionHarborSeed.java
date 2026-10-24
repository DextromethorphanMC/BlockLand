package com.blockland.backend.gametype.infection;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapService;
import com.blockland.backend.seed.CoverArt;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The generated harbour map ({@link InfectionHarborArena}: a lake, a pier, a glass tunnel under the water, a lighthouse)
 * for 枪战感染, added to the demo mode's map pool — a place to see the client's water, shadows and lights. Runs once; a
 * pool that is empty (every 枪战感染 map) already includes it.
 */
@Component
@Order(62)
public class InfectionHarborSeed implements DemoSeed {
    private static final Logger LOG = LoggerFactory.getLogger(InfectionHarborSeed.class);
    static final String MAP_ID = "infection_harbor";

    private final GameModeService modes;
    private final MapService maps;
    private final ObjectMapper mapper;

    public InfectionHarborSeed(GameModeService modes, MapService maps, ObjectMapper mapper) {
        this.modes = modes;
        this.maps = maps;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "infection-harbor";
    }

    @Override
    public void apply() {
        if (!maps.exists(MAP_ID)) {
            InfectionHarborArena.Result arena = InfectionHarborArena.generate();
            GameMap map = maps.upload(MAP_ID, "示例港湾", InfectionDefinition.ID, null, MAP_ID + ".nbt", arena.data());
            maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), arena.spawns(), -5,
                    InfectionMapSettings.toJson(mapper, arena.roleSpawns())));
            maps.setCover(MAP_ID, CoverArt.infection(29));
        }
        if (modes.exists(InfectionDemoSeed.MODE_ID)) addToPool(modes.get(InfectionDemoSeed.MODE_ID));
    }

    private void addToPool(GameMode mode) {
        ObjectNode settings = mode.settings() == null ? null : mode.settings().deepCopy();
        if (settings == null || !settings.has("mapIds")) return;
        ArrayNode pool = (ArrayNode) settings.get("mapIds");
        if (pool.isEmpty()) return;
        for (JsonNode id : pool) if (MAP_ID.equals(id.asText())) return;
        pool.add(MAP_ID);
        try {
            modes.update(mode.id(), new GameModeService.Request(mode.id(), mode.title(), mode.type(), mode.description(), mode.enabled(),
                    mode.timeLimitSeconds(), mode.availableUntil() == null ? null : mode.availableUntil().toString(), settings, mode.kit()));
        } catch (ApiException e) {
            LOG.warn("Could not add {} to mode {}: {} {}", MAP_ID, mode.id(), e.getMessage(), e.details());
        }
    }
}
