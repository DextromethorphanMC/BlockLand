package com.blockland.server.farterrain;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;

/**
 * Saved chunks as the far terrain reads them (rendering spec §22, §23): a chunk written by an older game version is
 * brought up to the current one with the game's own data fixers first (as loading it would), so a world carried over
 * from an older version shows its real chunks instead of estimates. Worker threads.
 */
final class SavedChunks {
    private SavedChunks() {
    }

    /** The tag in the current data version (the same tag when it already is; a chunk storage's tag is a copy). */
    static CompoundTag current(ServerLevel level, CompoundTag tag) {
        if (NbtUtils.getDataVersion(tag, -1) == SharedConstants.getCurrentVersion().dataVersion().version()) return tag;
        return level.getChunkSource().chunkMap.upgradeChunkTag(level.dimension(), () -> level.getServer().overworld().getDataStorage(), tag,
                level.getChunkSource().getGenerator().getTypeNameForDataFixer());
    }
}
