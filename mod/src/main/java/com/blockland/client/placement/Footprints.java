package com.blockland.client.placement;

import com.blockland.common.block.MultiBlockFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The blocks a placement state takes (rendering spec §26): what a {@link MultiBlockFootprint} block says, and the same
 * for vanilla's multi-block blocks — doors and two-block plants (the half above or below), beds (the head towards their
 * facing). Null for a block that takes only its own block.
 */
final class Footprints {
    private Footprints() {
    }

    @Nullable
    static List<BlockPos> of(BlockState state, BlockPos pos) {
        Block block = state.getBlock();
        if (block instanceof MultiBlockFootprint footprint) return footprint.footprint(state, pos);
        if ((block instanceof DoorBlock || block instanceof DoublePlantBlock) && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? List.of(pos, pos.above())
                    : List.of(pos, pos.below());
        }
        if (block instanceof BedBlock && state.hasProperty(BedBlock.PART)) {
            Direction facing = state.getValue(BedBlock.FACING);
            return state.getValue(BedBlock.PART) == BedPart.FOOT ? List.of(pos, pos.relative(facing))
                    : List.of(pos, pos.relative(facing.getOpposite()));
        }
        return null;
    }
}
