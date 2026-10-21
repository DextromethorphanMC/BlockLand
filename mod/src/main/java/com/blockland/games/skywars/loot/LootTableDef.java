package com.blockland.games.skywars.loot;

import java.util.ArrayList;
import java.util.List;

/**
 * Chest loot ("物资"). Every entry is rolled independently with its own chance;
 * the result is then clamped to [minItems, maxItems] stacks (0 = no limit).
 */
public final class LootTableDef {
    public String id;
    public String name;
    public int minItems;
    public int maxItems;
    public List<LootEntryDef> entries = new ArrayList<>();

    public static final class LootEntryDef {
        /** Item id with optional components, e.g. {@code minecraft:diamond_sword[enchantments={sharpness:2}]}. */
        public String item;
        public int minCount = 1;
        public int maxCount = 1;
        /** Probability in percent (0-100). */
        public double chance = 100;
    }
}
