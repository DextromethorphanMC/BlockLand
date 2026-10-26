package com.blockland.server.world;

import com.blockland.BlockLand;
import com.blockland.server.map.MapBlueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.List;

/** Places a {@link MapBlueprint} at an origin, a few thousand blocks per tick. */
public final class PasteJob implements WorldJob {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ServerLevel level;
    private final BlockPos origin;
    private final List<MapBlueprint.Placement> blocks;
    private final BoundingBox area;
    private int cursor;

    public PasteJob(ServerLevel level, BlockPos origin, MapBlueprint blueprint) {
        this.level = level;
        this.origin = origin;
        this.blocks = blueprint.blocks();
        this.area = new BoundingBox(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + Math.max(0, blueprint.size().getX() - 1),
                origin.getY() + Math.max(0, blueprint.size().getY() - 1), origin.getZ() + Math.max(0, blueprint.size().getZ() - 1));
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    @Override
    public BoundingBox area() {
        return area;
    }

    @Override
    public int step(int budget) {
        int end = Math.min(blocks.size(), cursor + budget);
        int done = end - cursor;
        for (; cursor < end; cursor++) {
            MapBlueprint.Placement placement = blocks.get(cursor);
            BlockPos pos = origin.offset(placement.pos());
            level.setBlock(pos, placement.state(), FLAGS);
            if (placement.blockEntity() != null) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    try {
                        blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), placement.blockEntity()));
                        blockEntity.setChanged();
                    } catch (Exception e) {
                        BlockLand.LOGGER.debug("[BlockLand] Could not load block entity at {}", pos, e);
                    }
                }
            }
        }
        return done;
    }

    @Override
    public boolean isDone() {
        return cursor >= blocks.size();
    }
}
