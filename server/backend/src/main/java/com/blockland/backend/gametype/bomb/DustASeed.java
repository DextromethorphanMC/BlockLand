package com.blockland.backend.gametype.bomb;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapAmbient;
import com.blockland.backend.map.MapService;
import com.blockland.backend.seed.CoverArt;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DustA (沙漠小镇), the generated desert town ({@link DustAArena}) for 枪战爆破, with tumbleweeds in its air
 * ({@link MapAmbient}); added to the shipped 枪战爆破 modes' map pools (custom modes and emptied pools are left alone).
 * v2: a stored DustA from before the town stood on its sandstone foundation (its sand fell into the void) gets the new
 * file, with the spawns and sites one block higher; its ambience is kept as the admin left it.
 */
@Component
@Order(73)
public class DustASeed implements DemoSeed {
    static final String MAP = "dust_a";

    private final MapService maps;
    private final GameModeService modes;
    private final ObjectMapper mapper;

    public DustASeed(MapService maps, GameModeService modes, ObjectMapper mapper) {
        this.maps = maps;
        this.modes = modes;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "bomb-dust-a-v2";
    }

    @Override
    public void apply() {
        if (!maps.exists(MAP)) {
            DustAArena.Result arena = DustAArena.generate();
            GameMap map = maps.upload(MAP, "沙漠小镇 DustA", BombDefinition.ID, null, MAP + ".nbt", arena.data());
            ObjectNode settings = BombMapSettings.toJson(mapper, arena.sites());
            settings.putObject(MapAmbient.KEY).put(MapAmbient.TUMBLEWEEDS, true);
            maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), arena.spawns(), -5, settings));
            maps.setCover(MAP, CoverArt.desertTown(31));
        } else if (BombDefinition.ID.equalsIgnoreCase(maps.get(MAP).gameType())) {
            DustAArena.Result arena = DustAArena.generate();
            if (maps.replaceGeneratedFile(MAP, MAP + ".nbt", arena.data())) {
                GameMap map = maps.get(MAP);
                ObjectNode settings = BombMapSettings.toJson(mapper, arena.sites());
                MapAmbient.attach(settings, MapAmbient.of(map.settings()));
                maps.update(MAP, new MapService.UpdateRequest(map.name(), map.gameType(), arena.spawns(), map.voidY(), settings));
            }
        }
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
