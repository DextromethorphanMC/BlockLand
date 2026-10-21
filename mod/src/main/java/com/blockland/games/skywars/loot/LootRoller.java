package com.blockland.games.skywars.loot;

import com.blockland.server.item.ItemStackFactory;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Rolls {@link LootTableDef}s and puts the result into containers. */
public final class LootRoller {
    private final ItemStackFactory items;

    public LootRoller(ItemStackFactory items) {
        this.items = items;
    }

    public List<ItemStack> roll(LootTableDef table, RandomSource random) {
        List<ItemStack> result = new ArrayList<>();
        if (table == null || table.entries == null || table.entries.isEmpty()) return result;

        for (LootTableDef.LootEntryDef entry : table.entries) {
            if (random.nextDouble() * 100.0 < entry.chance) {
                items.create(entry.item, count(entry, random)).ifPresent(result::add);
            }
        }

        // guarantee a minimum number of stacks by drawing extra entries weighted by their chance
        double totalWeight = table.entries.stream().mapToDouble(e -> Math.max(0, e.chance)).sum();
        int guard = 0;
        while (table.minItems > 0 && result.size() < table.minItems && totalWeight > 0 && guard++ < 100) {
            double pick = random.nextDouble() * totalWeight;
            for (LootTableDef.LootEntryDef entry : table.entries) {
                pick -= Math.max(0, entry.chance);
                if (pick <= 0) {
                    items.create(entry.item, count(entry, random)).ifPresent(result::add);
                    break;
                }
            }
        }

        if (table.maxItems > 0 && result.size() > table.maxItems) {
            Collections.shuffle(result, new java.util.Random(random.nextLong()));
            result = new ArrayList<>(result.subList(0, table.maxItems));
        }
        return result;
    }

    /** Clears the container and spreads the rolled items over random slots. */
    public void fill(Container container, LootTableDef table, RandomSource random) {
        container.clearContent();
        List<ItemStack> loot = roll(table, random);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) slots.add(i);
        Collections.shuffle(slots, new java.util.Random(random.nextLong()));
        for (int i = 0; i < loot.size() && i < slots.size(); i++) {
            container.setItem(slots.get(i), loot.get(i));
        }
        container.setChanged();
    }

    private static int count(LootTableDef.LootEntryDef entry, RandomSource random) {
        int min = Math.max(1, entry.minCount);
        int max = Math.max(min, entry.maxCount);
        return min + random.nextInt(max - min + 1);
    }
}
