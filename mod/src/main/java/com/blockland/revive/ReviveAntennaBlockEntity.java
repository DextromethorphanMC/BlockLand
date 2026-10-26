package com.blockland.revive;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The lower half of a revive antenna: only there so the client draws the antenna (its renderer covers both blocks) and
 * blinks the beacon. Nothing is saved.
 */
public class ReviveAntennaBlockEntity extends BlockEntity {
    public ReviveAntennaBlockEntity(BlockPos pos, BlockState state) {
        super(ReviveContent.ANTENNA_ENTITY.get(), pos, state);
    }

    /** Both blocks: the renderer is culled against this, and the antenna is two blocks tall. */
    @Override
    public AABB getRenderBoundingBox() {
        BlockPos pos = getBlockPos();
        return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1);
    }
}
