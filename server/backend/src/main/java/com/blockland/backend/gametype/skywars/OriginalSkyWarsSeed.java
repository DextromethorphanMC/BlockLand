package com.blockland.backend.gametype.skywars;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.map.MapService;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** A separate migration also reaches installations that already applied skywars-demo. */
@Component
@Order(11)
public class OriginalSkyWarsSeed implements DemoSeed {
    private final MapService maps;
    private final GameModeService modes;
    private final ObjectMapper mapper;

    public OriginalSkyWarsSeed(MapService maps, GameModeService modes, ObjectMapper mapper) {
        this.maps = maps;
        this.modes = modes;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "skywars-original-five-v1";
    }

    @Override
    public void apply() {
        var catalog = OriginalSkyWarsMaps.catalog();
        for (var design : catalog) {
            if (maps.exists(design.id())) continue;
            var arena = OriginalSkyWarsMaps.generate(design);
            var map = maps.upload(design.id(), design.name(), SkyWarsDefinition.ID, null, design.id() + ".nbt", arena.data());
            ObjectNode settings = mapper.valueToTree(new SkyWarsMapSettings(arena.chests(), "island"));
            maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), arena.spawns(), -10, settings));
        }
        // Extend only the shipped pools, leaving custom modes, existing maps and other settings alone.
        for (String id : List.of("skywars_1v1", "skywars_4v4")) {
            if (!modes.exists(id)) continue;
            var mode = modes.get(id);
            if (!SkyWarsDefinition.ID.equals(mode.type())) continue;
            var settings = mode.settings().deepCopy();
            var pool = settings.withArray("mapIds");
            // An empty pool already means all eligible maps; do not accidentally restrict it.
            if (pool.isEmpty()) continue;
            boolean changed = false;
            for (var design : catalog) {
                if (!SkyWarsDefinition.ID.equals(maps.get(design.id()).gameType())) continue;
                boolean present = false;
                for (var entry : pool) if (design.id().equals(entry.asText())) present = true;
                if (!present) {
                    pool.add(design.id());
                    changed = true;
                }
            }
            if (changed) modes.update(id, new GameModeService.Request(id, mode.title(), mode.type(), mode.description(),
                    mode.enabled(), mode.timeLimitSeconds(), mode.availableUntil() == null ? null : mode.availableUntil().toString(),
                    settings, mode.kit()));
        }
    }
}
