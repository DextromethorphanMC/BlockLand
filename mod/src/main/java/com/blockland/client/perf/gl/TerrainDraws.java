package com.blockland.client.perf.gl;

import com.blockland.client.perf.Optimization;
import com.blockland.client.perf.terrain.FaceGroups;
import com.blockland.client.perf.terrain.FaceIndex;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * {@code TERRAIN_DRAWS} (rendering spec §20): the terrain's per-section draw loop — thousands of draws a frame — without
 * garbage or repeated binds, for the same draws in the same order with the same uniform bytes.
 * <ul>
 * <li>{@link #prepare}: {@code LevelRenderer.prepareChunkRenders} line for line, minus a layer array per section, an
 *     uploader lambda per draw (one shared uploader per transform index) and the per-draw layer map lookup;</li>
 * <li>{@link #drawMultiple}: {@code GlCommandEncoder.executeDrawMultiple} line for line, with one uniform uploader per
 *     batch instead of one per draw, its name lookup remembered, and its binds through {@link GlStateCache};</li>
 * <li>{@link #sameVertexArray} / {@link #bindElementBuffer}: the vertex array is bound once while it stays the same, and
 *     its element buffer (vertex array state) is bound only when it changes. Vertex arrays are Blaze3D's
 *     ({@code VertexArrayCache}), never deleted, and only bound through {@code GlStateManager}; a deleted buffer drops
 *     out ({@link #bufferDeleted}) since GL reuses names;</li>
 * <li>{@code VertexFormat.hashCode} (every vertex array lookup) is computed once per format: its fields never change.</li>
 * </ul>
 * BlockLand's own batches (shadow casters, the first-person view model) go through the same batch path.
 */
public final class TerrainDraws {
    /** {@code GlCommandEncoder}'s private steps of a batch. */
    public interface Encoder {
        boolean blockland$trySetup(GlRenderPass pass, Collection<String> dynamicUniforms);

        void blockland$drawFromBuffers(GlRenderPass pass, int baseVertex, int firstIndex, int indexCount,
                                       @Nullable VertexFormat.IndexType indexType, GlRenderPipeline pipeline, int instances);
    }

    /** {@code GlRenderPass}'s pipeline. */
    public interface Pass {
        GlRenderPipeline blockland$pipeline();
    }

    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
    private static final String DYNAMIC_TRANSFORMS = "DynamicTransforms";
    /** {@code FACE_GROUPS}: time per view for writing index lists of sections whose visible groups changed. */
    private static final long FACE_INDEX_NANOS = 1_500_000;
    /** {@code FACE_GROUPS}: how far the camera may move before a section's visible groups are worked out again. */
    private static final double FACE_DRIFT = 0.25;
    /** Vertex array names above this are not cached (GL hands out small names; this only bounds the table). */
    private static final int MAX_VAO = 4096;

    /** The bound vertex array (-1 = unknown). */
    private static int boundVao = -1;
    /** Element buffer name + 1 per vertex array (0 = unknown). */
    private static int[] elementByVao = new int[64];

    @SuppressWarnings("unchecked")
    private static final List<RenderPass.Draw<GpuBufferSlice[]>>[] layerLists = new List[LAYERS.length];
    private static final ArrayList<DynamicUniforms.Transform> transforms = new ArrayList<>();
    private static TransformUpload[] uploads = new TransformUpload[0];
    private static final BatchUploader sharedUploader = new BatchUploader();
    private static boolean uploaderBusy;

    private TerrainDraws() {
    }

    public static boolean on() {
        return Optimization.TERRAIN_DRAWS.on();
    }

    // ---- binds -------------------------------------------------------------------------------------------------------

    /** {@code GlStateManager._glBindVertexArray}: true skips it (the array is bound already). */
    public static boolean sameVertexArray(int vao) {
        if (vao == boundVao && on()) return true;
        boundVao = vao;
        return false;
    }

    /** drawFromBuffers' element buffer bind, into the bound vertex array. */
    public static void bindElementBuffer(int target, int buffer) {
        int vao = boundVao;
        if (target == GL15.GL_ELEMENT_ARRAY_BUFFER && vao >= 0 && vao < MAX_VAO && buffer != 0) {
            if (vao < elementByVao.length && elementByVao[vao] == buffer + 1 && on()) return;
            GlStateManager._glBindBuffer(target, buffer);
            if (vao >= elementByVao.length) elementByVao = Arrays.copyOf(elementByVao, Math.max(vao + 1, elementByVao.length * 2));
            elementByVao[vao] = buffer + 1;
            return;
        }
        GlStateManager._glBindBuffer(target, buffer);
        if (target == GL15.GL_ELEMENT_ARRAY_BUFFER && vao >= 0 && vao < elementByVao.length) elementByVao[vao] = 0;
    }

    /** A buffer is being deleted: vertex arrays that held it must bind its name again (it may be another buffer then). */
    public static void bufferDeleted(int buffer) {
        int[] table = elementByVao;
        for (int i = 0; i < table.length; i++) {
            if (table[i] == buffer + 1) table[i] = 0;
        }
    }

    /** Once per frame (defensive): the bound array and element buffers are sent again on first use. */
    public static void endFrame() {
        boundVao = -1;
        Arrays.fill(elementByVao, 0);
    }

    // ---- batches -----------------------------------------------------------------------------------------------------

    /** Whether {@link #drawMultiple} replaces the batch (Blaze3D's validation build keeps its own loop). */
    public static boolean replacesBatches() {
        return on() && !GlRenderPass.VALIDATION;
    }

    /** {@code GlCommandEncoder.executeDrawMultiple} (non-validating): the same draws, one uploader for the batch. */
    public static <T> void drawMultiple(Encoder encoder, GlRenderPass pass, Collection<RenderPass.Draw<T>> draws,
                                        @Nullable GpuBuffer defaultIndexBuffer, @Nullable VertexFormat.IndexType defaultIndexType,
                                        Collection<String> dynamicUniforms, T uniformArgument) {
        if (!encoder.blockland$trySetup(pass, dynamicUniforms)) return;
        VertexFormat.IndexType fallbackType = defaultIndexType == null ? VertexFormat.IndexType.SHORT : defaultIndexType;
        boolean shared = !uploaderBusy;
        BatchUploader uploader = shared ? sharedUploader : new BatchUploader();
        uploaderBusy = true;
        try {
            uploader.begin(pass);
            for (RenderPass.Draw<T> draw : draws) {
                VertexFormat.IndexType indexType = draw.indexType() == null ? fallbackType : draw.indexType();
                pass.setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), indexType);
                pass.setVertexBuffer(draw.slot(), draw.vertexBuffer());
                BiConsumer<T, RenderPass.UniformUploader> consumer = draw.uniformUploaderConsumer();
                if (consumer != null) consumer.accept(uniformArgument, uploader);
                encoder.blockland$drawFromBuffers(pass, 0, draw.firstIndex(), draw.indexCount(), indexType,
                        ((Pass) pass).blockland$pipeline(), 1);
            }
        } finally {
            uploader.end();
            if (shared) uploaderBusy = false;
        }
    }

    /**
     * The batch's uniform uploader: vanilla's per-draw lambda (look the name up in the pass's program, bind the slice
     * if it is a uniform block), with the last name's lookup kept for the batch (the program cannot change in it).
     */
    private static final class BatchUploader implements RenderPass.UniformUploader {
        @Nullable
        private GlRenderPass pass;
        @Nullable
        private GlProgram program;
        @Nullable
        private String lastName;
        @Nullable
        private Uniform lastUniform;

        void begin(GlRenderPass pass) {
            this.pass = pass;
            this.program = null;
            this.lastName = null;
            this.lastUniform = null;
        }

        void end() {
            pass = null;
            program = null;
            lastName = null;
            lastUniform = null;
        }

        @Override
        public void upload(String name, GpuBufferSlice slice) {
            GlProgram current = ((Pass) pass).blockland$pipeline().program();
            Uniform uniform;
            if (name == lastName && current == program) {
                uniform = lastUniform;
            } else {
                uniform = current.getUniform(name);
                program = current;
                lastName = name;
                lastUniform = uniform;
            }
            if (uniform instanceof Uniform.Ubo(int binding)) {
                GlStateCache.bindUniformBuffer(GL31.GL_UNIFORM_BUFFER, binding, GlStateCache.handle(slice.buffer()), slice.offset(), slice.length());
            }
        }
    }

    // ---- section draws -----------------------------------------------------------------------------------------------

    /**
     * {@code LevelRenderer.prepareChunkRenders}: per visible section and layer with geometry, a transform (camera-relative
     * origin, computed as vanilla does) and a draw that uploads it; all transforms written at once.
     */
    public static ChunkSectionsToRender prepare(ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
                                                Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> drawsPerLayer = new EnumMap<>(ChunkSectionLayer.class);
        int maxIndices = 0;
        for (ChunkSectionLayer layer : LAYERS) {
            List<RenderPass.Draw<GpuBufferSlice[]>> list = new ArrayList<>();
            drawsPerLayer.put(layer, list);
            layerLists[layer.ordinal()] = list;
        }

        ArrayList<DynamicUniforms.Transform> list = transforms;
        Vector4f colorModulator = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
        Matrix4f textureMatrix = new Matrix4f();
        Object[] sections = visibleSections.elements();
        FaceIndex.budget(FACE_INDEX_NANOS);
        int count = visibleSections.size();
        try {
            for (int s = 0; s < count; s++) {
                SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) sections[s];
                SectionMesh mesh = section.getSectionMesh();
                for (ChunkSectionLayer layer : LAYERS) {
                    SectionBuffers buffers = mesh.getBuffers(layer);
                    if (buffers == null) continue;
                    GpuBuffer indexBuffer;
                    VertexFormat.IndexType indexType;
                    if (buffers.getIndexBuffer() == null) {
                        if (buffers.getIndexCount() > maxIndices) maxIndices = buffers.getIndexCount();
                        indexBuffer = null;
                        indexType = null;
                    } else {
                        indexBuffer = buffers.getIndexBuffer();
                        indexType = buffers.getIndexType();
                    }

                    BlockPos origin = section.getRenderOrigin();
                    // FACE_GROUPS: only the quads of the groups the camera may see, in the mesh's order (none: the layer is
                    // left out of this view; the index list not ready this frame: the whole mesh, as before)
                    int indexCount = buffers.getIndexCount();
                    FaceGroups groups = FaceGroups.grouped(layer) && buffers.getIndexBuffer() == null && Optimization.FACE_GROUPS.on()
                            ? ((FaceGroups.Holder) (Object) buffers).blockland$faceGroups() : null;
                    int firstIndex = 0;
                    if (groups != null && groups.quads * 6 == indexCount && groups.current()) {
                        // worked out again only once the camera moved a quarter block (the margin widened by as much)
                        FaceIndex faceIndex = ((FaceGroups.Holder) (Object) buffers).blockland$faceIndex();
                        double x = cameraX - origin.getX(), y = cameraY - origin.getY(), z = cameraZ - origin.getZ();
                        int mask = faceIndex.reuse(x, y, z, FACE_DRIFT);
                        if (mask < 0) {
                            mask = groups.visible(x, y, z, FaceGroups.MARGIN + FACE_DRIFT);
                            faceIndex.remember(x, y, z, mask);
                        }
                        if (mask == 0) continue;
                        if (!groups.all(mask)) {
                            int visibleIndices = groups.quads(mask) * 6;
                            boolean wide = groups.quads * 4 > 0xFFFF;
                            FaceGroups g = groups;
                            int drawn = mask;
                            if (faceIndex.ensure(drawn, visibleIndices, wide, out -> g.writeIndices(out, drawn, 0, wide))) {
                                indexBuffer = faceIndex.buffer();
                                indexType = faceIndex.type();
                                indexCount = faceIndex.indexCount();
                                firstIndex = faceIndex.firstIndex();
                            }
                        }
                    }
                    int index = list.size();
                    list.add(new DynamicUniforms.Transform(
                            modelView,
                            colorModulator,
                            new Vector3f((float) (origin.getX() - cameraX), (float) (origin.getY() - cameraY), (float) (origin.getZ() - cameraZ)),
                            textureMatrix,
                            1.0F));
                    layerLists[layer.ordinal()].add(new RenderPass.Draw<>(0, buffers.getVertexBuffer(), indexBuffer, indexType, firstIndex,
                            indexCount, upload(index)));
                }
            }
            GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(list.toArray(new DynamicUniforms.Transform[0]));
            return new ChunkSectionsToRender(drawsPerLayer, maxIndices, slices);
        } finally {
            list.clear();
            Arrays.fill(layerLists, null);
        }
    }

    private static TransformUpload upload(int index) {
        if (index >= uploads.length) {
            int from = uploads.length;
            uploads = Arrays.copyOf(uploads, Math.max(index + 1, Math.max(256, from * 2)));
            for (int i = from; i < uploads.length; i++) uploads[i] = new TransformUpload(i);
        }
        return uploads[index];
    }

    /** Vanilla's per-draw lambda {@code (slices, uploader) -> uploader.upload("DynamicTransforms", slices[j])}, shared per j. */
    private record TransformUpload(int index) implements BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> {
        @Override
        public void accept(GpuBufferSlice[] slices, RenderPass.UniformUploader uploader) {
            uploader.upload(DYNAMIC_TRANSFORMS, slices[index]);
        }
    }
}
