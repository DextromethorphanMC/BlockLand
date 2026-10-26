package com.blockland.client.graphics.far;

import com.blockland.farterrain.FarColumn;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The real far chunks on the GPU (rendering spec §23): units packed into regions of {@link #REGION} × {@link #REGION} units
 * of one scale, one vertex buffer per region and layer in vanilla's block format, so a region is one draw per layer. A unit
 * keeps its
 * place in the buffer; one that leaves is zeroed (degenerate quads), and a buffer that runs full is rebuilt by copying the
 * live columns on the GPU. Drawn with each layer's own vanilla pipeline and atlas, so the lighting pack lights it like the
 * near world. Render thread only.
 */
final class RegionRenderer {
    static final int REGION = 4;
    private static final int VERTEX = DefaultVertexFormat.BLOCK.getVertexSize();
    private static final int QUAD = VERTEX * 4;
    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
    private static final ByteBuffer ZEROS = MemoryUtil.memCalloc(1 << 20);

    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Region> regionOfColumn = new Long2ObjectOpenHashMap<>();
    private long gpuBytes;
    int drawnColumns;

    private static final class Range {
        final int offset, length;

        Range(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }

    private static final class Layer {
        @Nullable
        GpuBuffer buffer;
        int capacity, used, live;
        final Long2ObjectOpenHashMap<Range> ranges = new Long2ObjectOpenHashMap<>();
    }

    private static final class Region {
        final int scale, rx, rz, originY;
        final Layer[] layers = new Layer[LAYERS.length];
        final Long2ObjectOpenHashMap<int[]> heights = new Long2ObjectOpenHashMap<>();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        Region(int scale, int rx, int rz, int originY) {
            this.scale = scale;
            this.rx = rx;
            this.rz = rz;
            this.originY = originY;
        }

        void bounds() {
            minY = Integer.MAX_VALUE;
            maxY = Integer.MIN_VALUE;
            for (int[] h : heights.values()) {
                minY = Math.min(minY, h[0]);
                maxY = Math.max(maxY, h[1]);
            }
        }
    }

    static int regionOf(int unit) {
        return Math.floorDiv(unit, REGION);
    }

    /** The region's corner in its scale's grid (cubes of 2^scale blocks; x, z; y is the level's lowest). */
    static int originOf(int region) {
        return region * REGION * 16;
    }

    /** The region's width in blocks. */
    private static double span(int scale) {
        return (double) (REGION * 16) * (1 << scale);
    }

    /**
     * Puts a column's meshes in place (replacing what it had); frees the mesh's memory. {@code originY}: the height its
     * positions are relative to (the level's lowest block, the same for every column of a level).
     */
    void place(long column, int originY, ColumnMesher.Result mesh) {
        remove(column);
        int scale = FarColumn.scale(column), rx = regionOf(FarColumn.x(column)), rz = regionOf(FarColumn.z(column));
        Region region = regions.computeIfAbsent(FarColumn.key(scale, rx, rz), k -> new Region(scale, rx, rz, originY));
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        for (int i = 0; i < LAYERS.length; i++) {
            ByteBuffer data = mesh.layers.get(LAYERS[i]);
            if (data == null || data.remaining() < QUAD) continue;
            Layer layer = region.layers[i];
            if (layer == null) layer = region.layers[i] = new Layer();
            int length = data.remaining() / QUAD * QUAD;
            if (layer.buffer == null || layer.used + length > layer.capacity) grow(layer, length, encoder);
            data.limit(data.position() + length);
            encoder.writeToBuffer(layer.buffer.slice(layer.used, length), data);
            layer.ranges.put(column, new Range(layer.used, length));
            layer.used += length;
            layer.live += length;
        }
        if (mesh.minY <= mesh.maxY) {
            region.heights.put(column, new int[]{mesh.minY, mesh.maxY});
            region.bounds();
        }
        regionOfColumn.put(column, region);
        mesh.free();
    }

    /** Takes a column out (its quads zeroed); a region left empty gives its buffers back. */
    void remove(long column) {
        Region region = regionOfColumn.remove(column);
        if (region == null) return;
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        boolean empty = true;
        for (Layer layer : region.layers) {
            if (layer == null) continue;
            Range r = layer.ranges.remove(column);
            if (r != null && layer.buffer != null) {
                for (int off = 0; off < r.length; off += ZEROS.capacity()) {
                    int n = Math.min(ZEROS.capacity(), r.length - off);
                    ZEROS.clear().limit(n);
                    encoder.writeToBuffer(layer.buffer.slice(r.offset + off, n), ZEROS);
                }
                layer.live -= r.length;
            }
            if (!layer.ranges.isEmpty()) empty = false;
        }
        region.heights.remove(column);
        if (empty) {
            for (Layer layer : region.layers) {
                if (layer != null && layer.buffer != null) {
                    gpuBytes -= layer.capacity;
                    layer.buffer.close();
                }
            }
            regions.remove(FarColumn.key(region.scale, region.rx, region.rz));
        } else {
            region.bounds();
        }
    }

    /** A bigger buffer with the live columns copied in, packed. */
    private void grow(Layer layer, int adding, CommandEncoder encoder) {
        int need = layer.live + adding;
        int capacity = Math.max(256 * 1024, (int) Math.min(Integer.MAX_VALUE - 1, need * 3L / 2)) / QUAD * QUAD + QUAD;
        GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> "BlockLand far chunks", GpuBuffer.USAGE_VERTEX
                | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, capacity);
        int at = 0;
        if (layer.buffer != null) {
            for (Long2ObjectMap.Entry<Range> e : layer.ranges.long2ObjectEntrySet()) {
                Range r = e.getValue();
                encoder.copyToBuffer(layer.buffer.slice(r.offset, r.length), next.slice(at, r.length));
                e.setValue(new Range(at, r.length));
                at += r.length;
            }
            gpuBytes -= layer.capacity;
            layer.buffer.close();
        }
        layer.buffer = next;
        layer.capacity = capacity;
        layer.used = at;
        layer.live = at;
        gpuBytes += capacity;
    }

    /** One region layer to draw this frame. */
    record Draw(GpuBuffer buffer, int quads, ChunkSectionLayer layer, GpuBufferSlice transform, double distance) {
    }

    /**
     * The visible region layers, nearest first (translucent ones farthest first, at the end), with their transforms made
     * now (before the pass opens).
     */
    List<Draw> prepare(FrustumIntersection frustum, Matrix4f viewRotation, double camX, double camY, double camZ) {
        List<Draw> opaque = new ArrayList<>(), translucent = new ArrayList<>();
        Vector4f white = new Vector4f(1, 1, 1, 1);
        Matrix4f texture = new Matrix4f();
        int columns = 0;
        for (Region region : regions.values()) {
            if (region.minY > region.maxY) continue;
            double span = span(region.scale);
            double x0 = region.rx * span - camX, z0 = region.rz * span - camZ;
            if (!frustum.testAab((float) x0, (float) (region.minY - camY), (float) z0, (float) (x0 + span), (float) (region.maxY - camY),
                    (float) (z0 + span))) continue;
            double dx = Math.max(0, Math.max(x0, -(x0 + span))), dz = Math.max(0, Math.max(z0, -(z0 + span)));
            double distance = dx * dx + dz * dz;
            GpuBufferSlice transform = null;
            for (int i = 0; i < LAYERS.length; i++) {
                Layer layer = region.layers[i];
                if (layer == null || layer.buffer == null || layer.used < QUAD) continue;
                if (transform == null) {
                    Vector3f offset = new Vector3f((float) x0, (float) (region.originY - camY), (float) z0);
                    transform = RenderSystem.getDynamicUniforms().writeTransform(viewRotation, white, offset, texture, 1);
                }
                Draw d = new Draw(layer.buffer, layer.used / QUAD, LAYERS[i], transform, distance);
                if (LAYERS[i] == ChunkSectionLayer.TRANSLUCENT || LAYERS[i] == ChunkSectionLayer.TRIPWIRE) translucent.add(d);
                else opaque.add(d);
            }
            if (transform != null) columns += region.heights.size();
        }
        opaque.sort((a, b) -> Double.compare(a.distance, b.distance));
        translucent.sort((a, b) -> Double.compare(b.distance, a.distance));
        opaque.addAll(translucent);
        drawnColumns = columns;
        return opaque;
    }

    /** The draws {@link #prepare} made, in its order; vanilla's pipelines and atlases per layer. */
    static void draw(RenderPass pass, List<Draw> draws, boolean translucent) {
        ChunkSectionLayer current = null;
        for (Draw d : draws) {
            boolean isTranslucent = d.layer == ChunkSectionLayer.TRANSLUCENT || d.layer == ChunkSectionLayer.TRIPWIRE;
            if (isTranslucent != translucent) continue;
            if (d.layer != current) {
                pass.setPipeline(d.layer.pipeline());
                pass.bindSampler("Sampler0", d.layer.textureView());
                current = d.layer;
            }
            pass.setVertexBuffer(0, d.buffer);
            pass.setUniform("DynamicTransforms", d.transform);
            pass.drawIndexed(0, 0, d.quads * 6, 1);
        }
    }

    static int maxQuads(List<Draw> draws) {
        int n = 0;
        for (Draw d : draws) n = Math.max(n, d.quads);
        return n;
    }

    long gpuBytes() {
        return gpuBytes;
    }

    int columns() {
        return regionOfColumn.size();
    }

    void clear() {
        for (Region region : regions.values()) {
            for (Layer layer : region.layers) if (layer != null && layer.buffer != null) layer.buffer.close();
        }
        regions.clear();
        regionOfColumn.clear();
        gpuBytes = 0;
        drawnColumns = 0;
    }
}
