package com.blockland.backend.gametype.pillars;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapService;
import com.blockland.backend.modegroup.ModeGroup;
import com.blockland.backend.modegroup.ModeGroupService;
import com.blockland.backend.seed.CoverArt;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 幸运高柱 for 2 and for 16 players, each on a generated map with exactly as many pillars ({@link LuckyPillarsDemoMap}),
 * in a lobby group of their own. The item table names guns that come with the mod, so the modes are created even before
 * the server has reported its items. Every step checks what already exists.
 */
@Component
@Order(71)
public class LuckyPillarsDemoSeed implements DemoSeed {
    static final String DUEL_MAP = "pillars_duel";
    static final String RING_MAP = "pillars_ring16";
    static final String GROUP_ID = "lucky_pillars";
    private static final String DESCRIPTION = "每人站在一根高柱顶端，每隔几秒获得一件随机物品：搭路、下柱、远程偷袭，各自为战，最后存活的玩家获胜。";

    private final GameModeService modes;
    private final ModeGroupService groups;
    private final MapService maps;
    private final LuckyPillarsDefinition definition;

    public LuckyPillarsDemoSeed(GameModeService modes, ModeGroupService groups, MapService maps, LuckyPillarsDefinition definition) {
        this.modes = modes;
        this.groups = groups;
        this.maps = maps;
        this.definition = definition;
    }

    @Override
    public String id() {
        return "lucky-pillars-demo";
    }

    @Override
    public void apply() {
        map(DUEL_MAP, "双柱对决", () -> LuckyPillarsDemoMap.generate(2, 7, 26, 51), () -> CoverArt.pillars(false, 52));
        map(RING_MAP, "十六高柱", () -> LuckyPillarsDemoMap.generate(16, 20, 32, 53), () -> CoverArt.pillars(true, 54));
        mode("lucky_pillars_2", "幸运高柱 2人", 2, DUEL_MAP, 420, () -> CoverArt.pillars(false, 55));
        mode("lucky_pillars_16", "幸运高柱 16人", 16, RING_MAP, 900, () -> CoverArt.pillars(true, 56));

        List<String> ids = List.of("lucky_pillars_16", "lucky_pillars_2").stream().filter(modes::exists).toList();
        if (ids.isEmpty()) return;
        if (!groups.exists(GROUP_ID)) {
            groups.create(new ModeGroup(GROUP_ID, "幸运高柱", "站上高柱，等待好运：随机物品决定你怎么活到最后。", ids, true, 6, null, null));
        }
        if (groups.get(GROUP_ID).coverVersion() == null) groups.setCover(GROUP_ID, CoverArt.pillars(true, 57));
    }

    private void map(String id, String name, Supplier<LuckyPillarsDemoMap.Result> generator, Supplier<byte[]> cover) {
        if (maps.exists(id)) return;
        LuckyPillarsDemoMap.Result generated = generator.get();
        GameMap map = maps.upload(id, name, LuckyPillarsDefinition.ID, null, id + ".nbt", generated.data());
        maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), generated.spawns(), LuckyPillarsDemoMap.VOID_Y,
                map.settings()));
        maps.setCover(id, cover.get());
    }

    private void mode(String id, String title, int players, String mapId, int timeLimitSeconds, Supplier<byte[]> cover) {
        if (!modes.exists(id)) {
            ObjectNode settings = definition.defaultSettings();
            settings.put("players", players);
            // an empty pool means every 幸运高柱 map, in case an admin removed the demo map
            if (maps.exists(mapId) && LuckyPillarsDefinition.ID.equalsIgnoreCase(maps.get(mapId).gameType())) {
                settings.putArray("mapIds").add(mapId);
            }
            modes.createAllowingUnknownItems(new GameModeService.Request(id, title, LuckyPillarsDefinition.ID, DESCRIPTION, true,
                    timeLimitSeconds, null, settings, List.of()));
        }
        if (modes.get(id).coverVersion() == null) modes.setCover(id, cover.get());
    }
}
