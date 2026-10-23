package com.blockland.backend.gametype.infection;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapService;
import com.blockland.backend.modegroup.ModeGroup;
import com.blockland.backend.modegroup.ModeGroupService;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings installs that ran the first 枪战感染 demo up to date, once (an admin's later choices, e.g. putting the mode back
 * into 「PVP 竞技」, are not overridden): the mode moves into its own group, the demo map gets role spawn points, and
 * the demo mode offers every monster the server publishes instead of the old fixed zombie list. Nothing deleted is
 * recreated; on fresh installs {@link InfectionDemoSeed} already produced this state.
 */
@Component
@Order(61)
public class InfectionUpgradeSeed implements DemoSeed {
    private static final Logger LOG = LoggerFactory.getLogger(InfectionUpgradeSeed.class);
    private static final String PVP_GROUP = "pvp";
    /** Written by the first version; replaced only while unchanged. */
    private static final String FIRST_DESCRIPTION = "人类持枪坚守到时间结束，被怪物击杀即被感染加入怪物一方；感染全部人类则怪物获胜。";
    private static final List<String> FIRST_MONSTERS = List.of("zombie");

    private final GameModeService modes;
    private final ModeGroupService groups;
    private final MapService maps;
    private final InfectionDemoSeed demo;

    public InfectionUpgradeSeed(GameModeService modes, ModeGroupService groups, MapService maps, InfectionDemoSeed demo) {
        this.modes = modes;
        this.groups = groups;
        this.maps = maps;
        this.demo = demo;
    }

    @Override
    public String id() {
        return "infection-v2";
    }

    @Override
    public void apply() {
        demo.ensureOwnGroup();
        if (groups.exists(PVP_GROUP)) {
            ModeGroup pvp = groups.get(PVP_GROUP);
            if (pvp.modeIds().contains(InfectionDemoSeed.MODE_ID)) {
                List<String> modeIds = pvp.modeIds().stream().filter(id -> !id.equals(InfectionDemoSeed.MODE_ID)).filter(modes::exists).toList();
                groups.update(PVP_GROUP, pvp.withModeIds(modeIds));
            }
        }

        if (maps.exists(InfectionDemoSeed.DEMO_MAP)) {
            GameMap map = maps.get(InfectionDemoSeed.DEMO_MAP);
            if (InfectionDefinition.ID.equalsIgnoreCase(map.gameType()) && InfectionMapSettings.read(map.settings()).isEmpty()) {
                maps.update(map.id(), new MapService.UpdateRequest(map.name(), map.gameType(), map.teamSpawns(), map.voidY(), demo.demoMapSettings()));
            }
        }

        if (modes.exists(InfectionDemoSeed.MODE_ID)) upgradeDemoMode(modes.get(InfectionDemoSeed.MODE_ID));
    }

    private void upgradeDemoMode(GameMode mode) {
        ObjectNode settings = mode.settings() == null ? null : mode.settings().deepCopy();
        List<String> monsters = new ArrayList<>();
        if (settings != null) for (JsonNode id : settings.path("monsters")) monsters.add(id.asText());
        boolean firstMonsters = settings != null && FIRST_MONSTERS.equals(monsters);
        boolean firstDescription = FIRST_DESCRIPTION.equals(mode.description());
        if (!firstMonsters && !firstDescription) return;
        if (firstMonsters) settings.putArray("monsters");
        try {
            modes.update(mode.id(), new GameModeService.Request(mode.id(), mode.title(), mode.type(),
                    firstDescription ? InfectionDemoSeed.DESCRIPTION : mode.description(), mode.enabled(), mode.timeLimitSeconds(),
                    mode.availableUntil() == null ? null : mode.availableUntil().toString(), settings, mode.kit()));
        } catch (ApiException e) {
            // e.g. the kit names items the server no longer has; the mode keeps working with its old values
            LOG.warn("Could not update demo mode {}: {} {}", mode.id(), e.getMessage(), e.details());
        }
    }
}
