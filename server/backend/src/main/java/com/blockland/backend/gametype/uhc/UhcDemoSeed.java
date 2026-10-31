package com.blockland.backend.gametype.uhc;

import com.blockland.backend.gamemode.GameModeService;
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
 * 极限生存 for 2 players (a small world and short phases, to try it out) and for 16 (the full-size game), in a lobby
 * group of their own. No maps: the mod generates the world. Every step checks what already exists.
 */
@Component
@Order(72)
public class UhcDemoSeed implements DemoSeed {
    static final String GROUP_ID = "uhc";
    private static final String DESCRIPTION = "全新生成的生存世界：随机出生，采集、合成、战斗，最后存活的玩家获胜；普通圈时间到仍未分出胜负则传送进决赛圈，边界逐渐收缩。";

    private final GameModeService modes;
    private final ModeGroupService groups;
    private final UhcDefinition definition;

    public UhcDemoSeed(GameModeService modes, ModeGroupService groups, UhcDefinition definition) {
        this.modes = modes;
        this.groups = groups;
        this.definition = definition;
    }

    @Override
    public String id() {
        return "uhc-demo";
    }

    @Override
    public void apply() {
        // 2 players: a 500 × 500 world, 10 minutes of normal circle, then a 160 × 160 final circle closing to 20 × 20
        mode("uhc_2", "极限生存 2人", 2, 250, 60, 600, 80, 180, 10, () -> CoverArt.uhc(false, 61));
        // 16 players: a 1000 × 1000 world, 20 minutes of normal circle, then a 300 × 300 final circle closing to 30 × 30
        mode("uhc_16", "极限生存 16人", 16, 500, 100, 1200, 150, 300, 15, () -> CoverArt.uhc(true, 62));

        List<String> ids = List.of("uhc_16", "uhc_2").stream().filter(modes::exists).toList();
        if (ids.isEmpty()) return;
        if (!groups.exists(GROUP_ID)) {
            groups.create(new ModeGroup(GROUP_ID, "极限生存", "一个有边界的全新世界，从零开始生存与战斗，活到最后。", ids, true, 8, null, null));
        }
        if (groups.get(GROUP_ID).coverVersion() == null) groups.setCover(GROUP_ID, CoverArt.uhc(true, 63));
    }

    private void mode(String id, String title, int players, int borderRadius, int spawnSpacing, int normalPhaseSeconds,
                      int finalRadius, int finalShrinkSeconds, int finalEndRadius, Supplier<byte[]> cover) {
        if (!modes.exists(id)) {
            ObjectNode settings = definition.defaultSettings();
            settings.put("players", players);
            settings.put("borderRadius", borderRadius);
            settings.put("spawnSpacing", spawnSpacing);
            settings.put("normalPhaseSeconds", normalPhaseSeconds);
            settings.put("finalRadius", finalRadius);
            settings.put("finalShrinkSeconds", finalShrinkSeconds);
            settings.put("finalEndRadius", finalEndRadius);
            modes.create(new GameModeService.Request(id, title, UhcDefinition.ID, DESCRIPTION, true, null, null, settings, List.of()));
        }
        if (modes.get(id).coverVersion() == null) modes.setCover(id, cover.get());
    }
}
