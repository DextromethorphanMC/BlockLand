package com.blockland.client.perf.gl;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.VertexArrayCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * {@code TERRAIN_PAGES} (rendering spec §20): a batch of indexed draws of one pipeline submitted as multi-draws. Each
 * draw carries its own offset (the section's origin relative to the camera, the very floats
 * {@code DynamicTransforms.ModelOffset} would hold) in a shader storage buffer the vertex shader reads by its draw id
 * ({@code BL_PAGES} in the lighting pack's terrain.vsh and shadow_terrain.vsh: {@code Position + offset}, the same
 * addition), so a run of draws sharing their vertex and index buffers goes out as one
 * {@code glMultiDrawElementsBaseVertex}, each draw from its base vertex ({@link VertexPages}). The order of the draws
 * is the batch's order, as it would be draw by draw. The offsets go through a ring of three frame regions written with
 * {@code glNamedBufferSubData} (no binding, no mapping: a batch may be drawn inside a render pass); the vertex array
 * and element buffer binds go through Blaze3D's caches as any draw's do, the storage buffer bind is GL's own (Blaze3D
 * keeps no state for it). Needs GL 4.5 with {@code ARB_shader_draw_parameters}; without them nothing here is used.
 */
public final class PagedDraws {
    /** The storage block's binding point in the shaders ({@code layout(std430, binding = 0)}). */
    private static final int SSBO_BINDING = 0;
    private static final int VEC4_BYTES = 16;
    private static final int REGIONS = 3;
    private static final List<String> NO_DYNAMIC_UNIFORMS = List.of();

    @Nullable
    private static Boolean supported;
    private static int alignment = 256;
    /** The offsets ring: one GL buffer of {@link #REGIONS} regions, the frame's region bumped from {@link #cursor}. */
    private static int ring;
    private static int regionBytes;
    private static int frame;
    private static int cursor;
    /** The draw commands' ring (GL_DRAW_INDIRECT_BUFFER): five uints a draw, the same regions and cursor. */
    private static int commands;
    private static int commandRegionBytes;
    private static int commandCursor;
    private static final int COMMAND_BYTES = 20;

    private PagedDraws() {
    }

    /** Whether the GPU draws this way (asked once, render thread). */
    public static boolean supported() {
        Boolean known = supported;
        if (known != null) return known;
        boolean ok;
        try {
            GLCapabilities caps = GL.getCapabilities();
            ok = caps.OpenGL45 && caps.GL_ARB_shader_draw_parameters && caps.GL_ARB_shader_storage_buffer_object;
            if (ok) alignment = Math.max(16, GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
        } catch (RuntimeException e) {
            ok = false;
        }
        supported = ok;
        return ok;
    }

    /** The frame ended: the next frame writes its offsets and commands into the next region. */
    public static void endFrame() {
        frame++;
        cursor = 0;
        commandCursor = 0;
    }

    /** Room for {@code bytes} of draw commands in this frame's region: the byte offset into the commands ring. */
    private static int reserveCommands(int bytes) {
        int at = (commandCursor + 15) / 16 * 16;
        if (commands == 0 || at + bytes > commandRegionBytes) {
            int need = Math.max(commandRegionBytes * 2, Math.max(1 << 18, at + bytes));
            if (commands != 0) GL15.glDeleteBuffers(commands);
            commands = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(commands, (long) need * REGIONS, GL44.GL_DYNAMIC_STORAGE_BIT);
            commandRegionBytes = need;
            at = 0;
        }
        commandCursor = at + bytes;
        return (frame % REGIONS) * commandRegionBytes + at;
    }

    /** Room for {@code bytes} of offsets in this frame's region: the byte offset into the ring. */
    private static int reserve(int bytes) {
        int at = (cursor + alignment - 1) / alignment * alignment;
        if (ring == 0 || at + bytes > regionBytes) {
            int need = Math.max(regionBytes * 2, Math.max(1 << 20, at + bytes));
            if (ring != 0) GL15.glDeleteBuffers(ring);
            ring = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(ring, (long) need * REGIONS, GL44.GL_DYNAMIC_STORAGE_BIT);
            regionBytes = need;
            at = 0;
        }
        cursor = at + bytes;
        return (frame % REGIONS) * regionBytes + at;
    }

    /** The draws of one pipeline, in order. */
    public static final class Batch {
        private GpuBuffer[] vertexBuffers = new GpuBuffer[256];
        private GpuBuffer[] indexBuffers = new GpuBuffer[256];
        private VertexFormat.IndexType[] indexTypes = new VertexFormat.IndexType[256];
        private int[] firstIndices = new int[256], indexCounts = new int[256], baseVertices = new int[256];
        private float[] offsets = new float[256 * 4];
        private int count;
        // the multi-draw's arrays (native), grown with the batch
        private IntBuffer commandStaging = MemoryUtil.memAllocInt(256 * 5);
        private FloatBuffer staging = MemoryUtil.memAllocFloat(256 * 4 + 256 * 64);
        // the runs of the last draw
        private int runs;
        private int[] runStarts = new int[64], runCounts = new int[64], runOffsets = new int[64];
        private GpuBuffer[] runVertices = new GpuBuffer[64], runIndices = new GpuBuffer[64];
        private VertexFormat.IndexType[] runTypes = new VertexFormat.IndexType[64];

        public boolean isEmpty() {
            return count == 0;
        }

        public int size() {
            return count;
        }

        public void clear() {
            Arrays.fill(vertexBuffers, 0, count, null);
            Arrays.fill(indexBuffers, 0, count, null);
            count = 0;
        }

        /**
         * One draw: {@code indexCount} indices from {@code firstIndex} of {@code indexBuffer} (null: the sequential quad
         * buffer, its type), every index plus {@code baseVertex}, the vertices read from {@code vertexBuffer}, placed by
         * the offset.
         */
        public void add(GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable VertexFormat.IndexType indexType, int firstIndex,
                        int indexCount, int baseVertex, float ox, float oy, float oz) {
            if (count == vertexBuffers.length) grow();
            int i = count++;
            vertexBuffers[i] = vertexBuffer;
            indexBuffers[i] = indexBuffer;
            indexTypes[i] = indexType;
            firstIndices[i] = firstIndex;
            indexCounts[i] = indexCount;
            baseVertices[i] = baseVertex;
            offsets[i * 4] = ox;
            offsets[i * 4 + 1] = oy;
            offsets[i * 4 + 2] = oz;
            offsets[i * 4 + 3] = 0f;
        }

        private void grow() {
            int n = vertexBuffers.length * 2;
            vertexBuffers = Arrays.copyOf(vertexBuffers, n);
            indexBuffers = Arrays.copyOf(indexBuffers, n);
            indexTypes = Arrays.copyOf(indexTypes, n);
            firstIndices = Arrays.copyOf(firstIndices, n);
            indexCounts = Arrays.copyOf(indexCounts, n);
            baseVertices = Arrays.copyOf(baseVertices, n);
            offsets = Arrays.copyOf(offsets, n * 4);
            commandStaging = MemoryUtil.memRealloc(commandStaging, n * 5);
            // the offsets staging also holds the alignment padding between runs (at most one alignment per run)
            staging = MemoryUtil.memRealloc(staging, n * 4 + n * (alignment / 4));
        }

        /**
         * Draws the batch into the pass, whose pipeline, samplers and other uniforms are set: runs of draws sharing their
         * vertex buffer, index buffer and index type as one multi-draw each.
         *
         * @param sequential the sequential quad index buffer for draws without one of their own (and its type)
         */
        public void draw(GlRenderPass pass, @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType) {
            if (count == 0) return;
            TerrainDraws.Encoder encoder = (TerrainDraws.Encoder) RenderSystem.getDevice().createCommandEncoder();
            if (!encoder.blockland$trySetup(pass, NO_DYNAMIC_UNIFORMS)) return;
            GlRenderPipeline pipeline = ((TerrainDraws.Pass) pass).blockland$pipeline();
            VertexFormat format = pipeline.info().getVertexFormat();
            int mode = GlConst.toGl(pipeline.info().getVertexFormatMode());
            VertexArrayCache arrays = ((GlDevice) RenderSystem.getDevice()).vertexArrayCache();

            // the runs: consecutive draws sharing their vertex buffer, index buffer and index type; every run's offsets
            // (aligned, as the storage block is bound by range) and commands gathered first, written once, then drawn
            runs = 0;
            staging.clear();
            commandStaging.clear();
            int offsetBytes = 0;
            int start = 0;
            while (start < count) {
                GpuBuffer vertices = vertexBuffers[start];
                GpuBuffer indices = indexBuffers[start] == null ? sequential : indexBuffers[start];
                VertexFormat.IndexType type = indexBuffers[start] == null ? sequentialType : indexTypes[start];
                if (indices == null || type == null) {
                    start++;
                    continue;
                }
                int end = start + 1;
                while (end < count && vertexBuffers[end] == vertices && (indexBuffers[end] == null ? sequential : indexBuffers[end]) == indices
                        && (indexBuffers[end] == null ? sequentialType : indexTypes[end]) == type) {
                    end++;
                }
                int n = end - start;
                if (runs == runStarts.length) growRuns();
                int aligned = (offsetBytes + alignment - 1) / alignment * alignment;
                // pad the staged offsets up to the aligned start
                while (staging.position() * 4 < aligned) staging.put(0f);
                runStarts[runs] = start;
                runCounts[runs] = n;
                runOffsets[runs] = aligned;
                runVertices[runs] = vertices;
                runIndices[runs] = indices;
                runTypes[runs] = type;
                runs++;
                staging.put(offsets, start * 4, n * 4);
                offsetBytes = aligned + n * VEC4_BYTES;
                for (int i = start; i < end; i++) {
                    commandStaging.put(indexCounts[i]).put(1).put(firstIndices[i]).put(baseVertices[i]).put(0);
                }
                start = end;
            }
            if (runs == 0) return;
            staging.flip();
            commandStaging.flip();
            int at = reserve(offsetBytes);
            int commandsAt = reserveCommands(commandStaging.remaining() * 4);
            GL45.glNamedBufferSubData(ring, at, staging);
            GL45.glNamedBufferSubData(commands, commandsAt, commandStaging);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commands);
            int commandAt = commandsAt;
            for (int r = 0; r < runs; r++) {
                int n = runCounts[r];
                GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING, ring, at + runOffsets[r], (long) n * VEC4_BYTES);
                pass.setVertexBuffer(0, runVertices[r]);
                pass.setIndexBuffer(runIndices[r], runTypes[r]);
                arrays.bindVertexArray(format, (GlBuffer) runVertices[r]);
                TerrainDraws.bindElementBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, GlStateCache.handle(runIndices[r]));
                GL43.glMultiDrawElementsIndirect(mode, GlConst.toGl(runTypes[r]), commandAt, n, 0);
                commandAt += n * COMMAND_BYTES;
            }
        }

        private void growRuns() {
            int n = runStarts.length * 2;
            runStarts = Arrays.copyOf(runStarts, n);
            runCounts = Arrays.copyOf(runCounts, n);
            runOffsets = Arrays.copyOf(runOffsets, n);
            runVertices = Arrays.copyOf(runVertices, n);
            runIndices = Arrays.copyOf(runIndices, n);
            runTypes = Arrays.copyOf(runTypes, n);
        }
    }
}
