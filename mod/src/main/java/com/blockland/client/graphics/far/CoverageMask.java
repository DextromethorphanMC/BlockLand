package com.blockland.client.graphics.far;

import com.blockland.BlockLand;
import com.blockland.farterrain.FarColumn;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Where the terrain is drawn by something better than the far tiles (rendering spec §22, §23): chunks vanilla draws itself
 * and chunks a placed real far chunk unit holds. Two textures round the camera, addressed modulo their size: one texel
 * per chunk out to {@link #FINE} / 2 chunks, and one per {@link #CELL} × {@link #CELL} chunks (set when every chunk of the
 * cell is covered) out to {@link #COARSE} × {@link #CELL} / 2. The masked far terrain program drops its fragments where
 * either says covered, so the far tiles fill exactly what is left open — chunks still loading, chunks the server has not
 * generated. Laid out again every few frames from what is drawn, uploaded only when it changed.
 */
final class CoverageMask {
    static final int FINE = 640, CELL = 16, COARSE = 544;
    private static final int REFRESH_FRAMES = 3;

    /** The placed units, as the mask reads them. */
    interface Units {
        void forEach(Visitor visitor);
    }

    interface Visitor {
        /** {@code present}: {@link FarColumn#present}, null when every chunk of the unit exists. */
        void unit(long key, @Nullable long[] present);
    }

    private final byte[] fine = new byte[FINE * FINE], coarse = new byte[COARSE * COARSE];
    private final byte[] lastFine = new byte[FINE * FINE], lastCoarse = new byte[COARSE * COARSE];
    private final short[] counts = new short[COARSE * COARSE];
    private final Layer fineLayer = new Layer("BlockLand far terrain coverage", FINE), coarseLayer = new Layer(
            "BlockLand far terrain coverage (coarse)", COARSE);
    private int frames = REFRESH_FRAMES;
    /** The camera's chunk the texels were laid out round. */
    int centreX, centreZ;

    /**
     * Vanilla draws the chunk this frame: it holds it, it lies within vanilla's view distance and one of its non-empty
     * sections is compiled. Vanilla compiles only the sections its visibility graph reaches, so a chunk behind a hill can
     * have its tree tops compiled and drawn while the section under its middle column never is: asking only for that
     * section (as this once did) left such a chunk to the far units too, and both drew its visible blocks (z-fighting). The
     * sections vanilla never compiled it never reached: hidden from the camera, they need no far unit.
     */
    static boolean vanillaDraws(ClientLevel level, LevelRenderer renderer, int cameraChunkX, int cameraChunkZ, int renderDistance,
                                int cx, int cz, BlockPos.MutableBlockPos pos) {
        if (!ChunkTrackingView.isInViewDistance(cameraChunkX, cameraChunkZ, renderDistance, cx, cz)) return false;
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) return false;
        // the middle column's surface section first (nearly always the answer), then every other non-empty one
        int surface = Math.max(level.getMinY(), chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8) - 1);
        if (renderer.isSectionCompiled(pos.set(cx * 16 + 8, surface, cz * 16 + 8))) return true;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null || sections[i].hasOnlyAir()) continue;
            if (renderer.isSectionCompiled(pos.set(cx * 16 + 8, level.getMinY() + i * 16 + 8, cz * 16 + 8))) return true;
        }
        return false;
    }

    /** Lays the mask out again round the camera chunk (every few frames, or at once when {@code now}). */
    void update(ClientLevel level, int cameraChunkX, int cameraChunkZ, int renderDistance, Units units, boolean now) {
        if (cameraChunkX != centreX || cameraChunkZ != centreZ) now = true;
        centreX = cameraChunkX;
        centreZ = cameraChunkZ;
        if (!now && ++frames < REFRESH_FRAMES) return;
        frames = 0;
        Arrays.fill(fine, (byte) 0);
        Arrays.fill(coarse, (byte) 0);
        Arrays.fill(counts, (short) 0);
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        int r = Math.min(FINE / 2 - 1, renderDistance + 2);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int cx = cameraChunkX + dx, cz = cameraChunkZ + dz;
                if (vanillaDraws(level, renderer, cameraChunkX, cameraChunkZ, renderDistance, cx, cz, pos)) fine[fineIndex(cx, cz)] = 1;
            }
        }
        units.forEach(this::unit);
        for (int i = 0; i < counts.length; i++) if (counts[i] >= CELL * CELL) coarse[i] = 1;
        if (!Arrays.equals(fine, lastFine)) {
            System.arraycopy(fine, 0, lastFine, 0, fine.length);
            fineLayer.dirty = true;
        }
        if (!Arrays.equals(coarse, lastCoarse)) {
            System.arraycopy(coarse, 0, lastCoarse, 0, coarse.length);
            coarseLayer.dirty = true;
        }
    }

    private void unit(long key, @Nullable long[] present) {
        int scale = FarColumn.scale(key), side = 1 << scale;
        int x0 = FarColumn.chunkX(key), z0 = FarColumn.chunkZ(key);
        // the fine texels: only the part within reach of the fine texture
        int half = FINE / 2 - 1;
        int fx0 = Math.max(x0, centreX - half), fx1 = Math.min(x0 + side - 1, centreX + half);
        int fz0 = Math.max(z0, centreZ - half), fz1 = Math.min(z0 + side - 1, centreZ + half);
        for (int cz = fz0; cz <= fz1; cz++) {
            for (int cx = fx0; cx <= fx1; cx++) {
                if (has(present, scale, cx - x0, cz - z0)) fine[fineIndex(cx, cz)] = 1;
            }
        }
        // the coarse cells: a unit of 16 chunks or more holds whole cells, a smaller one lies within one
        if (side >= CELL) {
            int cells = side / CELL;
            for (int k = 0; k < cells * cells; k++) {
                int ox = (k % cells) * CELL, oz = (k / cells) * CELL;
                int n = CELL * CELL;
                if (present != null) {
                    n = 0;
                    for (int dz = 0; dz < CELL; dz++) for (int dx = 0; dx < CELL; dx++) if (has(present, scale, ox + dx, oz + dz)) n++;
                }
                int index = coarseIndex(Math.floorDiv(x0 + ox, CELL), Math.floorDiv(z0 + oz, CELL));
                counts[index] = (short) Math.min(Short.MAX_VALUE, counts[index] + n);
            }
        } else {
            int n = side * side;
            if (present != null) {
                n = 0;
                for (long w : present) n += Long.bitCount(w);
            }
            int index = coarseIndex(Math.floorDiv(x0, CELL), Math.floorDiv(z0, CELL));
            counts[index] = (short) Math.min(Short.MAX_VALUE, counts[index] + n);
        }
    }

    private static boolean has(@Nullable long[] present, int scale, int dx, int dz) {
        if (present == null) return true;
        int bit = dx + (dz << scale);
        return (present[bit >> 6] >>> (bit & 63) & 1) != 0;
    }

    private static int fineIndex(int cx, int cz) {
        return Math.floorMod(cx, FINE) + Math.floorMod(cz, FINE) * FINE;
    }

    private static int coarseIndex(int cellX, int cellZ) {
        return Math.floorMod(cellX, COARSE) + Math.floorMod(cellZ, COARSE) * COARSE;
    }

    /** The fine texture (uploaded now if it changed), or null when it cannot be made. */
    @Nullable
    GpuTextureView fine() {
        return fineLayer.upload(fine);
    }

    @Nullable
    GpuTextureView coarse() {
        return coarseLayer.upload(coarse);
    }

    void close() {
        fineLayer.close();
        coarseLayer.close();
        frames = REFRESH_FRAMES;
    }

    /** One of the two textures. */
    private static final class Layer {
        private final String name;
        private final int size;
        @Nullable
        private GpuTexture texture;
        @Nullable
        private GpuTextureView view;
        boolean dirty = true;

        Layer(String name, int size) {
            this.name = name;
            this.size = size;
        }

        @Nullable
        GpuTextureView upload(byte[] texels) {
            try {
                if (texture == null || texture.isClosed()) {
                    texture = RenderSystem.getDevice().createTexture(() -> name, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                            TextureFormat.RGBA8, size, size, 1, 1);
                    texture.setTextureFilter(FilterMode.NEAREST, false);
                    view = RenderSystem.getDevice().createTextureView(texture);
                    dirty = true;
                }
                if (dirty) {
                    try (NativeImage image = new NativeImage(size, size, false)) {
                        long at = image.getPointer();
                        for (int i = 0; i < texels.length; i++) MemoryUtil.memPutInt(at + 4L * i, texels[i] != 0 ? 0xFFFFFFFF : 0);
                        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
                    }
                    dirty = false;
                }
            } catch (RuntimeException e) {
                BlockLand.LOGGER.error("[BlockLand] Cannot upload the far terrain coverage", e);
                close();
                return null;
            }
            return view;
        }

        void close() {
            if (view != null) view.close();
            if (texture != null) texture.close();
            view = null;
            texture = null;
            dirty = true;
        }
    }
}
