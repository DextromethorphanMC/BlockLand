package com.blockland.games.battleroyale;

import com.blockland.BlockLand;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.item.ItemStackFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where loot lies on a 大逃杀 map and what it is: the map's {@code settings.lootSpots} (relative block positions,
 * edited in the admin map editor) and rolls from the mode's weighted table. Items the table names but the server does
 * not know are skipped (and logged once).
 */
final class BrLoot {
    private final List<BattleRoyaleSettings.LootEntry> table;
    private final int totalWeight;
    private final ItemStackFactory items;
    private final Set<String> warned = new HashSet<>();

    BrLoot(List<BattleRoyaleSettings.LootEntry> table, ItemStackFactory items) {
        this.table = table;
        this.totalWeight = table.stream().mapToInt(BattleRoyaleSettings.LootEntry::weight).sum();
        this.items = items;
    }

    /** The configured spots, relative to the map origin. */
    static List<BlockPos> spots(MapDef map) {
        List<BlockPos> spots = new ArrayList<>();
        JsonObject settings = map.settings;
        if (settings == null || !settings.has("lootSpots") || !settings.get("lootSpots").isJsonArray()) return spots;
        for (JsonElement e : settings.getAsJsonArray("lootSpots")) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            try {
                spots.add(new BlockPos((int) Math.floor(p.get("x").getAsDouble()), (int) Math.floor(p.get("y").getAsDouble()),
                        (int) Math.floor(p.get("z").getAsDouble())));
            } catch (RuntimeException ignored) {
                // a broken entry: the backend validates them, skip it here
            }
        }
        return spots;
    }

    /** {@code count} stacks rolled from the table (fewer when it names unknown items). */
    List<ItemStack> roll(int count, RandomSource random) {
        List<ItemStack> out = new ArrayList<>();
        if (totalWeight <= 0) return out;
        for (int i = 0; i < count; i++) {
            int pick = random.nextInt(totalWeight);
            for (BattleRoyaleSettings.LootEntry entry : table) {
                pick -= entry.weight();
                if (pick >= 0) continue;
                int amount = entry.min() + (entry.max() > entry.min() ? random.nextInt(entry.max() - entry.min() + 1) : 0);
                ItemStack stack = items.create(entry.item(), amount).orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) out.add(stack);
                else if (warned.add(entry.item())) BlockLand.LOGGER.warn("[BlockLand] Battle royale loot: unknown item {}", entry.item());
                break;
            }
        }
        return out;
    }
}
