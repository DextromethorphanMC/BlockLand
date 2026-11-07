package com.blockland.backend.gametype.bomb;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.map.MapAmbient;
import com.blockland.backend.map.MapService;
import com.blockland.backend.map.ShippedMaps;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DustA (沙漠小镇), the desert town for 枪战爆破 (a {@link ShippedMaps shipped map}), with tumbleweeds in its air
 * ({@link MapAmbient}); added to the shipped 枪战爆破 modes' map pools (custom modes and emptied pools are left alone).
 * v2: a stored DustA from before the town stood on its sandstone foundation (its sand fell into the void) gets the new
 * file, with the spawns and sites one block higher; its ambience is kept as the admin left it.
 */
@Component
@Order(73)
public class DustASeed implements DemoSeed {
    static final String MAP = "dust_a";

    private final MapService maps;
    private final ShippedMaps shipped;
    private final GameModeService modes;

    public DustASeed(MapService maps, ShippedMaps shipped, GameModeService modes) {
        this.maps = maps;
        this.shipped = shipped;
        this.modes = modes;
    }

    @Override
    public String id() {
        return "bomb-dust-a-v2";
    }

    @Override
    public void apply() {
        if (!shipped.install(MAP) && BombDefinition.ID.equalsIgnoreCase(maps.get(MAP).gameType())) shipped.refresh(MAP);
        if (!BombDefinition.ID.equalsIgnoreCase(maps.get(MAP).gameType())) return;
        for (String id : List.of("bomb_5v5", "bomb_1v1")) {
            if (!modes.exists(id)) continue;
            var mode = modes.get(id);
            if (!BombDefinition.ID.equalsIgnoreCase(mode.type())) continue;
            ObjectNode settings = mode.settings().deepCopy();
            var pool = settings.withArray("mapIds");
            // an empty pool already means every 枪战爆破 map
            if (pool.isEmpty()) continue;
            boolean present = false;
            for (var entry : pool) if (MAP.equals(entry.asText())) present = true;
            if (present) continue;
            pool.add(MAP);
            modes.updateAllowingUnknownItems(id, new GameModeService.Request(id, mode.title(), mode.type(), mode.description(),
                    mode.enabled(), mode.timeLimitSeconds(), mode.availableUntil() == null ? null : mode.availableUntil().toString(),
                    settings, mode.kit()));
        }
    }
}
