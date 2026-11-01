package com.blockland.mixin.terrain;

import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.resources.model.WeightedVariants;
import net.minecraft.util.random.WeightedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every variant of a weighted block model, for reading them all ({@code ENCLOSED_MODELS}). */
@Mixin(WeightedVariants.class)
public interface WeightedVariantsAccess {
    @Accessor("list")
    WeightedList<BlockStateModel> blockland$list();
}
