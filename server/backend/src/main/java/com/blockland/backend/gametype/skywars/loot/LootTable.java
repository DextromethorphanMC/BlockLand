package com.blockland.backend.gametype.skywars.loot;

import java.time.Instant;
import java.util.List;

/**
 * SkyWars chest contents. Every entry is rolled independently with {@code chance} percent; the resulting
 * number of stacks is then clamped to [minItems, maxItems] (0 = no limit).
 */
public record LootTable(
        String id,
        String name,
        int minItems,
        int maxItems,
        List<LootEntry> entries,
        Instant updatedAt
) {
    /**
     * @param item item id, optionally with components in /give syntax,
     *             e.g. {@code minecraft:diamond_sword[enchantments={sharpness:2}]}
     */
    public record LootEntry(String item, int minCount, int maxCount, double chance) {
    }
}
