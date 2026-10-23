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
import java.util.regex.Pattern;

/**
 * 搭路练习: one player bridges across the void from a spawn island to a goal island (the mod builds the course, no
 * map) with endless blocks; falling off goes back to the start. Settings: {@code gapLength}, {@code courseWidth},
 * {@code voidDepth}, {@code blockId} (a block item, e.g. {@code minecraft:white_wool}), {@code timed} (timer and best
 * time) and the movement rules ({@code allowSlide}). Must stay in sync with the mod's {@code BridgePracticeSettings}.
 */
@Component
public class BridgePracticeDefinition implements GameTypeDefinition {
    public static final String ID = "BRIDGE_PRACTICE";
    private static final boolean ALLOW_SLIDE = false;
    private static final String DEFAULT_BLOCK = "minecraft:white_wool";
    private static final Pattern ITEM_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    private final ObjectMapper mapper;

    public BridgePracticeDefinition(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "搭路练习";
    }

    @Override
    public String description() {
        return "单人搭路：方块无限，跨过虚空搭到对面的终点平台，掉落自动回到起点并计时。无需地图。";
    }

    @Override
    public ObjectNode defaultSettings() {
        ObjectNode node = mapper.createObjectNode();
        node.put("gapLength", 36);
        node.put("courseWidth", 9);
        node.put("voidDepth", 10);
        node.put("blockId", DEFAULT_BLOCK);
        node.put("timed", true);
        MovementSettings.putDefaults(node, ALLOW_SLIDE);
        return node;
    }

    @Override
    public ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors) {
        JsonNode in = raw == null || !raw.isObject() ? mapper.createObjectNode() : raw;
        ObjectNode out = defaultSettings();
        out.put("gapLength", TeamArenaSettings.intField(in, "gapLength", 36, 8, 120, "虚空距离", errors));
        out.put("courseWidth", TeamArenaSettings.intField(in, "courseWidth", 9, 3, 31, "平台宽度", errors));
        out.put("voidDepth", TeamArenaSettings.intField(in, "voidDepth", 10, 4, 40, "掉落判定深度", errors));
        String block = in.path("blockId").asText(DEFAULT_BLOCK).trim();
        if (block.isEmpty()) block = DEFAULT_BLOCK;
        if (!ITEM_ID.matcher(block).matches()) {
            errors.add("方块 ID 格式不正确，例如 minecraft:white_wool");
            block = DEFAULT_BLOCK;
        }
        out.put("blockId", block);
        out.put("timed", TeamArenaSettings.boolField(in, "timed", true));
        MovementSettings.normalizeInto(in, out, ALLOW_SLIDE);
        return out;
    }

    @Override
    public List<String> warnings(GameMode mode, Context context) {
        return List.of();
    }
}
