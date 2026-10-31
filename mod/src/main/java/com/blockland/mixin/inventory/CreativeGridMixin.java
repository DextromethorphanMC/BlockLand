package com.blockland.mixin.inventory;

import com.blockland.client.backpack.BlockLandCreativeScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * The creative item grid is {@link BlockLandCreativeScreen#GRID_ROWS} rows instead of vanilla's 5 (UI spec §1): the
 * picker's shared container holds that many cells, and the hotbar sync after a drag finds the hotbar after them.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeGridMixin {
    @ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 45))
    private static int blockland$containerSize(int cells) {
        return BlockLandCreativeScreen.GRID_ROWS * 9;
    }

    @ModifyConstant(method = "slotClicked", constant = @Constant(intValue = 45))
    private int blockland$hotbarStart(int start) {
        return BlockLandCreativeScreen.GRID_ROWS * 9;
    }
}
