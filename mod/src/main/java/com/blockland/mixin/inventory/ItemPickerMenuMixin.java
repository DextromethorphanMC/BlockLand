package com.blockland.mixin.inventory;

import com.blockland.client.backpack.BlockLandCreativeScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Vanilla's creative item picker with {@link BlockLandCreativeScreen#GRID_ROWS} rows of cells (UI spec §1): the cells it
 * adds, the rows it fills on scrolling, how far it scrolls and whether it scrolls at all.
 */
@Mixin(CreativeModeInventoryScreen.ItemPickerMenu.class)
public abstract class ItemPickerMenuMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 5))
    private int blockland$rowsAdded(int rows) {
        return BlockLandCreativeScreen.GRID_ROWS;
    }

    @ModifyConstant(method = "calculateRowCount", constant = @Constant(intValue = 5))
    private int blockland$rowsShown(int rows) {
        return BlockLandCreativeScreen.GRID_ROWS;
    }

    @ModifyConstant(method = "scrollTo", constant = @Constant(intValue = 5))
    private int blockland$rowsFilled(int rows) {
        return BlockLandCreativeScreen.GRID_ROWS;
    }

    @ModifyConstant(method = "canScroll", constant = @Constant(intValue = 45))
    private int blockland$cellsShown(int cells) {
        return BlockLandCreativeScreen.GRID_ROWS * 9;
    }
}
