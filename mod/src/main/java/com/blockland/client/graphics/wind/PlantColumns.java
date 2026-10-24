package com.blockland.client.graphics.wind;

import com.blockland.BlockLand;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Plant columns around the camera (rendering spec §18): for every block column of a 128 × 128 window, addressed by
 * world x, z modulo 128, the longest run of blocks that grow up (sugar cane, bamboo, kelp, twisting vines) and the
 * longest that hang down (vines, cave and weeping vines), so the terrain shader bends a whole column as one stem from its
 * base (or its top). Texel RGBA8: RG = the up run, BA = the hanging run, each 16 bits = (bottom or top y + 65) | length
 * << 9, 0 = none. Rescanned a few chunk columns a tick (round robin over the window; sections that cannot hold such a
 * block are skipped by their palette), uploaded when something changed.
 */
final class PlantColumns {
    static final int SIZE = 128;
    private static final int CHUNKS = SIZE / 16;
    /** Chunk columns rescanned per tick: the whole window about every 0.8 s. */
    private static final int BUDGET = 4;
    /** Vertical reach around the camera (blocks). */
    private static final int REACH = 64;

    private final int[] texels = new int[SIZE * SIZE];
    private Set<Block> up = Set.of(), hang = Set.of();
    private int cursor;
    private boolean dirty = true;
    @Nullable
    private GpuTexture texture;

    void configure(Set<Block> upBlocks, Set<Block> hangBlocks) {
        up = upBlocks;
        hang = hangBlocks;
        clear();
    }

    void clear() {
        java.util.Arrays.fill(texels, 0);
        dirty = true;
    }

    void tick(ClientLevel level, int camX, int camY, int camZ) {
        if (up.isEmpty() && hang.isEmpty()) return;
        int originX = (camX >> 4) - CHUNKS / 2, originZ = (camZ >> 4) - CHUNKS / 2;
        for (int n = 0; n < BUDGET; n++) {
            int i = cursor;
            cursor = (cursor + 1) % (CHUNKS * CHUNKS);
            scan(level, originX + i % CHUNKS, originZ + i / CHUNKS, camY);
        }
    }

    private boolean column(BlockState state) {
        Block block = state.getBlock();
        return up.contains(block) || hang.contains(block);
    }

    private void scan(ClientLevel level, int chunkX, int chunkZ, int camY) {
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        int bottom = Math.max(level.getMinSectionY(), (camY - REACH) >> 4), top = Math.min(level.getMaxSectionY(), (camY + REACH) >> 4);
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        if (chunk != null) {
            for (int sy = bottom; sy <= top; sy++) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                if (section.hasOnlyAir() || !section.maybeHas(this::column)) continue;
                lowest = Math.min(lowest, sy);
                highest = Math.max(highest, sy);
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int value = lowest > highest ? 0 : runs(chunk, x, z, lowest << 4, (highest << 4) + 15);
                int index = ((chunkZ * 16 + z) & (SIZE - 1)) * SIZE + ((chunkX * 16 + x) & (SIZE - 1));
                if (texels[index] != value) {
                    texels[index] = value;
                    dirty = true;
                }
            }
        }
    }

    /** The longest up run and the longest hanging run of one column, packed as the texel (ABGR). */
    private int runs(LevelChunk chunk, int x, int z, int fromY, int toY) {
        int upBase = 0, upLength = 0, hangTop = 0, hangLength = 0;
        int runStart = 0, runKind = 0; // 1 up, 2 hang
        for (int y = fromY; y <= toY + 1; y++) {
            int kind = 0;
            if (y <= toY) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                Block block = section.getBlockState(x, y & 15, z).getBlock();
                kind = up.contains(block) ? 1 : hang.contains(block) ? 2 : 0;
            }
            if (kind != runKind) {
                int length = y - runStart;
                if (runKind == 1 && length > upLength) {
                    upBase = runStart;
                    upLength = length;
                } else if (runKind == 2 && length > hangLength) {
                    hangTop = y - 1;
                    hangLength = length;
                }
                runStart = y;
                runKind = kind;
            }
        }
        int upBits = upLength == 0 ? 0 : (upBase + 65) & 511 | Math.min(upLength, 127) << 9;
        int hangBits = hangLength == 0 ? 0 : (hangTop + 65) & 511 | Math.min(hangLength, 127) << 9;
        return (hangBits >> 8 & 255) << 24 | (hangBits & 255) << 16 | (upBits >> 8 & 255) << 8 | upBits & 255;
    }

    /** Uploads what changed (outside any render pass) and returns the texture, or null before the first upload. */
    @Nullable
    GpuTexture upload() {
        if (!dirty && texture != null && !texture.isClosed()) return texture;
        try {
            if (texture == null || texture.isClosed()) {
                texture = RenderSystem.getDevice().createTexture(() -> "BlockLand plant columns", GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_COPY_DST, TextureFormat.RGBA8, SIZE, SIZE, 1, 1);
                texture.setTextureFilter(FilterMode.NEAREST, false);
            }
            try (NativeImage image = new NativeImage(SIZE, SIZE, false)) {
                for (int i = 0; i < texels.length; i++) image.setPixelABGR(i % SIZE, i / SIZE, texels[i]);
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
            }
            dirty = false;
        } catch (RuntimeException e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot upload the plant columns", e);
            texture = null;
        }
        return texture;
    }
}
