package com.blockland.backend.gametype.world;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.modegroup.ModeGroup;
import com.blockland.backend.modegroup.ModeGroupService;
import com.blockland.backend.seed.CoverArt;
import com.blockland.backend.seed.DemoSeed;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 开放世界: one survival world everybody shares, in a lobby group of its own. */
@Component
@Order(67)
public class WorldDemoSeed implements DemoSeed {
    static final String MODE_ID = "open_world";
    static final String GROUP_ID = "world";

    private final GameModeService modes;
    private final ModeGroupService groups;
    private final WorldDefinition definition;

    public WorldDemoSeed(GameModeService modes, ModeGroupService groups, WorldDefinition definition) {
        this.modes = modes;
        this.groups = groups;
        this.definition = definition;
    }

    @Override
    public String id() {
        return "world-demo";
    }

    @Override
    public void apply() {
        if (!modes.exists(MODE_ID)) {
            modes.create(new GameModeService.Request(MODE_ID, "开放世界", WorldDefinition.ID,
                    "所有人共享的生存世界：随时进入、随时离开，背包与位置会保存到下次。", true, null, null,
                    definition.defaultSettings(), definition.defaultKit()));
        }
        if (modes.get(MODE_ID).coverVersion() == null) modes.setCover(MODE_ID, CoverArt.islands(false, 31));
        if (!groups.exists(GROUP_ID)) {
            groups.create(new ModeGroup(GROUP_ID, "开放世界", "不限时、不分胜负的共享世界。", List.of(MODE_ID), true, 5, null, null));
            groups.setCover(GROUP_ID, CoverArt.islands(true, 32));
        }
    }
}
