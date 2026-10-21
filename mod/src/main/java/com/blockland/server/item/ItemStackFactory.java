package com.blockland.server.item;

import com.blockland.BlockLand;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds item stacks from the same syntax as {@code /give}, so loot tables can contain enchantments
 * and other components, e.g. {@code minecraft:diamond_sword[enchantments={sharpness:2}]}.
 */
public final class ItemStackFactory {
    private final CommandBuildContext context;
    private final Map<String, Optional<ItemInput>> cache = new HashMap<>();

    public ItemStackFactory(HolderLookup.Provider registries) {
        this.context = Commands.createValidationContext(registries);
    }

    public Optional<ItemStack> create(String spec, int count) {
        if (spec == null || spec.isBlank()) return Optional.empty();
        Optional<ItemInput> input = cache.computeIfAbsent(spec.trim(), this::parse);
        return input.flatMap(in -> {
            try {
                ItemStack stack = in.createItemStack(1, false);
                stack.setCount(Math.max(1, Math.min(count, stack.getMaxStackSize())));
                return Optional.of(stack);
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    private Optional<ItemInput> parse(String spec) {
        try {
            return Optional.of(ItemArgument.item(context).parse(new StringReader(spec)));
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Invalid loot item '{}': {}", spec, e.getMessage());
            return Optional.empty();
        }
    }
}
