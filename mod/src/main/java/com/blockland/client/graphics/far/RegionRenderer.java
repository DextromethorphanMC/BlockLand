package com.blockland.client.graphics.far;

import com.blockland.client.perf.Optimization;
import com.blockland.client.perf.gl.PagedTerrain;
import com.blockland.client.perf.terrain.FaceGroups;
import com.blockland.client.perf.terrain.FaceIndex;
import com.blockland.farterrain.FarColumn;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
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
import java.util.Arrays;
import java.util.List;

/**
 * The real far chunks on the GPU (rendering spec §23): units packed into regions of {@link #REGION} × {@link #REGION} units
 * of one scale, one vertex buffer per region and layer in vanilla's block format, so a region is one draw per layer. A unit
 * keeps its place in the buffer; one that leaves is zeroed (degenerate quads), and a buffer that runs full is rebuilt by
 * copying the live columns on the GPU. Drawn with each layer's own vanilla pipeline and atlas, so the lighting pack lights
 * it like the near world. With {@code FAR_GPU_CULLING} every region layer also keeps the tables of its units' clusters
 * ({@link Clusters}) in the culling's buffer — in sequential terms, and in the terms of its face-group index list — and
 * is drawn as one indirect multi-draw over the clusters the GPU left in ({@link #drawCulled}). Render thread only.
 */
final class RegionRenderer {
    static final int REGION = 4;
    /** {@code FACE_GROUPS}: time per view for writing index lists of region layers whose visible groups changed. */
    private static final long FACE_INDEX_NANOS = 1_000_000;
    private static final int VERTEX = DefaultVertexFormat.BLOCK.getVertexSize();
    private static final int QUAD = VERTEX * 4;
    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
    /** The opaque layers in the order vanilla draws its own ({@link #drawCulled}). */
    private static final ChunkSectionLayer[] OPAQUE = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT_MIPPED, ChunkSectionLayer.CUTOUT};
    private static final ByteBuffer ZEROS = MemoryUtil.memCalloc(1 << 20);

    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Region> regionOfColumn = new Long2ObjectOpenHashMap<>();
    private long gpuBytes;
    int drawnColumns;
    /** {@code FAR_GPU_CULLING}: the culling whose buffer holds the cluster tables (the one {@link #prepare} was last given). */
    @Nullable
    private GpuCull cull;
    /** A cluster table being written (native order). */
    private ByteBuffer table = MemoryUtil.memAlloc(256 * Clusters.BYTES);

    private static final class Range {
        final int offset, length;
        /** {@code FACE_GROUPS}: the unit's quads of this layer by facing (positions relative to the region), or null. */
        @Nullable
        final FaceGroups groups;
        /** {@code FAR_GPU_CULLING}: the quads in clusters with their boxes, or null (meshed without them). */
        @Nullable
        final Clusters clusters;
        /** The unit's place in the region and its height (blocks): the box round all of it when it has no clusters. */
        final float x0, z0, size, yLo, yHi;

        Range(int offset, int length, @Nullable FaceGroups groups, @Nullable Clusters clusters, float x0, float z0, float size,
              float yLo, float yHi) {
            this.offset = offset;
            this.length = length;
            this.groups = groups;
            this.clusters = clusters;
            this.x0 = x0;
            this.z0 = z0;
            this.size = size;
            this.yLo = yLo;
            this.yHi = yHi;
        }
    }

    private static final class Layer {
        @Nullable
        GpuBuffer buffer;
        int capacity, used, live;
        final Long2ObjectOpenHashMap<Range> ranges = new Long2ObjectOpenHashMap<>();
        /** {@code FACE_GROUPS}: bumped when units come or go; the units in buffer order and their visible groups. */
        long version;
        @Nullable
        Range[] order;
        int[] masks = new int[0];
        long key;
        final FaceIndex index = new FaceIndex();
        /**
         * {@code FAR_GPU_CULLING}: the cluster tables — every quad in the sequential index order (made for
         * {@code seqVersion}) and the quads of the face-group index list in the list's order (written with the list for
         * {@code listKey}).
         */
        final GpuCull.Table seq = new GpuCull.Table(), list = new GpuCull.Table();
        long seqVersion = -1, listKey = Long.MIN_VALUE;

        void changed() {
            version++;
            order = null;
        }
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
        // the unit's place in the region (blocks) and its height, for a box round all of it
        float size = 16 << scale;
        float x0 = (FarColumn.x(column) - rx * REGION) * size, z0 = (FarColumn.z(column) - rz * REGION) * size;
        float yLo = mesh.minY <= mesh.maxY ? mesh.minY - originY : 0, yHi = mesh.minY <= mesh.maxY ? mesh.maxY - originY : 0;
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
            layer.ranges.put(column, new Range(layer.used, length, mesh.groups.get(LAYERS[i]), mesh.clusters.get(LAYERS[i]), x0, z0, size,
                    yLo, yHi));
            layer.changed();
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
                layer.changed();
            }
            if (!layer.ranges.isEmpty()) empty = false;
        }
        region.heights.remove(column);
        if (empty) {
            for (Layer layer : region.layers) {
                if (layer == null) continue;
                if (layer.buffer != null) {
                    gpuBytes -= layer.capacity;
                    layer.buffer.close();
                }
                close(layer);
            }
            regions.remove(FarColumn.key(region.scale, region.rx, region.rz));
        } else {
            region.bounds();
        }
    }

    private void close(Layer layer) {
        layer.index.close();
        if (cull != null) {
            cull.free(layer.seq);
            cull.free(layer.list);
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
                e.setValue(new Range(at, r.length, r.groups, r.clusters, r.x0, r.z0, r.size, r.yLo, r.yHi));
                at += r.length;
            }
            gpuBytes -= layer.capacity;
            layer.buffer.close();
        }
        layer.buffer = next;
        layer.changed();
        layer.capacity = capacity;
        layer.used = at;
        layer.live = at;
        gpuBytes += capacity;
    }

    /**
     * One region layer to draw this frame: the whole buffer, or with {@code FACE_GROUPS} the index list of its units'
     * quads that may face the camera ({@code indexBuffer}, in the buffer's order). With {@code FAR_GPU_CULLING}: its
     * cluster table ({@code clusterBase}, {@code clusterCount} in the culling's buffer, in the terms of the index buffer
     * drawn, whose first index in its own buffer is {@code indexBase}) and its region's corner relative to the camera
     * ({@code ox, oy, oz}; no transform is written then).
     */
    record Draw(GpuBuffer buffer, int quads, ChunkSectionLayer layer, @Nullable GpuBufferSlice transform, double distance,
                @Nullable GpuBuffer indexBuffer, VertexFormat.IndexType indexType, int firstIndex, int indexCount,
                int clusterBase, int clusterCount, int indexBase, float ox, float oy, float oz) {
    }

    /**
     * The visible region layers, nearest first (translucent ones farthest first, at the end), with their transforms (and
     * index lists) made now (before the pass opens). With {@code cull} the region layers' cluster tables are brought up
     * to date for the culling instead of transforms being written.
     */
    List<Draw> prepare(FrustumIntersection frustum, Matrix4f viewRotation, double camX, double camY, double camZ, @Nullable GpuCull cull) {
        this.cull = cull;
        List<Draw> opaque = new ArrayList<>(), translucent = new ArrayList<>();
        Vector4f white = new Vector4f(1, 1, 1, 1);
        Matrix4f texture = new Matrix4f();
        int columns = 0;
        boolean grouped = Optimization.FACE_GROUPS.on();
        FaceIndex.budget(FACE_INDEX_NANOS);
        for (Region region : regions.values()) {
            if (region.minY > region.maxY) continue;
            double span = span(region.scale);
            double x0 = region.rx * span - camX, z0 = region.rz * span - camZ;
            if (!frustum.testAab((float) x0, (float) (region.minY - camY), (float) z0, (float) (x0 + span), (float) (region.maxY - camY),
                    (float) (z0 + span))) continue;
            double dx = Math.max(0, Math.max(x0, -(x0 + span))), dz = Math.max(0, Math.max(z0, -(z0 + span)));
            double distance = dx * dx + dz * dz;
            GpuBufferSlice transform = null;
            boolean drawn = false;
            for (int i = 0; i < LAYERS.length; i++) {
                Layer layer = region.layers[i];
                if (layer == null || layer.buffer == null || layer.used < QUAD) continue;
                GpuBuffer indexBuffer = null;
                int indexCount = layer.used / QUAD * 6, firstIndex = 0;
                boolean listUsed = false;
                if (grouped && FaceGroups.grouped(LAYERS[i])) {
                    // the units' quads that may face the camera, in the buffer's order (places of units gone left out too)
                    int count = visibleIndices(layer, camX - region.rx * span, camY - region.originY, camZ - region.rz * span);
                    if (count == 0) continue;
                    if (count > 0) {
                        // FAR_GPU_CULLING: the list's cluster table is written with the list; a list whose table does not
                        // match (or is gone) is written again, within the budget
                        if (cull != null && (layer.listKey != layer.key || !cull.valid(layer.list))) layer.index.reset();
                        if (layer.index.ensure(layer.key, count, true, out -> writeIndices(layer, out, cull))) {
                            indexBuffer = layer.index.buffer();
                            indexCount = layer.index.indexCount();
                            firstIndex = layer.index.firstIndex();
                            listUsed = true;
                        }
                    }
                }
                int clusterBase = 0, clusterCount = 0;
                if (cull != null) {
                    GpuCull.Table t = listUsed ? layer.list : sequentialTable(layer, cull);
                    clusterBase = t.base;
                    clusterCount = t.count;
                    if (clusterCount == 0) continue;
                } else if (transform == null) {
                    Vector3f offset = new Vector3f((float) x0, (float) (region.originY - camY), (float) z0);
                    transform = RenderSystem.getDynamicUniforms().writeTransform(viewRotation, white, offset, texture, 1);
                }
                drawn = true;
                Draw d = new Draw(layer.buffer, layer.used / QUAD, LAYERS[i], transform, distance, indexBuffer, VertexFormat.IndexType.INT,
                        firstIndex, indexCount, clusterBase, clusterCount, listUsed ? firstIndex : 0, (float) x0, (float) (region.originY - camY),
                        (float) z0);
                if (LAYERS[i] == ChunkSectionLayer.TRANSLUCENT || LAYERS[i] == ChunkSectionLayer.TRIPWIRE) translucent.add(d);
                else opaque.add(d);
            }
            if (drawn) columns += region.heights.size();
        }
        opaque.sort((a, b) -> Double.compare(a.distance, b.distance));
        translucent.sort((a, b) -> Double.compare(b.distance, a.distance));
        opaque.addAll(translucent);
        drawnColumns = columns;
        return opaque;
    }

    /** The layer's units in buffer order (made when units came or went). */
    private static void order(Layer layer) {
        if (layer.order != null) return;
        layer.order = layer.ranges.values().toArray(new Range[0]);
        Arrays.sort(layer.order, (a, b) -> Integer.compare(a.offset, b.offset));
        layer.masks = new int[layer.order.length];
    }

    /**
     * {@code FACE_GROUPS}: each unit's visible groups for a camera at ({@code x, y, z}) relative to the region, kept in the
     * layer ({@code masks}, units in buffer order) with a key for them; the indices they draw, or -1 when every unit draws
     * all of its quads (the whole buffer then).
     */
    private static int visibleIndices(Layer layer, double x, double y, double z) {
        order(layer);
        long key = layer.version * 0x9E3779B97F4A7C15L;
        int count = 0;
        boolean all = true;
        for (int u = 0; u < layer.order.length; u++) {
            Range r = layer.order[u];
            int mask;
            if (r.groups == null || !r.groups.current()) {
                mask = -1;
                count += r.length / QUAD * 6;
            } else {
                mask = r.groups.visible(x, y, z);
                if (!r.groups.all(mask)) all = false;
                count += r.groups.quads(mask) * 6;
            }
            layer.masks[u] = mask;
            key = (key ^ mask) * 0x100000001B3L + u;
        }
        layer.key = key;
        return all ? -1 : count;
    }

    /**
     * The index list {@link #visibleIndices} counted (ints, the buffer's quads in their order); with {@code cull} the
     * list's cluster table along with it: per cluster where its surviving quads' indices start in the list and how many.
     */
    private void writeIndices(Layer layer, ByteBuffer out, @Nullable GpuCull cull) {
        if (cull == null) {
            for (int u = 0; u < layer.order.length; u++) {
                Range r = layer.order[u];
                int base = r.offset / QUAD;
                if (layer.masks[u] != -1) {
                    r.groups.writeIndices(out, layer.masks[u], base, true);
                    continue;
                }
                for (int q = 0, n = r.length / QUAD; q < n; q++) {
                    int v = (base + q) * 4;
                    out.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
                }
            }
            return;
        }
        table.clear();
        int written = 0;
        for (int u = 0; u < layer.order.length; u++) {
            Range r = layer.order[u];
            int base = r.offset / QUAD, n = r.length / QUAD, mask = layer.masks[u];
            Clusters c = r.clusters;
            if (c == null) {
                int start = written;
                written += mask != -1 ? writeMasked(out, r.groups, mask, base, 0, n) : writeAll(out, base, 0, n);
                room(1);
                whole(r, start, written - start);
                continue;
            }
            room(c.count);
            for (int k = 0; k < c.count; k++) {
                int start = written;
                written += mask != -1 ? writeMasked(out, r.groups, mask, base, c.first[k], c.quads[k]) : writeAll(out, base, c.first[k], c.quads[k]);
                entry(c, k, start, written - start);
            }
        }
        table.flip();
        cull.write(layer.list, table);
        layer.listKey = layer.key;
    }

    /** Vanilla's quad indices ({@code 0 1 2 2 3 0} from each quad's first vertex) of the quads whose group the mask holds. */
    private static int writeMasked(ByteBuffer out, FaceGroups groups, int mask, int baseQuad, int from, int count) {
        int written = 0;
        for (int q = from, end = from + count; q < end; q++) {
            if ((mask & 1 << groups.group[q]) == 0) continue;
            int v = (baseQuad + q) * 4;
            out.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
            written += 6;
        }
        return written;
    }

    private static int writeAll(ByteBuffer out, int baseQuad, int from, int count) {
        for (int q = from, end = from + count; q < end; q++) {
            int v = (baseQuad + q) * 4;
            out.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
        }
        return count * 6;
    }

    /**
     * {@code FAR_GPU_CULLING}: the layer's cluster table in sequential terms (every quad, the sequential index buffer),
     * written again when units came or went.
     */
    private GpuCull.Table sequentialTable(Layer layer, GpuCull cull) {
        if (layer.seqVersion == layer.version && cull.valid(layer.seq)) return layer.seq;
        order(layer);
        table.clear();
        for (Range r : layer.order) {
            int base = r.offset / QUAD;
            Clusters c = r.clusters;
            if (c == null) {
                room(1);
                whole(r, base * 6, r.length / QUAD * 6);
                continue;
            }
            room(c.count);
            for (int k = 0; k < c.count; k++) entry(c, k, (base + c.first[k]) * 6, c.quads[k] * 6);
        }
        table.flip();
        cull.write(layer.seq, table);
        layer.seqVersion = layer.version;
        return layer.seq;
    }

    private void room(int clusters) {
        int need = table.position() + clusters * Clusters.BYTES;
        if (need > table.capacity()) table = MemoryUtil.memRealloc(table, Math.max(need, table.capacity() * 2));
    }

    private void entry(Clusters c, int k, int firstIndex, int indexCount) {
        float[] b = c.box;
        table.putFloat(b[k * 6]).putFloat(b[k * 6 + 1]).putFloat(b[k * 6 + 2]).putInt(firstIndex);
        table.putFloat(b[k * 6 + 3]).putFloat(b[k * 6 + 4]).putFloat(b[k * 6 + 5]).putInt(indexCount);
    }

    /** A unit meshed without clusters: one cluster, the box round the whole unit. */
    private void whole(Range r, int firstIndex, int indexCount) {
        table.putFloat(r.x0).putFloat(r.yLo).putFloat(r.z0).putInt(firstIndex);
        table.putFloat(r.x0 + r.size).putFloat(r.yHi).putFloat(r.z0 + r.size).putInt(indexCount);
    }

    /** The draws {@link #prepare} made, in its order; vanilla's pipelines and atlases per layer. */
    static void draw(RenderPass pass, List<Draw> draws, boolean translucent, GpuBuffer indices, VertexFormat.IndexType indexType) {
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
            if (d.indexBuffer != null) {
                pass.setIndexBuffer(d.indexBuffer, d.indexType);
                pass.drawIndexed(0, d.firstIndex, d.indexCount, 1);
                pass.setIndexBuffer(indices, indexType);
            } else {
                pass.drawIndexed(0, 0, d.quads * 6, 1);
            }
        }
    }

    /**
     * {@code FAR_GPU_CULLING}: the draws {@link #prepare} made as indirect multi-draws over the clusters the culling
     * ({@code frame}) left in, with the pages pipelines (the offsets read by draw id) and one shared transform. The opaque
     * layers go layer by layer, nearest first within each, as vanilla orders its own terrain; the translucent ones in
     * the prepared order (farthest first).
     */
    static void drawCulled(RenderPass pass, List<Draw> draws, boolean translucent, GpuBuffer indices, VertexFormat.IndexType indexType,
                           GpuCull cull, GpuCull.Frame frame, GpuBufferSlice transform) {
        cull.beginDraws();
        if (!translucent) {
            for (ChunkSectionLayer layer : OPAQUE) {
                boolean set = false;
                for (int i = 0; i < draws.size(); i++) {
                    Draw d = draws.get(i);
                    if (d.layer != layer) continue;
                    if (!set) {
                        pass.setPipeline(PagedTerrain.pipeline(layer));
                        pass.bindSampler("Sampler0", layer.textureView());
                        pass.setUniform("DynamicTransforms", transform);
                        if (!cull.setup(pass)) break;
                        set = true;
                    }
                    cull.multiDraw(pass, d.buffer, d.indexBuffer != null ? d.indexBuffer : indices, d.indexBuffer != null ? d.indexType : indexType,
                            frame, frame.commandBase[i], d.clusterCount);
                }
            }
            return;
        }
        ChunkSectionLayer current = null;
        for (int i = 0; i < draws.size(); i++) {
            Draw d = draws.get(i);
            if (d.layer != ChunkSectionLayer.TRANSLUCENT && d.layer != ChunkSectionLayer.TRIPWIRE) continue;
            if (d.layer != current) {
                pass.setPipeline(PagedTerrain.pipeline(d.layer));
                pass.bindSampler("Sampler0", d.layer.textureView());
                pass.setUniform("DynamicTransforms", transform);
                if (!cull.setup(pass)) return;
                current = d.layer;
            }
            cull.multiDraw(pass, d.buffer, d.indexBuffer != null ? d.indexBuffer : indices, d.indexBuffer != null ? d.indexType : indexType,
                    frame, frame.commandBase[i], d.clusterCount);
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
            for (Layer layer : region.layers) {
                if (layer == null) continue;
                if (layer.buffer != null) layer.buffer.close();
                close(layer);
            }
        }
        regions.clear();
        regionOfColumn.clear();
        gpuBytes = 0;
        drawnColumns = 0;
    }
}
