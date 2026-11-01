package com.blockland.server.world;

import com.blockland.BlockLand;
import com.blockland.server.map.MapBlueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.TagValueInput;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Places a {@link MapBlueprint} at an origin, a few thousand blocks per tick. A falling block (sand, gravel, concrete
 * powder…) the map leaves with nothing under it gets an invisible barrier there first: arenas hang over the void, where
 * it would drop away — taking the floor with it and leaving the water it held hanging and pouring off (a safety net
 * for maps uploaded as they are; the backend's own generators never do it). The arena's clean-up box reaches below.
 */
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
        this.blocks = supported(blueprint.blocks());
        this.area = new BoundingBox(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + Math.max(0, blueprint.size().getX() - 1),
                origin.getY() + Math.max(0, blueprint.size().getY() - 1), origin.getZ() + Math.max(0, blueprint.size().getZ() - 1));
    }

    /** The blueprint's blocks, after a barrier under each falling block that has nothing under it in the map. */
    private static List<MapBlueprint.Placement> supported(List<MapBlueprint.Placement> blocks) {
        LongOpenHashSet filled = new LongOpenHashSet(blocks.size());
        for (MapBlueprint.Placement p : blocks) if (!p.state().isAir()) filled.add(p.pos().asLong());
        List<MapBlueprint.Placement> supports = new ArrayList<>();
        for (MapBlueprint.Placement p : blocks) {
            BlockState state = p.state();
            if (!(state.getBlock() instanceof FallingBlock)) continue;
            BlockPos below = p.pos().below();
            if (filled.add(below.asLong())) supports.add(new MapBlueprint.Placement(below, Blocks.BARRIER.defaultBlockState(), null));
        }
        if (supports.isEmpty()) return blocks;
        BlockLand.LOGGER.debug("[BlockLand] {} falling blocks of a map held up by barriers", supports.size());
        // the supports first, so nothing is ever placed without one
        supports.addAll(blocks);
        return supports;
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
