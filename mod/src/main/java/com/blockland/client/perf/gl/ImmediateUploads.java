package com.blockland.client.perf.gl;

import com.blockland.BlockLand;
import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.GraphicsWorkarounds;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.VertexArrayCache;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.lwjgl.opengl.ARBVertexAttribBinding;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * {@code IMMEDIATE_UPLOADS} (rendering spec §20): immediate-mode draws ({@code RenderType} meshes — entities, particles,
 * block entities, text) put their vertices and indices into persistently mapped ring buffers instead of rewriting one
 * buffer per vertex format hundreds of times a frame while the GPU may still read it.
 * <p>
 * Each frame appends to the ring's current buffer (one buffer per frame, three in turn, the next one only written after
 * the GPU passed the fence set when it was last left — {@link MappableRingBuffer}, as the GUI's vertex buffers), so no
 * region is written while a draw may still read it. The draw reads the same bytes:
 * <ul>
 * <li>vertices: the vertex buffer is bound at the mesh's offset ({@code glBindVertexBuffer(0, ring, offset, stride)}),
 *     so the draw and every index — and {@code gl_VertexID}, which BlockLand's terrain shader and vanilla's lines read —
 *     are exactly vanilla's (no base vertex);</li>
 * <li>indices (sorted translucent meshes): the draw starts at the mesh's first index in the ring.</li>
 * </ul>
 * A mesh that does not fit takes vanilla's path, and the ring grows for the next frames. Needs separate vertex
 * attribute binding and persistent mapping, and none of vanilla's immediate-buffer driver workarounds; otherwise (and
 * off) everything is vanilla's.
 */
public final class ImmediateUploads {
    private static final int ALIGN = 64;
    private static final int VERTEX_START = 4 << 20;
    private static final int INDEX_START = 1 << 20;
    private static final int MAX_SIZE = 64 << 20;

    /** -1 not probed yet, 0 unsupported, 1 supported. */
    private static int support = -1;
    @Nullable
    private static Ring vertices, indices;

    /** The mesh being drawn ({@code CompositeRenderType.draw}). */
    @Nullable
    private static VertexFormat.IndexType meshIndexType;
    @Nullable
    private static GpuBuffer meshVertexBuffer;
    private static int meshVertexOffset = -1;
    private static int meshIndexOffset = -1;

    /** The vertex buffer to bind at an offset during the draw call in progress. */
    @Nullable
    private static GpuBuffer armed;
    private static long armedOffset;

    private ImmediateUploads() {
    }

    /** {@code CompositeRenderType.draw} starts with this mesh. */
    public static void begin(MeshData mesh) {
        meshIndexType = mesh.drawState().indexType();
        meshVertexBuffer = null;
        meshVertexOffset = -1;
        meshIndexOffset = -1;
    }

    /** The draw's {@code format.uploadImmediateVertexBuffer(data)}. */
    public static GpuBuffer vertices(VertexFormat format, ByteBuffer data) {
        meshVertexBuffer = null;
        meshVertexOffset = -1;
        if (Optimization.IMMEDIATE_UPLOADS.on() && supported()) {
            if (vertices == null) vertices = new Ring("BlockLand immediate vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, VERTEX_START);
            int offset = vertices.write(data);
            if (offset >= 0) {
                meshVertexBuffer = vertices.current;
                meshVertexOffset = offset;
                return meshVertexBuffer;
            }
        }
        return format.uploadImmediateVertexBuffer(data);
    }

    /** The draw's {@code format.uploadImmediateIndexBuffer(data)}. */
    public static GpuBuffer indices(VertexFormat format, ByteBuffer data) {
        meshIndexOffset = -1;
        if (Optimization.IMMEDIATE_UPLOADS.on() && supported() && meshIndexType != null) {
            if (indices == null) indices = new Ring("BlockLand immediate indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, INDEX_START);
            int offset = indices.write(data);
            if (offset >= 0) {
                meshIndexOffset = offset;
                return indices.current;
            }
        }
        return format.uploadImmediateIndexBuffer(data);
    }

    /** The draw's {@code pass.drawIndexed(0, 0, count, 1)}: from the mesh's first index, its vertices at their offset. */
    public static void drawIndexed(RenderPass pass, int baseVertex, int firstIndex, int indexCount, int instances) {
        if (meshIndexOffset >= 0 && meshIndexType != null) firstIndex += meshIndexOffset / meshIndexType.bytes;
        if (meshVertexOffset >= 0) {
            armed = meshVertexBuffer;
            armedOffset = meshVertexOffset;
        }
        try {
            pass.drawIndexed(baseVertex, firstIndex, indexCount, instances);
        } finally {
            armed = null;
            meshVertexBuffer = null;
            meshVertexOffset = -1;
            meshIndexOffset = -1;
        }
    }

    /** drawFromBuffers' vertex array bind: a ring mesh's vertices are bound at its offset right after. */
    public static void bindVertexArray(VertexArrayCache cache, VertexFormat format, GlBuffer buffer) {
        cache.bindVertexArray(format, buffer);
        if (armed != null && buffer == armed) {
            ARBVertexAttribBinding.glBindVertexBuffer(0, GlStateCache.handle(buffer), armedOffset, format.getVertexSize());
        }
    }

    /** End of the frame (after the swap): used rings move on to their next buffer. */
    public static void endFrame() {
        if (vertices != null) vertices.endFrame();
        if (indices != null) indices.endFrame();
    }

    private static boolean supported() {
        if (support < 0) support = probe() ? 1 : 0;
        return support == 1;
    }

    private static boolean probe() {
        GpuDevice device = RenderSystem.getDevice();
        if (!(device instanceof GlDevice gl)) return false;
        // binding the vertex buffer at an offset needs ARB_vertex_attrib_binding (VertexArrayCache.Separate)
        if (!gl.vertexArrayCache().getClass().getName().endsWith("$Separate")) return false;
        // persistently mapped buffers (BufferStorage.Immutable), as the ring buffers vanilla uses for the GUI
        if (!gl.getBufferStorage().getClass().getName().endsWith("$Immutable")) return false;
        // vanilla's own workarounds for immediate buffers (Intel gen 11, Windows on ARM) and VertexArrayCache's Mesa rebind
        if (GraphicsWorkarounds.get(device).alwaysCreateFreshImmediateBuffer()) return false;
        if (Util.getPlatform() == Util.OS.WINDOWS && Util.isAarch64()) return false;
        if ("Mesa".equals(GlStateManager._getString(GL11.GL_VENDOR))) return false;
        return true;
    }

    /** One ring: appends within the frame's buffer, rotates at the frame's end, grows after a frame that overflowed. */
    private static final class Ring {
        private final String label;
        private final int usage;
        private MappableRingBuffer ring;
        private int size;
        /** Bytes used in the current buffer this frame. */
        private int used;
        /** The most this frame wanted (grows the ring when above its size). */
        private int demand;
        @Nullable
        GpuBuffer current;

        Ring(String label, int usage, int size) {
            this.label = label;
            this.usage = usage;
            this.size = size;
            this.ring = new MappableRingBuffer(() -> label, usage, size);
        }

        /** Copies the data in; its offset, or -1 when it does not fit this frame. */
        int write(ByteBuffer data) {
            int bytes = data.remaining();
            int offset = (used + ALIGN - 1) & -ALIGN;
            long end = (long) offset + bytes;
            if (bytes <= 0 || end > size) {
                demand = (int) Math.min(Integer.MAX_VALUE, Math.max(demand, end));
                return -1;
            }
            // waits (once per frame) until the GPU is done with this buffer's previous frame
            GpuBuffer buffer = ring.currentBuffer();
            try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer.slice(offset, bytes), false, true)) {
                MemoryUtil.memCopy(data, view.data());
            }
            used = (int) end;
            current = buffer;
            return offset;
        }

        void endFrame() {
            if (used > 0) {
                ring.rotate();
                used = 0;
            }
            current = null;
            if (demand > size && size < MAX_SIZE) {
                int grown = demand >= MAX_SIZE ? MAX_SIZE : Integer.highestOneBit(demand - 1) << 1;
                ring.close();
                size = grown;
                ring = new MappableRingBuffer(() -> label, usage, size);
                BlockLand.LOGGER.debug("[BlockLand] {} grown to {} KiB", label, size >> 10);
            }
            demand = 0;
        }
    }
}
