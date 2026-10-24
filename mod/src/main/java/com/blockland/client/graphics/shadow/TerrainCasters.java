package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * The terrain casters: the compiled chunk sections around the camera, drawn into the shadow map straight from the GPU
 * buffers the level renderer already holds (solid, cutout-mipped and cutout layers; water and other translucent blocks
 * cast no shadow). The level renderer only knows the sections the camera sees; the sun also needs those behind the
 * camera and above it, so they are looked up in its view area (a private field) by section position. Each section that
 * reaches any cascade drawn this frame gets one transform slot (its origin relative to the camera), shared by all
 * cascades.
 */
final class TerrainCasters {
    /** More would mean a huge shadow distance on a huge render distance: the farthest are left out. */
    private static final int MAX_SECTIONS = 6144;
    private static final ChunkSectionLayer[] CUTOUT_LAYERS = {ChunkSectionLayer.CUTOUT_MIPPED, ChunkSectionLayer.CUTOUT};
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);

    @Nullable
    private static final Field VIEW_AREA = field();

    private final SectionMesh[] meshes = new SectionMesh[MAX_SECTIONS];
    private final float[] centers = new float[MAX_SECTIONS * 3];
    private final DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[MAX_SECTIONS];
    @Nullable
    private GpuBufferSlice[] slices;
    private int count;
    /** Most sequential quad indices a section without its own index buffer needs. */
    private int maxSequentialIndices;
    private boolean warnedFull;
    private final Vector3f scratch = new Vector3f();
    private final Matrix4f inverse = new Matrix4f();

    private static Field field() {
        try {
            Field f = LevelRenderer.class.getDeclaredField("viewArea");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Level renderer has no readable view area ({}): terrain casts no sun shadows", e.toString());
            return null;
        }
    }

    static boolean supported() {
        return VIEW_AREA != null;
    }

    int count() {
        return count;
    }

    int maxSequentialIndices() {
        return maxSequentialIndices;
    }

    /**
     * Collects the sections inside {@code bounds} (the largest cascade drawn this frame) and writes their transforms.
     *
     * @return false when the view area cannot be read
     */
    boolean gather(Level level, Cascade bounds, double camX, double camY, double camZ) {
        count = 0;
        maxSequentialIndices = 0;
        slices = null;
        ViewArea area;
        try {
            area = VIEW_AREA == null ? null : (ViewArea) VIEW_AREA.get(Minecraft.getInstance().levelRenderer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        if (area == null || area.sections == null) return true;
        int viewDistance = area.getViewDistance();
        int sizeXZ = viewDistance * 2 + 1;
        int sizeY = level.getSectionsCount();
        SectionRenderDispatcher.RenderSection[] sections = area.sections;
        if (sections.length != sizeXZ * sizeY * sizeXZ) return true;
        SectionPos cameraSection = area.getCameraSectionPos();
        int minSectionY = level.getMinSectionY(), maxSectionY = level.getMaxSectionY();

        // the box's world bounds (camera-relative), from its eight corners
        inverse.set(bounds.view).invert();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            inverse.transformPosition((i & 1) == 0 ? -bounds.half : bounds.half, (i & 2) == 0 ? -bounds.half : bounds.half,
                    (i & 4) == 0 ? 0 : -bounds.depth, scratch);
            minX = Math.min(minX, scratch.x);
            minY = Math.min(minY, scratch.y);
            minZ = Math.min(minZ, scratch.z);
            maxX = Math.max(maxX, scratch.x);
            maxY = Math.max(maxY, scratch.y);
            maxZ = Math.max(maxZ, scratch.z);
        }
        int sx0 = Math.max(cameraSection.x() - viewDistance, SectionPos.blockToSectionCoord(camX + minX));
        int sx1 = Math.min(cameraSection.x() + viewDistance, SectionPos.blockToSectionCoord(camX + maxX));
        int sy0 = Math.max(minSectionY, SectionPos.blockToSectionCoord(camY + minY));
        int sy1 = Math.min(maxSectionY, SectionPos.blockToSectionCoord(camY + maxY));
        int sz0 = Math.max(cameraSection.z() - viewDistance, SectionPos.blockToSectionCoord(camZ + minZ));
        int sz1 = Math.min(cameraSection.z() + viewDistance, SectionPos.blockToSectionCoord(camZ + maxZ));

        for (int sz = sz0; sz <= sz1; sz++) {
            int zi = Math.floorMod(sz, sizeXZ);
            for (int sx = sx0; sx <= sx1; sx++) {
                int xi = Math.floorMod(sx, sizeXZ);
                for (int sy = sy0; sy <= sy1; sy++) {
                    SectionRenderDispatcher.RenderSection section = sections[(zi * sizeY + (sy - minSectionY)) * sizeXZ + xi];
                    if (section == null || section.getSectionNode() != SectionPos.asLong(sx, sy, sz)) continue;
                    SectionMesh mesh = section.getSectionMesh();
                    if (!casts(mesh)) continue;
                    BlockPos origin = section.getRenderOrigin();
                    float cx = (float) (origin.getX() + 8 - camX), cy = (float) (origin.getY() + 8 - camY), cz = (float) (origin.getZ() + 8 - camZ);
                    if (!bounds.touches(cx, cy, cz, Cascade.SECTION_RADIUS, scratch)) continue;
                    if (count == MAX_SECTIONS) {
                        if (!warnedFull) {
                            warnedFull = true;
                            BlockLand.LOGGER.warn("[BlockLand] More than {} sections cast sun shadows; the rest are left out", MAX_SECTIONS);
                        }
                        continue;
                    }
                    meshes[count] = mesh;
                    centers[count * 3] = cx;
                    centers[count * 3 + 1] = cy;
                    centers[count * 3 + 2] = cz;
                    transforms[count] = new DynamicUniforms.Transform(IDENTITY, WHITE, new Vector3f(cx - 8, cy - 8, cz - 8), IDENTITY, 1f);
                    for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                        SectionBuffers buffers = layer == ChunkSectionLayer.TRANSLUCENT || layer == ChunkSectionLayer.TRIPWIRE
                                ? null : mesh.getBuffers(layer);
                        if (buffers != null && buffers.getIndexBuffer() == null) {
                            maxSequentialIndices = Math.max(maxSequentialIndices, buffers.getIndexCount());
                        }
                    }
                    count++;
                }
            }
        }
        if (count > 0) {
            DynamicUniforms.Transform[] used = new DynamicUniforms.Transform[count];
            System.arraycopy(transforms, 0, used, 0, count);
            slices = RenderSystem.getDynamicUniforms().writeTransforms(used);
        }
        return true;
    }

    private static boolean casts(SectionMesh mesh) {
        return mesh.getBuffers(ChunkSectionLayer.SOLID) != null || mesh.getBuffers(ChunkSectionLayer.CUTOUT_MIPPED) != null
                || mesh.getBuffers(ChunkSectionLayer.CUTOUT) != null;
    }

    /**
     * Draws the gathered sections that reach this cascade: the solid layer, or (with the alpha-tested pipeline set and
     * the block atlas bound) the cutout layers.
     */
    int draw(RenderPass pass, Cascade cascade, boolean cutout, @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType) {
        if (slices == null) return 0;
        int drawn = 0;
        for (int i = 0; i < count; i++) {
            if (!cascade.touches(centers[i * 3], centers[i * 3 + 1], centers[i * 3 + 2], Cascade.SECTION_RADIUS, scratch)) continue;
            SectionMesh mesh = meshes[i];
            boolean bound = false;
            if (cutout) {
                for (ChunkSectionLayer layer : CUTOUT_LAYERS) {
                    bound = drawLayer(pass, mesh.getBuffers(layer), slices[i], bound, sequential, sequentialType);
                }
            } else {
                bound = drawLayer(pass, mesh.getBuffers(ChunkSectionLayer.SOLID), slices[i], false, sequential, sequentialType);
            }
            if (bound) drawn++;
        }
        return drawn;
    }

    private static boolean drawLayer(RenderPass pass, @Nullable SectionBuffers buffers, GpuBufferSlice transform, boolean bound,
                                     @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType) {
        if (buffers == null || buffers.getIndexCount() == 0) return bound;
        if (buffers.getIndexBuffer() != null) {
            pass.setIndexBuffer(buffers.getIndexBuffer(), buffers.getIndexType());
        } else if (sequential != null && sequentialType != null) {
            pass.setIndexBuffer(sequential, sequentialType);
        } else {
            return bound;
        }
        if (!bound) pass.setUniform("DynamicTransforms", transform);
        pass.setVertexBuffer(0, buffers.getVertexBuffer());
        pass.drawIndexed(0, 0, buffers.getIndexCount(), 1);
        return true;
    }

    /** Drops the references to this frame's meshes (they belong to the level renderer). */
    void clear() {
        for (int i = 0; i < count; i++) {
            meshes[i] = null;
            transforms[i] = null;
        }
        count = 0;
        slices = null;
    }
}
