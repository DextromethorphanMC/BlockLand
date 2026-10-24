package com.blockland.client.graphics.raytrace;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The blocks around the camera as voxels for the ray tracer (rendering spec §4): a box of chunk sections centred on the
 * camera, kept in two RGBA8 atlases where each 16³ section is one 64×64 tile (block (x, y, z) of a section at
 * (x + 16 (y mod 4), z + 16 (y div 4)) in its tile) — one upload per section and atlas.
 * <ul>
 *   <li>colour atlas: RGB the block's average colour (display colours), A its kind ({@link BlockVoxels});</li>
 *   <li>light atlas: R sky light, G block light (Minecraft's own light levels, of every cell, air included: a ray that
 *   hits a face is lit by the cell in front of it), B the block's own emission, all as level / 15.</li>
 * </ul>
 * Tiles are addressed toroidally (section coordinate modulo the box size), so moving never shifts data: sections that
 * enter the box are filled nearest first, and every section is refreshed in turn so block changes and light updates
 * arrive within a fraction of a second, all inside a per-frame time budget.
 */
final class VoxelWorld implements AutoCloseable {
    static final int TILE = 64, COLUMNS = 32, HEIGHT_SECTIONS = 8;
    private static final long EMPTY = Long.MIN_VALUE;

    private final BlockVoxels palette = new BlockVoxels();
    private final IntBuffer colors = MemoryUtil.memAllocInt(TILE * TILE);
    private final IntBuffer lights = MemoryUtil.memAllocInt(TILE * TILE);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    @Nullable
    private GpuTexture colorAtlas, lightAtlas;
    @Nullable
    private GpuTextureView colorView, lightView;
    private int nx, ny = HEIGHT_SECTIONS, nz;
    private long[] slots = new long[0];
    private boolean[] complete = new boolean[0];
    /** Section offsets from the camera's section, nearest first (the fill and refresh order). */
    private int[][] order = new int[0][];
    private int cornerX, cornerY, cornerZ;
    private int refresh;
    @Nullable
    private ClientLevel level;
    private int filled;

    /** Sections across the box horizontally for a tracing distance in blocks (vertical: {@link #HEIGHT_SECTIONS}). */
    static int sectionsFor(float distance) {
        return Math.max(6, Math.min(16, 2 * (int) Math.ceil(distance / 16f) + 2));
    }

    /**
     * Follows the camera and fills or refreshes sections until the budget is spent.
     *
     * @param sections box width in sections
     */
    void update(ClientLevel level, double camX, double camY, double camZ, int sections, long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        if (level != this.level || sections != nx || colorAtlas == null) reset(level, sections);
        int cx = Math.floorDiv((int) Math.floor(camX), 16), cy = Math.floorDiv((int) Math.floor(camY), 16);
        int cz = Math.floorDiv((int) Math.floor(camZ), 16);
        cornerX = cx - nx / 2;
        cornerY = Math.max(level.getMinSectionY(), Math.min(level.getMaxSectionY() - ny + 1, cy - ny / 2));
        cornerZ = cz - nz / 2;

        // sections that are new to their tile, nearest first
        for (int[] offset : order) {
            if (System.nanoTime() > deadline) return;
            int sx = cx + offset[0], sy = cy + offset[1], sz = cz + offset[2];
            if (!inside(sx, sy, sz)) continue;
            int slot = slot(sx, sy, sz);
            if (slots[slot] != key(sx, sy, sz)) fill(slot, sx, sy, sz);
        }
        // then everything in turn (block and light changes), the nearest sections more often
        int near = Math.min(order.length, 27);
        for (int i = 0; i < 2 && near > 0; i++) refreshOffset(cx, cy, cz, order[(refresh + i) % near]);
        for (int n = 0; n < order.length && System.nanoTime() < deadline; n++) {
            refresh = (refresh + 1) % order.length;
            refreshOffset(cx, cy, cz, order[refresh]);
        }
    }

    private void refreshOffset(int cx, int cy, int cz, int[] offset) {
        int sx = cx + offset[0], sy = cy + offset[1], sz = cz + offset[2];
        if (inside(sx, sy, sz)) fill(slot(sx, sy, sz), sx, sy, sz);
    }

    private boolean inside(int sx, int sy, int sz) {
        return sx >= cornerX && sx < cornerX + nx && sy >= cornerY && sy < cornerY + ny && sz >= cornerZ && sz < cornerZ + nz;
    }

    private int slot(int sx, int sy, int sz) {
        return Math.floorMod(sx, nx) + nx * (Math.floorMod(sz, nz) + nz * Math.floorMod(sy, ny));
    }

    private static long key(int sx, int sy, int sz) {
        return SectionPos.asLong(sx, sy, sz);
    }

    /** One section into its tile: colours and light, or air when its chunk is not loaded (retried on refresh). */
    private void fill(int slot, int sx, int sy, int sz) {
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        int index = sy - level.getMinSectionY();
        LevelChunkSection section = chunk != null && index >= 0 && index < chunk.getSectionsCount() ? chunk.getSection(index) : null;
        DataLayer sky = chunk == null ? null : level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(sx, sy, sz));
        DataLayer block = chunk == null ? null : level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(sx, sy, sz));
        // no sky data: open sky above everything the chunk has, darkness below
        int skyDefault = chunk != null && index > chunk.getHighestFilledSectionIndex() ? 15 : 0;
        boolean air = section == null || section.hasOnlyAir();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int texel = (x + 16 * (y & 3)) + TILE * (z + 16 * (y >> 2));
                    int color = 0, emission = 0;
                    if (!air) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!state.isAir()) {
                            color = palette.tinted(state)
                                    ? palette.voxel(state, level, pos.set(sx * 16 + x, sy * 16 + y, sz * 16 + z))
                                    : palette.voxel(state);
                            emission = state.getLightEmission();
                        }
                    }
                    int s = sky == null ? skyDefault : sky.get(x, y, z);
                    int b = block == null ? 0 : block.get(x, y, z);
                    colors.put(texel, color);
                    lights.put(texel, 0xFF000000 | (emission * 17) << 16 | (b * 17) << 8 | s * 17);
                }
            }
        }
        int tileX = (slot % COLUMNS) * TILE, tileY = (slot / COLUMNS) * TILE;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToTexture(colorAtlas, colors, NativeImage.Format.RGBA, 0, 0, tileX, tileY, TILE, TILE);
        encoder.writeToTexture(lightAtlas, lights, NativeImage.Format.RGBA, 0, 0, tileX, tileY, TILE, TILE);
        if (slots[slot] == EMPTY) filled++;
        slots[slot] = key(sx, sy, sz);
        complete[slot] = chunk != null;
    }

    private void reset(ClientLevel level, int sections) {
        closeTextures();
        // a new level may come with other resources (block colours)
        if (level != this.level) palette.clear();
        this.level = level;
        nx = nz = sections;
        int tiles = nx * ny * nz;
        int rows = (tiles + COLUMNS - 1) / COLUMNS;
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
        colorAtlas = RenderSystem.getDevice().createTexture("BlockLand voxel colours", usage, TextureFormat.RGBA8, COLUMNS * TILE, rows * TILE, 1, 1);
        lightAtlas = RenderSystem.getDevice().createTexture("BlockLand voxel light", usage, TextureFormat.RGBA8, COLUMNS * TILE, rows * TILE, 1, 1);
        for (GpuTexture atlas : new GpuTexture[]{colorAtlas, lightAtlas}) {
            atlas.setTextureFilter(FilterMode.NEAREST, false);
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(atlas, 0);
        }
        colorView = RenderSystem.getDevice().createTextureView(colorAtlas);
        lightView = RenderSystem.getDevice().createTextureView(lightAtlas);
        slots = new long[tiles];
        java.util.Arrays.fill(slots, EMPTY);
        complete = new boolean[tiles];
        filled = 0;
        refresh = 0;
        List<int[]> offsets = new ArrayList<>();
        for (int y = -ny / 2; y < ny - ny / 2; y++) {
            for (int z = -nz / 2; z < nz - nz / 2; z++) {
                for (int x = -nx / 2; x < nx - nx / 2; x++) offsets.add(new int[]{x, y, z});
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1] * 2 + o[2] * o[2]));
        order = offsets.toArray(int[][]::new);
    }

    @Nullable
    GpuTextureView colors() {
        return colorView;
    }

    @Nullable
    GpuTextureView light() {
        return lightView;
    }

    int sectionsX() {
        return nx;
    }

    int sectionsY() {
        return ny;
    }

    int sectionsZ() {
        return nz;
    }

    int cornerX() {
        return cornerX;
    }

    int cornerY() {
        return cornerY;
    }

    int cornerZ() {
        return cornerZ;
    }

    /** Share of the box's tiles that hold their section (0..1). */
    float readiness() {
        return slots.length == 0 ? 0 : filled / (float) slots.length;
    }

    private void closeTextures() {
        if (colorView != null) colorView.close();
        if (lightView != null) lightView.close();
        if (colorAtlas != null) colorAtlas.close();
        if (lightAtlas != null) lightAtlas.close();
        colorView = lightView = null;
        colorAtlas = lightAtlas = null;
    }

    @Override
    public void close() {
        closeTextures();
        level = null;
        slots = new long[0];
    }

    void free() {
        close();
        MemoryUtil.memFree(colors);
        MemoryUtil.memFree(lights);
    }
}
