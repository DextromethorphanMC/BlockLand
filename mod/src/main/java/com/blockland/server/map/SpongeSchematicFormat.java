package com.blockland.server.map;

import com.blockland.BlockLand;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Sponge schematic v2 / v3 ({@code .schem}), the format exported by WorldEdit. */
public final class SpongeSchematicFormat implements MapFormat {

    @Override
    public String id() {
        return "schem";
    }

    @Override
    public MapBlueprint read(CompoundTag root, Context context, Predicate<Block> skipBlocks) {
        // v3 wraps everything in a "Schematic" compound and moves block data into "Blocks"
        CompoundTag schematic = root.getCompound("Schematic").orElse(root);
        int width = schematic.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = schematic.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = schematic.getShortOr("Length", (short) 0) & 0xFFFF;

        CompoundTag paletteTag;
        byte[] data;
        ListTag blockEntities;
        if (schematic.getCompound("Blocks").isPresent()) {
            CompoundTag blocks = schematic.getCompoundOrEmpty("Blocks");
            paletteTag = blocks.getCompoundOrEmpty("Palette");
            data = blocks.getByteArray("Data").orElse(new byte[0]);
            blockEntities = blocks.getListOrEmpty("BlockEntities");
        } else {
            paletteTag = schematic.getCompoundOrEmpty("Palette");
            data = schematic.getByteArray("BlockData").orElse(new byte[0]);
            blockEntities = schematic.getListOrEmpty("BlockEntities");
        }

        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : paletteTag.keySet()) {
            int index = paletteTag.getIntOr(key, -1);
            palette.put(index, parseState(context, key));
        }

        Map<BlockPos, CompoundTag> entityData = new HashMap<>();
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag entry = blockEntities.getCompoundOrEmpty(i);
            int[] pos = entry.getIntArray("Pos").orElse(null);
            if (pos == null || pos.length < 3) continue;
            CompoundTag nbt = entry.getCompound("Data").map(CompoundTag::copy).orElseGet(() -> {
                CompoundTag copy = entry.copy();
                copy.remove("Pos");
                copy.remove("Id");
                return copy;
            });
            entry.getString("Id").ifPresent(id -> nbt.putString("id", id));
            entityData.put(new BlockPos(pos[0], pos[1], pos[2]), nbt);
        }

        List<MapBlueprint.Placement> placements = new ArrayList<>();
        int index = 0;
        int cursor = 0;
        while (cursor < data.length) {
            // block data is a VarInt array in YZX order
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[cursor++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && cursor < data.length);

            int y = index / (width * length);
            int rem = index % (width * length);
            int z = rem / width;
            int x = rem % width;
            index++;

            BlockState state = palette.getOrDefault(value, Blocks.AIR.defaultBlockState());
            if (state.isAir() || skipBlocks.test(state.getBlock())) continue;
            BlockPos pos = new BlockPos(x, y, z);
            placements.add(new MapBlueprint.Placement(pos, state, entityData.get(pos)));
        }
        return new MapBlueprint(new Vec3i(width, height, length), placements);
    }

    private static BlockState parseState(Context context, String value) {
        try {
            return BlockStateParser.parseForBlock(context.blocks(), value, false).blockState();
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Unknown block state '{}' in schematic, using air", value);
            return Blocks.AIR.defaultBlockState();
        }
    }
}
