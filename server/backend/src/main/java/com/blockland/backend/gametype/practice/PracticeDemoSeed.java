package com.blockland.backend.gametype.practice;

import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.modegroup.ModeGroup;
import com.blockland.backend.modegroup.ModeGroupService;
import com.blockland.backend.seed.CoverArt;
import com.blockland.backend.seed.DemoSeed;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The single-player practice modes 枪械瞄准练习 and 搭路练习 (no maps needed: the mod builds both courses) in a lobby group
 * 「练习」, with generated covers. Every step checks what already exists.
 */
@Component
@Order(70)
public class PracticeDemoSeed implements DemoSeed {
    static final String AIM_MODE = "aim_practice";
    static final String BRIDGE_MODE = "bridge_practice";
    static final String GROUP_ID = "practice";

    private final GameModeService modes;
    private final ModeGroupService groups;
    private final AimPracticeDefinition aim;
    private final BridgePracticeDefinition bridge;

    public PracticeDemoSeed(GameModeService modes, ModeGroupService groups, AimPracticeDefinition aim, BridgePracticeDefinition bridge) {
        this.modes = modes;
        this.groups = groups;
        this.aim = aim;
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "practice-modes";
    }

    @Override
    public void apply() {
        if (!modes.exists(AIM_MODE)) {
            modes.create(new GameModeService.Request(AIM_MODE, "枪械瞄准练习", AimPracticeDefinition.ID,
                    "单人靶场：获得全部枪械、弹药无限，对会横移、探头、下蹲的假人练习瞄准、爆头与压枪。随时按 Esc 离开。",
                    true, null, null, aim.defaultSettings(), List.of()));
        }
        if (modes.get(AIM_MODE).coverVersion() == null) modes.setCover(AIM_MODE, CoverArt.range(21));
        if (!modes.exists(BRIDGE_MODE)) {
            modes.create(new GameModeService.Request(BRIDGE_MODE, "搭路练习", BridgePracticeDefinition.ID,
                    "单人搭路：方块无限，跨过虚空搭到对面的金色平台。掉下去自动回到起点，挑战自己的最快用时。",
                    true, null, null, bridge.defaultSettings(), List.of()));
        }
        if (modes.get(BRIDGE_MODE).coverVersion() == null) modes.setCover(BRIDGE_MODE, CoverArt.bridge(22));

        if (!groups.exists(GROUP_ID)) {
            groups.create(new ModeGroup(GROUP_ID, "练习", "单人练习场：瞄准、压枪、搭路，随进随练，不计胜负。",
                    List.of(AIM_MODE, BRIDGE_MODE), true, 9, null, null));
        }
        ModeGroup group = groups.get(GROUP_ID);
        List<String> modeIds = new ArrayList<>(group.modeIds().stream().filter(modes::exists).toList());
        boolean changed = false;
        for (String id : List.of(AIM_MODE, BRIDGE_MODE)) {
            if (!modeIds.contains(id)) {
                modeIds.add(id);
                changed = true;
            }
        }
        if (changed) group = groups.update(GROUP_ID, group.withModeIds(modeIds));
        if (group.coverVersion() == null) groups.setCover(GROUP_ID, CoverArt.practice(23));
    }
}
