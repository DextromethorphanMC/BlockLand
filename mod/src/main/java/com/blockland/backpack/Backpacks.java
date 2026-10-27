package com.blockland.backpack;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Where a backpack is (UI spec §2): worn in the body's {@link EquipmentSlot#BODY} slot, which players otherwise never use
 * — vanilla saves it with the player, drops it on death (unless the inventory is kept) and clears it with the inventory.
 * Its contents live on the stack as {@code minecraft:container}.
 */
public final class Backpacks {
    public static final EquipmentSlot SLOT = EquipmentSlot.BODY;

    private Backpacks() {
    }

    public static boolean isBackpack(ItemStack stack) {
        return stack.getItem() instanceof BackpackItem;
    }

    /** The backpack the entity wears, or {@link ItemStack#EMPTY}. */
    public static ItemStack worn(LivingEntity entity) {
        ItemStack stack = entity.getItemBySlot(SLOT);
        return isBackpack(stack) ? stack : ItemStack.EMPTY;
    }

    public static void wear(LivingEntity entity, ItemStack backpack) {
        entity.setItemSlot(SLOT, backpack);
    }

    /** Storage slots of a backpack stack (0 for anything else). */
    public static int capacity(ItemStack stack) {
        return stack.getItem() instanceof BackpackItem item ? item.pack().slots() : 0;
    }

    /** Copies what the backpack holds into {@code into} (its first {@link #capacity} slots; the rest cleared). */
    public static void read(ItemStack backpack, NonNullList<ItemStack> into) {
        for (int i = 0; i < into.size(); i++) into.set(i, ItemStack.EMPTY);
        ItemContainerContents contents = backpack.get(DataComponents.CONTAINER);
        if (contents != null) contents.copyInto(into);
    }

    /** Stores the first {@link #capacity} slots of {@code items} on the backpack; returns what was stored. */
    public static ItemContainerContents write(ItemStack backpack, NonNullList<ItemStack> items) {
        int capacity = Math.min(capacity(backpack), items.size());
        ItemContainerContents contents = ItemContainerContents.fromItems(items.subList(0, capacity));
        backpack.set(DataComponents.CONTAINER, contents);
        return contents;
    }

    /** How many of the backpack's slots hold something. */
    public static int usedSlots(ItemStack backpack) {
        ItemContainerContents contents = backpack.get(DataComponents.CONTAINER);
        if (contents == null) return 0;
        int used = 0;
        for (ItemStack ignored : contents.nonEmptyItems()) used++;
        return used;
    }

    /** Whether {@code stack} may go into a backpack: no backpacks in backpacks, nor anything that refuses containers. */
    public static boolean fitsInside(ItemStack stack) {
        return !isBackpack(stack) && stack.getItem().canFitInsideContainerItems();
    }
}
