package com.blockland.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A block that occupies more than one block (rendering spec §26): it says which blocks it takes once placed, so the
 * placement preview ({@code client/placement}) can outline them without knowing the block. Vanilla's doors, beds and
 * two-block plants are covered by the preview's own adapter.
 */
public interface MultiBlockFootprint {
    /**
     * The absolute positions {@code state} would occupy if it were placed at {@code pos}, {@code pos} first.
     *
     * @param state the placement state ({@code getStateForPlacement}'s result: the part that goes at {@code pos})
     */
    List<BlockPos> footprint(BlockState state, BlockPos pos);
}
