package com.blockland.server.map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;

import java.util.function.Predicate;

/** A readable map file format. New formats are added by registering another implementation in {@link MapLoader}. */
public interface MapFormat {

    /** File extension / format id as stored in the backend, e.g. {@code nbt}. */
    String id();

    /**
     * @param root       decompressed NBT root of the file
     * @param skipBlocks blocks that must not end up in the blueprint (e.g. spawn markers)
     */
    MapBlueprint read(CompoundTag root, Context context, Predicate<Block> skipBlocks);

    record Context(HolderLookup<Block> blocks, com.mojang.datafixers.DataFixer dataFixer) {
    }
}
