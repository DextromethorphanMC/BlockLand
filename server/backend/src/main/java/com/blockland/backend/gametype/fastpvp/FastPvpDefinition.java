package com.blockland.backend.gametype.fastpvp;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.common.MovementSettings;
import com.blockland.backend.gametype.common.TeamArenaSettings;
import com.blockland.backend.kit.KitItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FastPVP: teams spawn on a custom arena map with a configured kit; last team standing wins.
 * Settings: the shared team arena settings plus {@code allowBlockBreak} / {@code allowBlockPlace},
 * {@code infiniteBlocks} (placed blocks are given back at once), {@code knockbackOnly} (hits only knock back, only the
 * void kills — Rush) and the movement rules ({@code allowSlide}).
 * Must stay in sync with the mod's {@code FastPvpSettings}.
 */
@Component
public class FastPvpDefinition implements GameTypeDefinition {
    public static final String ID = "FASTPVP";
    /** Arena fights (guns included) slide by default. */
    private static final boolean ALLOW_SLIDE = true;

    private final ObjectMapper mapper;

    public FastPvpDefinition(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "快速PVP";
    }

    @Override
    public String description() {
        return "所有玩家使用相同的开局装备在竞技场中对战，活到最后的队伍获胜。";
    }

    @Override
    public ObjectNode defaultSettings() {
        ObjectNode node = mapper.createObjectNode();
        TeamArenaSettings.putDefaults(node);
        node.put("countdownSeconds", 5);
        node.put("endingSeconds", 5);
        node.put("allowBlockBreak", false);
        node.put("allowBlockPlace", false);
        node.put("infiniteBlocks", false);
        node.put("knockbackOnly", false);
        MovementSettings.putDefaults(node, ALLOW_SLIDE);
        return node;
    }

    @Override
    public ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors) {
        JsonNode in = raw == null || !raw.isObject() ? mapper.createObjectNode() : raw;
        ObjectNode out = defaultSettings();
        TeamArenaSettings.normalizeInto(in, out, ID, context, errors);
        out.put("allowBlockBreak", TeamArenaSettings.boolField(in, "allowBlockBreak", false));
        out.put("allowBlockPlace", TeamArenaSettings.boolField(in, "allowBlockPlace", false));
        out.put("infiniteBlocks", TeamArenaSettings.boolField(in, "infiniteBlocks", false));
        out.put("knockbackOnly", TeamArenaSettings.boolField(in, "knockbackOnly", false));
        MovementSettings.normalizeInto(in, out, ALLOW_SLIDE);
        return out;
    }

    @Override
    public List<String> warnings(GameMode mode, Context context) {
        List<String> warnings = TeamArenaSettings.mapWarnings(mode, ID, context);
        if (mode.kit().isEmpty()) warnings.add("没有配置开局装备，玩家将空手进入对局");
        JsonNode settings = mode.settings();
        if (settings != null && settings.path("infiniteBlocks").asBoolean(false) && !settings.path("allowBlockPlace").asBoolean(false)) {
            warnings.add("开启了「方块无限」但不允许放置方块，该设置不会生效");
        }
        return warnings;
    }

    @Override
    public boolean usesKit() {
        return true;
    }

    @Override
    public List<KitItem> defaultKit() {
        return List.of(new KitItem("minecraft:iron_sword", 1, "0"));
    }
}
