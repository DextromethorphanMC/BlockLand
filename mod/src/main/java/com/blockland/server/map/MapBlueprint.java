package com.blockland.server.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Format-independent, in-memory representation of a map: every non-air block with a position
 * relative to the map origin. Produced by a {@link MapFormat}, consumed by paste jobs.
 */
public record MapBlueprint(Vec3i size, List<Placement> blocks) {

    public record Placement(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntity) {
    }
}
