package com.blockland.backend.gametype.skywars;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.common.MovementSettings;
import com.blockland.backend.gametype.common.TeamArenaSettings;
import com.blockland.backend.gametype.skywars.loot.LootTable;
import com.blockland.backend.gametype.skywars.loot.LootTableService;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 空岛战争. Mode settings: the shared team arena settings plus {@code chestRefillSeconds}.
 * Map settings: {@link SkyWarsMapSettings}. Loot tables are SkyWars-only and synced as type data.
 * Must stay in sync with the mod's {@code SkyWarsSettings} / {@code SkyWarsMapSettings}.
 */
@Component
public class SkyWarsDefinition implements GameTypeDefinition {
    public static final String ID = "SKYWARS";
    /** Islands over the void: no sliding unless a mode turns it on. */
    private static final boolean ALLOW_SLIDE = false;

    private final ObjectMapper mapper;
    private final LootTableService lootTables;

    public SkyWarsDefinition(ObjectMapper mapper, LootTableService lootTables) {
        this.mapper = mapper;
        this.lootTables = lootTables;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "空岛战争";
    }

    @Override
    public String description() {
        return "玩家出生在各自的空岛上，搜刮箱子物资，击败其他队伍，活到最后的队伍获胜。";
    }

    @Override
    public ObjectNode defaultSettings() {
        ObjectNode node = mapper.createObjectNode();
        TeamArenaSettings.putDefaults(node);
        node.put("chestRefillSeconds", 0);
        MovementSettings.putDefaults(node, ALLOW_SLIDE);
        return node;
    }

    @Override
    public ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors) {
        JsonNode in = raw == null || !raw.isObject() ? mapper.createObjectNode() : raw;
        ObjectNode out = defaultSettings();
        TeamArenaSettings.normalizeInto(in, out, ID, context, errors);
        out.put("chestRefillSeconds", TeamArenaSettings.intField(in, "chestRefillSeconds", 0, 0, 3600, "箱子刷新间隔", errors));
        MovementSettings.normalizeInto(in, out, ALLOW_SLIDE);
        return out;
    }

    @Override
    public List<String> warnings(GameMode mode, Context context) {
        List<String> warnings = TeamArenaSettings.mapWarnings(mode, ID, context);
        for (GameMap map : TeamArenaSettings.mapPool(mode, ID, context)) {
            if (read(map.settings()).chests().isEmpty()) warnings.add("地图「" + map.name() + "」没有配置箱子");
        }
        return warnings;
    }

    @Override
    public ObjectNode mapSettingsFromFile(MapFileAnalyzer.Result analysis, ObjectNode previous) {
        SkyWarsMapSettings old = read(previous);
        Map<String, SkyWarsMapSettings.Chest> byPos = new HashMap<>();
        for (SkyWarsMapSettings.Chest chest : old.chests()) byPos.put(key(chest.x(), chest.y(), chest.z()), chest);
        // keep the loot settings of containers that are still at the same coordinates
        List<SkyWarsMapSettings.Chest> chests = analysis.containers().stream()
                .map(c -> byPos.getOrDefault(key(c.x(), c.y(), c.z()), new SkyWarsMapSettings.Chest(c.x(), c.y(), c.z(), null, "")))
                .toList();
        String defaultTable = old.defaultLootTableId() != null ? old.defaultLootTableId() : suggestedDefaultTable();
        return mapper.valueToTree(new SkyWarsMapSettings(chests, defaultTable));
    }

    @Override
    public ObjectNode normalizeMapSettings(JsonNode raw, List<String> errors) {
        SkyWarsMapSettings in;
        try {
            in = raw == null || raw.isNull() ? new SkyWarsMapSettings(List.of(), null) : mapper.convertValue(raw, SkyWarsMapSettings.class);
        } catch (IllegalArgumentException e) {
            errors.add("空岛战争地图设置格式错误");
            return mapper.valueToTree(new SkyWarsMapSettings(List.of(), null));
        }
        String defaultTable = blankToNull(in.defaultLootTableId());
        if (defaultTable != null && !lootTables.exists(defaultTable)) errors.add("默认物资表不存在: " + defaultTable);

        List<SkyWarsMapSettings.Chest> chests = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SkyWarsMapSettings.Chest chest : in.chests()) {
            String where = "(" + chest.x() + ", " + chest.y() + ", " + chest.z() + ")";
            String table = blankToNull(chest.lootTableId());
            if (table != null && !lootTables.exists(table)) errors.add("箱子 " + where + " 的物资表不存在: " + table);
            if (!seen.add(key(chest.x(), chest.y(), chest.z()))) errors.add("箱子坐标重复: " + where);
            chests.add(new SkyWarsMapSettings.Chest(chest.x(), chest.y(), chest.z(), table, chest.label() == null ? "" : chest.label().trim()));
        }
        return mapper.valueToTree(new SkyWarsMapSettings(chests, defaultTable));
    }

    /** Loot tables are only needed by SkyWars, so they travel as SkyWars type data. */
    @Override
    public Object syncData() {
        return Map.of("lootTables", lootTables.list());
    }

    private SkyWarsMapSettings read(JsonNode node) {
        if (node == null || !node.isObject()) return new SkyWarsMapSettings(List.of(), null);
        try {
            return mapper.convertValue(node, SkyWarsMapSettings.class);
        } catch (IllegalArgumentException e) {
            return new SkyWarsMapSettings(List.of(), null);
        }
    }

    private String suggestedDefaultTable() {
        List<LootTable> tables = lootTables.list();
        return tables.stream().filter(t -> t.id().equals("island")).findFirst()
                .or(() -> tables.stream().findFirst())
                .map(LootTable::id).orElse(null);
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
