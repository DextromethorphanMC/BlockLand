package com.blockland.games.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * Where newcomers and the dead appear in a generated world, found the way vanilla places the overworld's spawn: the
 * climate's spawn target (land, not ocean, near the origin), then a spiral of chunks around it until one has a spot a
 * player can stand on; failing that, the first dry surface on a wider spiral of columns. The terrain decides, so it is
 * the same spot every time the server starts.
 */
final class WorldSpawn {
    /** Vanilla's search: an 11 × 11 chunk spiral around the climate target. */
    private static final int CHUNK_RADIUS = 5;
    private static final int STEP = 24;
    private static final int TRIES = 96;

    private WorldSpawn() {
    }

    static BlockPos find(ServerLevel level) {
        ChunkPos target = new ChunkPos(level.getChunkSource().randomState().sampler().findSpawnPosition());
        BlockPos vanilla = spiralChunks(level, target);
        if (vanilla != null) return vanilla;
        BlockPos dry = spiralColumns(level, target.getMiddleBlockX(), target.getMiddleBlockZ());
        return dry != null ? dry : new BlockPos(target.getMiddleBlockX(), level.getSeaLevel() + 1, target.getMiddleBlockZ());
    }

    /** {@link PlayerRespawnLogic#getSpawnPosInChunk} over the chunks around {@code centre}, nearest first. */
    @Nullable
    private static BlockPos spiralChunks(ServerLevel level, ChunkPos centre) {
        int x = 0, z = 0, dx = 0, dz = -1;
        int side = 2 * CHUNK_RADIUS + 1;
        for (int i = 0; i < side * side; i++) {
            BlockPos spot = PlayerRespawnLogic.getSpawnPosInChunk(level, new ChunkPos(centre.x + x, centre.z + z));
            if (spot != null && dry(level, spot)) return spot;
            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int t = dx;
                dx = -dz;
                dz = t;
            }
            x += dx;
            z += dz;
        }
        return null;
    }

    /** The first dry surface spot on a wider spiral of columns (every {@value #STEP} blocks). */
    @Nullable
    private static BlockPos spiralColumns(ServerLevel level, int cx, int cz) {
        int x = 0, z = 0, dx = 0, dz = -1;
        for (int i = 0; i < TRIES; i++) {
            BlockPos spot = surface(level, cx + x * STEP, cz + z * STEP);
            if (spot != null && dry(level, spot)) return spot;
            // square spiral: turn at the corners
            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int t = dx;
                dx = -dz;
                dz = t;
            }
            x += dx;
            z += dz;
        }
        return null;
    }

    /** The block above the highest solid one at (x, z), or null when the column is empty. */
    @Nullable
    private static BlockPos surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return y <= level.getMinY() + 1 ? null : new BlockPos(x, y, z);
    }

    /** Standing on land: no water, lava or leaves underfoot, and room for a player. */
    private static boolean dry(ServerLevel level, BlockPos feet) {
        BlockState ground = level.getBlockState(feet.below());
        return ground.getFluidState().isEmpty() && !ground.is(BlockTags.LEAVES) && ground.isSolid()
                && level.getBlockState(feet).getFluidState().isEmpty() && level.getBlockState(feet.above()).isAir();
    }
}
