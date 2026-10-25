package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import com.blockland.client.perf.Optimization;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The terrain casters: the compiled chunk sections around the camera, drawn into the shadow map straight from the GPU
 * buffers the level renderer already holds (solid, cutout-mipped and cutout layers; water and other translucent blocks
 * cast no shadow). The level renderer only knows the sections the camera sees; the sun also needs those behind the
 * camera and above it, so they are looked up in its view area (a private field) by section position. Each section that
 * reaches any cascade drawn this frame gets one transform slot (its origin relative to the camera), shared by all
 * cascades.
 * <p>
 * Batched ({@link Optimization#SHADOW_BATCHING}): the transforms are reused objects, which cascades each section
 * reaches is worked out once ({@link #mark}), and each cascade submits its solid and its cutout sections as one batch
 * each (the same draws in the same order with the same state, each section's transform bound per draw) instead of one
 * draw call after the other, each setting up all of the pass's state again.
 */
final class TerrainCasters {
    /** More would mean a huge shadow distance on a huge render distance: the farthest are left out. */
    private static final int MAX_SECTIONS = 6144;
    private static final ChunkSectionLayer[] CUTOUT_LAYERS = {ChunkSectionLayer.CUTOUT_MIPPED, ChunkSectionLayer.CUTOUT};
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
    /** Bound per draw in a batch: each section's own transform. */
    private static final List<String> PER_DRAW = List.of("DynamicTransforms");
    /** Batch draw slots per section: the solid, cutout-mipped and cutout layers. */
    private static final int SOLID = 0, MIPPED = 1, CUTOUT = 2;

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

    // batched (SHADOW_BATCHING)
    /** Transforms reused from frame to frame, their model offset set anew by each gather. */
    private final DynamicUniforms.Transform[] pooled = new DynamicUniforms.Transform[MAX_SECTIONS];
    @Nullable
    private DynamicUniforms.Transform[] used;
    /** Per gathered section: the cascades drawn this frame it reaches, a bit each. */
    private final byte[] reach = new byte[MAX_SECTIONS];

    // sorted (SHADOW_SORTING)
    /** Gathered sections in drawing order (nearest the sun first), or null to draw them as gathered. */
    @Nullable
    private int[] order;
    private final int[] sorted = new int[MAX_SECTIONS];
    private final long[] keys = new long[MAX_SECTIONS];
    private final List<RenderPass.Draw<GpuBufferSlice[]>> batch = new ArrayList<>();
    /** Per section slot: binds the slot's transform from the frame's slices. */
    @SuppressWarnings("unchecked")
    private final BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader>[] uploaders = new BiConsumer[MAX_SECTIONS];
    /** Per layer and section slot: an earlier frame's draw, reused while it names the same buffers and range. */
    @SuppressWarnings("unchecked")
    private final RenderPass.Draw<GpuBufferSlice[]>[][] draws = new RenderPass.Draw[3][MAX_SECTIONS];

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
     * @param batched the frame is drawn batched: the transforms are pooled
     * @return false when the view area cannot be read
     */
    boolean gather(Level level, Cascade bounds, double camX, double camY, double camZ, boolean batched) {
        count = 0;
        maxSequentialIndices = 0;
        slices = null;
        order = null;
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
                    if (batched) {
                        DynamicUniforms.Transform transform = pooled[count];
                        if (transform == null) {
                            transform = pooled[count] = new DynamicUniforms.Transform(IDENTITY, WHITE, new Vector3f(), IDENTITY, 1f);
                        }
                        ((Vector3f) transform.modelOffset()).set(cx - 8, cy - 8, cz - 8);
                        transforms[count] = transform;
                    } else {
                        transforms[count] = new DynamicUniforms.Transform(IDENTITY, WHITE, new Vector3f(cx - 8, cy - 8, cz - 8), IDENTITY, 1f);
                    }
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
        if (count > 0 && batched) {
            if (used == null || used.length != count) used = new DynamicUniforms.Transform[count];
            System.arraycopy(transforms, 0, used, 0, count);
            // the uniform storage keeps the last one to compare the next with until the frame ends: never a pooled one
            used[count - 1] = new DynamicUniforms.Transform(IDENTITY, WHITE, new Vector3f(transforms[count - 1].modelOffset()), IDENTITY, 1f);
            slices = RenderSystem.getDynamicUniforms().writeTransforms(used);
        } else if (count > 0) {
            DynamicUniforms.Transform[] used = new DynamicUniforms.Transform[count];
            System.arraycopy(transforms, 0, used, 0, count);
            slices = RenderSystem.getDynamicUniforms().writeTransforms(used);
        }
        return true;
    }

    /**
     * Batched: which of the cascades drawn this frame each gathered section reaches, the test {@link #draw} makes per
     * layer made once.
     *
     * @param pad the culling padding ({@link Cascade#boxReaches}), or negative for the sphere test alone
     */
    void mark(Cascade[] cascades, boolean[] due, int cascadeCount, float pad) {
        for (int i = 0; i < count; i++) {
            int bits = 0;
            for (int c = 0; c < cascadeCount; c++) {
                if (due[c] && reaches(cascades[c], i, pad)) bits |= 1 << c;
            }
            reach[i] = (byte) bits;
        }
    }

    /**
     * Sorted: the gathered sections are drawn nearest the sun first (by their centre's depth in the light's view, the
     * same for every cascade), so what lies beneath — the ground under the trees, the caves under the ground — fails the
     * GPU's early depth test instead of being rasterised, shaded and written over. The order cannot change a texel: every
     * caster writes depth with a less-or-equal test and nothing else (no colour, no blending), so each texel ends at the
     * least depth of the fragments covering it, whatever order they came in. Nothing but the order of the same draws
     * changes.
     *
     * @param view the light's view (any cascade's: they share its direction)
     */
    void sort(Matrix4f view) {
        if (count == 0) return;
        for (int i = 0; i < count; i++) {
            float x = centers[i * 3], y = centers[i * 3 + 1], z = centers[i * 3 + 2];
            // view-space depth: the light looks down -z, so the nearest has the largest z; sort ascending on -z
            float depth = -(view.m02() * x + view.m12() * y + view.m22() * z + view.m32());
            int bits = Float.floatToIntBits(depth);
            bits ^= (bits >> 31) & 0x7FFFFFFF;
            keys[i] = ((long) bits << 32) | i;
        }
        java.util.Arrays.sort(keys, 0, count);
        for (int n = 0; n < count; n++) sorted[n] = (int) keys[n];
        order = sorted;
    }

    /** The section drawn {@code n}th. */
    private int at(int n) {
        return order == null ? n : order[n];
    }

    /**
     * The meshes of the gathered sections that reach cascade {@code c} ({@link #mark}), in gather order, into {@code out};
     * their number, or minus the length {@code out} needs when it is too short.
     */
    int meshesReaching(int c, SectionMesh[] out) {
        int bit = 1 << c, n = 0;
        for (int i = 0; i < count; i++) if ((reach[i] & bit) != 0) n++;
        if (n > out.length) return -n;
        n = 0;
        for (int i = 0; i < count; i++) if ((reach[i] & bit) != 0) out[n++] = meshes[i];
        return n;
    }

    /** Whether gathered section {@code i} can cast into the cascade: by its sphere, and (culling) by its padded box. */
    private boolean reaches(Cascade cascade, int i, float pad) {
        if (!cascade.touches(centers[i * 3], centers[i * 3 + 1], centers[i * 3 + 2], Cascade.SECTION_RADIUS, scratch)) return false;
        return pad < 0 || cascade.boxReaches(scratch);
    }

    private static boolean casts(SectionMesh mesh) {
        return mesh.getBuffers(ChunkSectionLayer.SOLID) != null || mesh.getBuffers(ChunkSectionLayer.CUTOUT_MIPPED) != null
                || mesh.getBuffers(ChunkSectionLayer.CUTOUT) != null;
    }

    /**
     * Draws the gathered sections that reach this cascade: the solid layer, or (with the alpha-tested pipeline set and
     * the block atlas bound) the cutout layers.
     */
    int draw(RenderPass pass, Cascade cascade, boolean cutout, @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType,
             float pad) {
        if (slices == null) return 0;
        int drawn = 0;
        for (int n = 0; n < count; n++) {
            int i = at(n);
            if (!reaches(cascade, i, pad)) continue;
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

    /**
     * Batched {@link #draw}: the sections {@link #mark} found reaching the cascade, the same layers in the same order,
     * submitted as one batch (the pipeline, its other uniforms and the block atlas set on the pass as for {@link #draw}).
     */
    int drawBatched(RenderPass pass, int cascade, boolean cutout, @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType) {
        if (slices == null) return 0;
        int bit = 1 << cascade;
        int drawn = 0;
        batch.clear();
        for (int n = 0; n < count; n++) {
            int i = at(n);
            if ((reach[i] & bit) == 0) continue;
            SectionMesh mesh = meshes[i];
            boolean any;
            if (cutout) {
                any = add(mesh.getBuffers(ChunkSectionLayer.CUTOUT_MIPPED), MIPPED, i, sequential, sequentialType);
                any |= add(mesh.getBuffers(ChunkSectionLayer.CUTOUT), CUTOUT, i, sequential, sequentialType);
            } else {
                any = add(mesh.getBuffers(ChunkSectionLayer.SOLID), SOLID, i, sequential, sequentialType);
            }
            if (any) drawn++;
        }
        if (!batch.isEmpty()) pass.drawMultipleIndexed(batch, null, null, PER_DRAW, slices);
        batch.clear();
        return drawn;
    }

    /** One layer's draw for the batch, as {@link #drawLayer} would make it (same buffers, index range and type). */
    private boolean add(@Nullable SectionBuffers buffers, int layer, int i, @Nullable GpuBuffer sequential,
                        @Nullable VertexFormat.IndexType sequentialType) {
        if (buffers == null || buffers.getIndexCount() == 0) return false;
        GpuBuffer indices = buffers.getIndexBuffer();
        VertexFormat.IndexType type = buffers.getIndexType();
        if (indices == null) {
            if (sequential == null || sequentialType == null) return false;
            indices = sequential;
            type = sequentialType;
        }
        GpuBuffer vertices = buffers.getVertexBuffer();
        int indexCount = buffers.getIndexCount();
        RenderPass.Draw<GpuBufferSlice[]> draw = draws[layer][i];
        if (draw == null || draw.vertexBuffer() != vertices || draw.indexBuffer() != indices || draw.indexType() != type
                || draw.indexCount() != indexCount) {
            draw = draws[layer][i] = new RenderPass.Draw<>(0, vertices, indices, type, 0, indexCount, uploader(i));
        }
        batch.add(draw);
        return true;
    }

    private BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> uploader(int i) {
        BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> uploader = uploaders[i];
        if (uploader == null) uploader = uploaders[i] = (frame, upload) -> upload.upload("DynamicTransforms", frame[i]);
        return uploader;
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
