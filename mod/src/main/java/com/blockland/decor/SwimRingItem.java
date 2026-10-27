package com.blockland.decor;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The swim ring's item: used on a block it is placed as any block is; used towards water (no block in reach) it is
 * laid on the water's surface, in the block above it, as a lily pad is.
 */
public class SwimRingItem extends BlockItem {
    public SwimRingItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK || level.getFluidState(hit.getBlockPos()).getType() != Fluids.WATER) {
            return super.use(level, player, hand);
        }
        return super.useOn(new UseOnContext(player, hand, hit.withPosition(hit.getBlockPos().above())));
    }
}
