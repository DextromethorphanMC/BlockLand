package com.blockland.backend.gametype.common;

import com.blockland.backend.registry.ItemSpecs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;

/**
 * A weighted item table in mode settings: {@code [{item, min, max, weight}]} with the item in /give syntax, one row drawn
 * with chance weight / total and a count between min and max (e.g. 幸运高柱's {@code items}). Mirrors the mod's
 * {@code WeightedItems}.
 */
public final class WeightedLoot {
    public static final int MAX_ENTRIES = 100;

    private WeightedLoot() {
    }

    /** A table from {@code [item, min, max, weight]} rows. */
    public static ArrayNode of(ObjectMapper mapper, Object[][] rows) {
        ArrayNode table = mapper.createArrayNode();
        for (Object[] r : rows) {
            table.addObject().put("item", (String) r[0]).put("min", (int) r[1]).put("max", (int) r[2]).put("weight", (int) r[3]);
        }
        return table;
    }

    /**
     * Cleaned rows: an item spec, 1 ≤ min ≤ max ≤ 64, weight 1–1000; {@code fallback} when the table is missing.
     *
     * @param rowLabel what a row is called in the problems, e.g. "物品" → "物品第 2 项: …"
     */
    public static ArrayNode normalize(ObjectMapper mapper, ItemSpecs items, JsonNode raw, ArrayNode fallback, String rowLabel,
                                      List<String> errors) {
        if (raw == null || raw.isNull()) return fallback;
        ArrayNode out = mapper.createArrayNode();
        if (!raw.isArray()) {
            errors.add(rowLabel + "表必须是数组");
            return out;
        }
        if (raw.size() > MAX_ENTRIES) errors.add(rowLabel + "表最多 " + MAX_ENTRIES + " 项");
        for (int i = 0; i < raw.size() && i < MAX_ENTRIES; i++) {
            JsonNode row = raw.get(i);
            String label = rowLabel + "第 " + (i + 1) + " 项: ";
            if (!row.isObject()) {
                errors.add(label + "格式错误");
                continue;
            }
            String item = row.path("item").asText("").trim();
            items.checkFormat(item, label, errors);
            int min = TeamArenaSettings.intField(row, "min", 1, 1, 64, label + "最少数量", errors);
            int max = TeamArenaSettings.intField(row, "max", min, 1, 64, label + "最多数量", errors);
            if (min > max) errors.add(label + "最少数量不能大于最多数量");
            int weight = TeamArenaSettings.intField(row, "weight", 10, 1, 1000, label + "权重", errors);
            out.addObject().put("item", item).put("min", Math.min(min, max)).put("max", max).put("weight", weight);
        }
        return out;
    }

    /** Rows naming items the server's registry does not have (once a server reported it). */
    public static void registryWarnings(ItemSpecs items, JsonNode table, String rowLabel, List<String> warnings) {
        if (table == null || !table.isArray()) return;
        for (int i = 0; i < table.size(); i++) {
            items.checkRegistered(table.get(i).path("item").asText(""), rowLabel + "第 " + (i + 1) + " 项: ", warnings);
        }
    }
}
