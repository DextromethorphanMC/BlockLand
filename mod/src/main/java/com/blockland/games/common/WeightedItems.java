package com.blockland.games.common;

import com.blockland.BlockLand;
import com.blockland.server.item.ItemStackFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A weighted item table from a mode's settings, {@code [{item, min, max, weight}]} with the item in /give syntax (e.g.
 * {@code minecraft:bow[enchantments={power:1}]}): every draw picks one row with chance weight / total and a count between
 * min and max. Rows naming items the server does not know are skipped (and logged once). Mirrors the backend's
 * {@code WeightedLoot}.
 */
public final class WeightedItems {
    /** Draws that land on unknown items before a draw gives up. */
    private static final int ATTEMPTS = 4;

    /** One row: an item spec, how many of it, how often relative to the others. */
    public record Entry(String item, int min, int max, int weight) {
    }

    private final List<Entry> entries;
    private final int totalWeight;
    private final ItemStackFactory items;
    private final Set<String> warned = new HashSet<>();

    public WeightedItems(List<Entry> entries, ItemStackFactory items) {
        this.entries = List.copyOf(entries);
        this.totalWeight = entries.stream().mapToInt(Entry::weight).sum();
        this.items = items;
    }

    /** The rows under {@code key} (broken rows are skipped: the backend validates them). */
    public static List<Entry> parse(JsonObject json, String key) {
        List<Entry> entries = new ArrayList<>();
        if (json == null || !json.has(key) || !json.get(key).isJsonArray()) return entries;
        for (JsonElement e : json.getAsJsonArray(key)) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("item") || !o.get("item").isJsonPrimitive()) continue;
            int min = TeamArenaSettings.intOr(o, "min", 1, 1, 64);
            entries.add(new Entry(o.get("item").getAsString(), min, Math.max(min, TeamArenaSettings.intOr(o, "max", min, 1, 64)),
                    TeamArenaSettings.intOr(o, "weight", 10, 1, 1000)));
        }
        return entries;
    }

    public boolean isEmpty() {
        return totalWeight <= 0;
    }

    /** One stack from the table, empty when the table is empty or only lands on items the server does not know. */
    public ItemStack draw(RandomSource random) {
        if (totalWeight <= 0) return ItemStack.EMPTY;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Entry entry = pick(random);
            int amount = entry.min() + (entry.max() > entry.min() ? random.nextInt(entry.max() - entry.min() + 1) : 0);
            ItemStack stack = items.create(entry.item(), amount).orElse(ItemStack.EMPTY);
            if (!stack.isEmpty()) return stack;
            if (warned.add(entry.item())) BlockLand.LOGGER.warn("[BlockLand] Item table: unknown item {}", entry.item());
        }
        return ItemStack.EMPTY;
    }

    private Entry pick(RandomSource random) {
        int pick = random.nextInt(totalWeight);
        for (Entry entry : entries) {
            pick -= entry.weight();
            if (pick < 0) return entry;
        }
        return entries.get(entries.size() - 1);
    }
}
