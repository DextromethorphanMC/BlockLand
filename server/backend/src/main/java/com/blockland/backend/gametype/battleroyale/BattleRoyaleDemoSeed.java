package com.blockland.backend.gametype.battleroyale;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.map.MapService;
import com.blockland.backend.map.ShippedMaps;
import com.blockland.backend.modegroup.ModeGroup;
import com.blockland.backend.modegroup.ModeGroupService;
import com.blockland.backend.seed.CoverArt;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 大逃杀 on the demo island (shipped map {@code br_island}): a 16-player mode and a 2-player one (two clients are
 * enough to try the plane, the jump and the fight), in a lobby group of their own. The loot names guns that come with
 * the mod, so the modes are created even before the server has reported its items.
 */
@Component
@Order(68)
public class BattleRoyaleDemoSeed implements DemoSeed {
    static final String DEMO_MAP = "br_island";
    static final String GROUP_ID = "battle_royale";
    private static final String DESCRIPTION = "乘飞机飞越小岛，按空格跳下并打开降落伞；落地搜刮枪械与物资，躲开缩小的安全区，最后存活的玩家获胜。";

    private final GameModeService modes;
    private final ModeGroupService groups;
    private final MapService maps;
    private final ShippedMaps shipped;
    private final BattleRoyaleDefinition definition;

    public BattleRoyaleDemoSeed(GameModeService modes, ModeGroupService groups, MapService maps, ShippedMaps shipped,
                                BattleRoyaleDefinition definition) {
        this.modes = modes;
        this.groups = groups;
        this.maps = maps;
        this.shipped = shipped;
        this.definition = definition;
    }

    @Override
    public String id() {
        return "battle-royale-demo";
    }

    @Override
    public void apply() {
        shipped.install(DEMO_MAP);
        mode("br_16", "大逃杀 16人", 16, 42);
        mode("br_2", "大逃杀 2人（测试）", 2, 43);

        List<String> ids = List.of("br_16", "br_2").stream().filter(modes::exists).toList();
        if (ids.isEmpty()) return;
        if (!groups.exists(GROUP_ID)) {
            groups.create(new ModeGroup(GROUP_ID, "大逃杀", "跳伞、搜刮、生存：只有一个人能活到最后。", ids, true, 4, null, null));
        }
        if (groups.get(GROUP_ID).coverVersion() == null) groups.setCover(GROUP_ID, CoverArt.islands(true, 44));
    }

    private void mode(String id, String title, int players, long coverSeed) {
        if (!modes.exists(id)) {
            ObjectNode settings = definition.defaultSettings();
            settings.put("players", players);
            // an empty pool means every 大逃杀 map, in case an admin removed the demo island
            ArrayNode mapIds = settings.putArray("mapIds");
            if (maps.exists(DEMO_MAP) && BattleRoyaleDefinition.ID.equalsIgnoreCase(maps.get(DEMO_MAP).gameType())) mapIds.add(DEMO_MAP);
            if (players <= 2) {
                // a quick test round: the zone closes in sooner
                settings.put("zoneDelaySeconds", 30);
                settings.put("zoneShrinkSeconds", 120);
            }
            modes.createAllowingUnknownItems(new GameModeService.Request(id, title, BattleRoyaleDefinition.ID, DESCRIPTION, true, null, null,
                    settings, List.of()));
        }
        if (modes.get(id).coverVersion() == null) modes.setCover(id, CoverArt.islands(true, coverSeed));
    }
}
