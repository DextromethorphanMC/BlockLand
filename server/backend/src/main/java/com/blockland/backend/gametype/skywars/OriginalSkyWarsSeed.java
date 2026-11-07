package com.blockland.backend.gametype.skywars;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.map.MapService;
import com.blockland.backend.map.ShippedMaps;
import com.blockland.backend.seed.DemoSeed;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** A separate migration also reaches installations that already applied skywars-demo. */
@Component
@Order(11)
public class OriginalSkyWarsSeed implements DemoSeed {
    /** The five original maps (tools/maps/SKYWARS_MAPS.md), shipped maps. */
    static final List<String> MAPS = List.of("skywars_sakura_court", "skywars_copper_harbor", "skywars_frost_observatory",
            "skywars_scarlet_temple", "skywars_luminous_grove");

    private final MapService maps;
    private final ShippedMaps shipped;
    private final GameModeService modes;

    public OriginalSkyWarsSeed(MapService maps, ShippedMaps shipped, GameModeService modes) {
        this.maps = maps;
        this.shipped = shipped;
        this.modes = modes;
    }

    @Override
    public String id() {
        return "skywars-original-five-v1";
    }

    @Override
    public void apply() {
        for (String id : MAPS) shipped.install(id);
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
            for (String map : MAPS) {
                if (!SkyWarsDefinition.ID.equals(maps.get(map).gameType())) continue;
                boolean present = false;
                for (var entry : pool) if (map.equals(entry.asText())) present = true;
                if (!present) {
                    pool.add(map);
                    changed = true;
                }
            }
            if (changed) modes.update(id, new GameModeService.Request(id, mode.title(), mode.type(), mode.description(),
                    mode.enabled(), mode.timeLimitSeconds(), mode.availableUntil() == null ? null : mode.availableUntil().toString(),
                    settings, mode.kit()));
        }
    }
}
