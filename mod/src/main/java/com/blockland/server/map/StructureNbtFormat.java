package com.blockland.server.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Vanilla structure files (structure block / {@code .nbt}). Older versions are upgraded with the DataFixer. */
public final class StructureNbtFormat implements MapFormat {

    @Override
    public String id() {
        return "nbt";
    }

    @Override
    public MapBlueprint read(CompoundTag root, Context context, Predicate<Block> skipBlocks) {
        int dataVersion = NbtUtils.getDataVersion(root, 500);
        CompoundTag tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(context.dataFixer(), root, dataVersion);

        ListTag sizeTag = tag.getListOrEmpty("size");
        Vec3i size = new Vec3i(sizeTag.getIntOr(0, 0), sizeTag.getIntOr(1, 0), sizeTag.getIntOr(2, 0));

        ListTag paletteTag = tag.getListOrEmpty("palette");
        if (paletteTag.isEmpty()) {
            // multi-palette structures (e.g. shipwrecks): use the first variant
            paletteTag = tag.getListOrEmpty("palettes").getListOrEmpty(0);
        }
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = NbtUtils.readBlockState(context.blocks(), paletteTag.getCompoundOrEmpty(i));
        }

        ListTag blocksTag = tag.getListOrEmpty("blocks");
        List<MapBlueprint.Placement> placements = new ArrayList<>(blocksTag.size());
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag entry = blocksTag.getCompoundOrEmpty(i);
            int stateIndex = entry.getIntOr("state", -1);
            if (stateIndex < 0 || stateIndex >= palette.length) continue;
            BlockState state = palette[stateIndex];
            if (state.isAir() || skipBlocks.test(state.getBlock())) continue;
            ListTag pos = entry.getListOrEmpty("pos");
            BlockPos blockPos = new BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0));
            CompoundTag nbt = entry.getCompound("nbt").orElse(null);
            placements.add(new MapBlueprint.Placement(blockPos, state, nbt));
        }
        return new MapBlueprint(size, placements);
    }
}
