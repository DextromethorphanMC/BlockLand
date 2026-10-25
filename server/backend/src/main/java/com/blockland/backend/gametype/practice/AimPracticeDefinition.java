package com.blockland.backend.gametype.practice;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.common.MovementSettings;
import com.blockland.backend.gametype.common.TeamArenaSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 枪械瞄准练习: one player on a shooting range the mod builds itself (no map), every gun and endless ammo, fake players
 * as targets. Settings: {@code targetCount}, {@code targetHealth}, {@code moving} (targets strafe and peek),
 * {@code respawnSeconds}, {@code armor} (targets wear armour) and the movement rules ({@code allowSlide}).
 * Must stay in sync with the mod's {@code AimPracticeSettings}.
 */
@Component
public class AimPracticeDefinition implements GameTypeDefinition {
    public static final String ID = "AIM_PRACTICE";
    private static final boolean ALLOW_SLIDE = true;

    private final ObjectMapper mapper;

    public AimPracticeDefinition(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "枪械瞄准练习";
    }

    @Override
    public String description() {
        return "单人靶场：发放全部枪械并提供无限弹药，对移动的假人练习瞄准与压枪。无需地图。";
    }

    /** Practice: lenient, setbacks only. */
    @Override
    public String antiCheatPreset() {
        return "relaxed";
    }

    @Override
    public ObjectNode defaultSettings() {
        ObjectNode node = mapper.createObjectNode();
        node.put("targetCount", 8);
        node.put("targetHealth", 20);
        node.put("moving", true);
        node.put("respawnSeconds", 1.5);
        node.put("armor", false);
        MovementSettings.putDefaults(node, ALLOW_SLIDE);
        return node;
    }

    @Override
    public ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors) {
        JsonNode in = raw == null || !raw.isObject() ? mapper.createObjectNode() : raw;
        ObjectNode out = defaultSettings();
        out.put("targetCount", TeamArenaSettings.intField(in, "targetCount", 8, 1, 24, "假人数量", errors));
        out.put("targetHealth", TeamArenaSettings.intField(in, "targetHealth", 20, 1, 200, "假人生命值", errors));
        out.put("moving", TeamArenaSettings.boolField(in, "moving", true));
        out.put("respawnSeconds", TeamArenaSettings.doubleField(in, "respawnSeconds", 1.5, 0, 30, "假人复活时间", errors));
        out.put("armor", TeamArenaSettings.boolField(in, "armor", false));
        MovementSettings.normalizeInto(in, out, ALLOW_SLIDE);
        return out;
    }

    @Override
    public List<String> warnings(GameMode mode, Context context) {
        return List.of();
    }
}
