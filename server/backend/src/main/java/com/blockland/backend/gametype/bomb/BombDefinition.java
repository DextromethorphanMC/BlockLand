package com.blockland.backend.gametype.bomb;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.common.MovementSettings;
import com.blockland.backend.gametype.common.TeamArenaSettings;
import com.blockland.backend.kit.KitItem;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 枪战爆破: attackers (team spawn group 1) carry a bomb to one of the map's bomb sites and plant it; defenders (group 2)
 * stop them or defuse it. The bomb going off wins for the attackers; a defuse, the plant time running out or all
 * attackers dead before a plant wins for the defenders. The mode's {@code timeLimitSeconds} is the time the attackers
 * have to plant (none = no limit).
 * Settings: the shared team arena settings ({@code teamCount} is always 2), {@code plantSeconds}, {@code defuseSeconds},
 * {@code fuseSeconds}, {@code allowBlockBreak} / {@code allowBlockPlace} and the movement rules ({@code allowSlide}).
 * Map settings: {@link BombMapSettings} (the sites). Must stay in sync with the mod's {@code BombSettings} / {@code BombSites}.
 */
@Component
public class BombDefinition implements GameTypeDefinition {
    public static final String ID = "BOMB";
    public static final int TEAM_COUNT = 2;
    private static final boolean ALLOW_SLIDE = true;

    private final ObjectMapper mapper;

    public BombDefinition(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "枪战爆破";
    }

    @Override
    public String description() {
        return "进攻方携带炸药包在包点安放，防守方阻止安放或拆除炸弹；炸弹爆炸则进攻方获胜。";
    }

    /** Competitive rounds: stricter tolerance and auto-bans allowed. */
    @Override
    public String antiCheatPreset() {
        return "strict";
    }

    @Override
    public ObjectNode defaultSettings() {
        ObjectNode node = mapper.createObjectNode();
        TeamArenaSettings.putDefaults(node);
        node.put("teamSize", 5);
        node.put("teamCount", TEAM_COUNT);
        node.put("plantSeconds", 3.2);
        node.put("defuseSeconds", 7.0);
        node.put("fuseSeconds", 40);
        node.put("allowBlockBreak", false);
        node.put("allowBlockPlace", false);
        MovementSettings.putDefaults(node, ALLOW_SLIDE);
        return node;
    }

    @Override
    public ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors) {
        JsonNode in = raw == null || !raw.isObject() ? mapper.createObjectNode() : raw;
        ObjectNode out = defaultSettings();
        TeamArenaSettings.normalizeInto(in, out, ID, context, errors);
        // the shared fields fall back to 1 per team; this type defaults to 5 (range errors were reported above)
        out.put("teamSize", TeamArenaSettings.intField(in, "teamSize", 5, 1, 64, "每队人数", new ArrayList<>()));
        JsonNode teamCount = in.get("teamCount");
        if (teamCount != null && !teamCount.isNull() && teamCount.asInt(-1) != TEAM_COUNT) {
            errors.add("枪战爆破固定为 2 支队伍（进攻方 / 防守方）");
        }
        out.put("teamCount", TEAM_COUNT);
        out.put("plantSeconds", TeamArenaSettings.doubleField(in, "plantSeconds", 3.2, 1, 15, "安放时间", errors));
        out.put("defuseSeconds", TeamArenaSettings.doubleField(in, "defuseSeconds", 7, 1, 30, "拆除时间", errors));
        out.put("fuseSeconds", TeamArenaSettings.intField(in, "fuseSeconds", 40, 10, 120, "爆炸倒计时", errors));
        out.put("allowBlockBreak", TeamArenaSettings.boolField(in, "allowBlockBreak", false));
        out.put("allowBlockPlace", TeamArenaSettings.boolField(in, "allowBlockPlace", false));
        MovementSettings.normalizeInto(in, out, ALLOW_SLIDE);
        return out;
    }

    @Override
    public List<String> warnings(GameMode mode, Context context) {
        List<String> warnings = TeamArenaSettings.mapWarnings(mode, ID, context);
        for (GameMap map : TeamArenaSettings.mapPool(mode, ID, context)) {
            List<BombMapSettings.Site> sites = BombMapSettings.read(map.settings());
            if (sites.isEmpty()) {
                warnings.add("地图「" + map.name() + "」没有设置包点，无法用于枪战爆破");
                continue;
            }
            for (BombMapSettings.Site site : sites) {
                if (site.leaves(map.size())) warnings.add("地图「" + map.name() + "」的包点 " + site.name() + " 超出了地图范围");
            }
        }
        if (mode.timeLimitSeconds() == null || mode.timeLimitSeconds() <= 0) {
            warnings.add("未设置存活时间：进攻方安放炸弹没有时间限制");
        }
        if (mode.kit().isEmpty()) warnings.add("没有配置开局装备，玩家将空手进入对局（炸药包由游戏另行发放）");
        return warnings;
    }

    @Override
    public boolean usesKit() {
        return true;
    }

    /** A rifle, a pistol and a knife with spare ammunition; hotbar slot 4 stays free (the bomb's default place). */
    @Override
    public List<KitItem> defaultKit() {
        return List.of(
                new KitItem("blockland:ak47", 1, "0"),
                new KitItem("blockland:usp", 1, "1"),
                new KitItem("blockland:butterfly", 1, "2"),
                new KitItem("blockland:rifle_ammo", 60, "9"),
                new KitItem("blockland:rifle_ammo", 30, "10"),
                new KitItem("blockland:pistol_ammo", 36, "11"));
    }

    @Override
    public ObjectNode mapSettingsFromFile(MapFileAnalyzer.Result analysis, ObjectNode previous) {
        return BombMapSettings.fromFile(mapper, previous);
    }

    @Override
    public ObjectNode normalizeMapSettings(JsonNode raw, List<String> errors) {
        return BombMapSettings.normalize(mapper, raw, errors);
    }
}
