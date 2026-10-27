package com.blockland.mixin.inventory;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The drag state the inventory screens need to draw slots and the carried stack themselves (UI spec §1): what vanilla's
 * {@code renderSlot} / {@code renderCarriedItem} read.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {
    @Accessor("clickedSlot")
    Slot blockland$clickedSlot();

    @Accessor("draggingItem")
    ItemStack blockland$draggingItem();

    @Accessor("isSplittingStack")
    boolean blockland$isSplittingStack();

    @Accessor("quickCraftingType")
    int blockland$quickCraftingType();

    @Accessor("quickCraftingRemainder")
    int blockland$quickCraftingRemainder();
}
