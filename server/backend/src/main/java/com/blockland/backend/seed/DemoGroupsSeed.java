package com.blockland.backend.seed;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.modegroup.ModeGroup;
import com.blockland.backend.modegroup.ModeGroupService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Covers for the demo modes plus two groups ("空岛战争", "PVP 竞技"). */
@Component
@Order(40)
public class DemoGroupsSeed implements DemoSeed {
    private final GameModeService modes;
    private final ModeGroupService groups;

    public DemoGroupsSeed(GameModeService modes, ModeGroupService groups) {
        this.modes = modes;
        this.groups = groups;
    }

    @Override
    public String id() {
        return "demo-groups-covers";
    }

    @Override
    public void apply() {
        cover("skywars_1v1", CoverArt.islands(false, 1));
        cover("skywars_4v4", CoverArt.islands(true, 2));
        cover("classpvp_1v1", CoverArt.swords(3));

        List<String> skyWars = existing(List.of("skywars_1v1", "skywars_4v4"));
        if (!skyWars.isEmpty() && !groups.exists("skywars")) {
            groups.create(new ModeGroup("skywars", "空岛战争", "出生在空岛上搜刮物资，击败所有对手。", skyWars, true, 0, null, null));
            groups.setCover("skywars", CoverArt.islands(false, 7));
        }
        List<String> pvp = existing(List.of("classpvp_1v1"));
        if (!pvp.isEmpty() && !groups.exists("pvp")) {
            groups.create(new ModeGroup("pvp", "PVP 竞技", "统一装备，纯粹比拼操作的快节奏对决。", pvp, true, 1, null, null));
            groups.setCover("pvp", CoverArt.swords(9));
        }
    }

    private void cover(String modeId, byte[] image) {
        if (modes.exists(modeId) && modes.get(modeId).coverVersion() == null) modes.setCover(modeId, image);
    }

    private List<String> existing(List<String> ids) {
        return ids.stream().filter(modes::exists).toList();
    }
}
