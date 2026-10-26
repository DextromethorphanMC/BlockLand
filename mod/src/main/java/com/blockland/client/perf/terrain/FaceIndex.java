package com.blockland.client.perf.terrain;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * {@code FACE_GROUPS}: the index list of the quads a mesh draws for the groups the camera may see (rendering spec §20),
 * kept while the same groups are seen (the camera has to cross a group's planes to change them) and written again when
 * they change — within a time budget per frame; a mesh whose list is not ready this frame is drawn whole (which shows
 * the same pixels). The lists live in a few large shared buffers ({@link Page}), so the draws one after another mostly
 * use the same index buffer and the draw loop does not bind a new one for each. Render thread, outside render passes.
 */
public final class FaceIndex implements AutoCloseable {
    private static final int PAGE_BYTES = 8 << 20, ALIGN = 16;
    private static final List<Page> PAGES = new ArrayList<>();
    private static long deadline;

    @Nullable
    private Page page;
    private int offset, capacity;
    private long key = Long.MIN_VALUE;
    private int indexCount;
    private VertexFormat.IndexType type = VertexFormat.IndexType.SHORT;
    /** The camera the visible groups were worked out for ({@link #reuse}). */
    private double camX = Double.NaN, camY, camZ;
    private int visible;

    /** Starts the budget for index lists written from now on (one per view's preparation). */
    public static void budget(long nanos) {
        deadline = System.nanoTime() + nanos;
    }

    /**
     * The visible groups worked out last, when the camera is still within {@code drift} blocks of where they were worked
     * out (the caller widened the margin by as much), else -1.
     */
    public int reuse(double x, double y, double z, double drift) {
        if (Math.abs(x - camX) <= drift && Math.abs(y - camY) <= drift && Math.abs(z - camZ) <= drift) return visible;
        return -1;
    }

    public void remember(double x, double y, double z, int visible) {
        camX = x;
        camY = y;
        camZ = z;
        this.visible = visible;
    }

    /**
     * The list for {@code key} (whatever identifies the quads drawn: the groups' mask, the units' masks), written now with
     * {@code fill} ({@code indexCount} indices, ints when {@code wide}) when it is another; false when it is not ready (out
     * of budget), then the mesh is drawn whole.
     */
    public boolean ensure(long key, int indexCount, boolean wide, Consumer<ByteBuffer> fill) {
        if (key == this.key && page != null && !page.buffer.isClosed()) return true;
        if (System.nanoTime() > deadline) return false;
        int size = indexCount * (wide ? 4 : 2);
        if (size > PAGE_BYTES) return false;
        if (page == null || page.buffer.isClosed() || capacity < size) {
            free();
            int want = Math.max(ALIGN, (size + ALIGN - 1) / ALIGN * ALIGN);
            for (Page p : PAGES) {
                int at = p.allocate(want);
                if (at >= 0) {
                    page = p;
                    offset = at;
                    capacity = want;
                    break;
                }
            }
            if (page == null) {
                Page p = new Page();
                PAGES.add(p);
                page = p;
                offset = p.allocate(want);
                capacity = want;
            }
        }
        if (size > 0) {
            ByteBuffer data = MemoryUtil.memAlloc(size).order(ByteOrder.nativeOrder());
            try {
                fill.accept(data);
                data.flip();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(page.buffer.slice(offset, size), data);
            } finally {
                MemoryUtil.memFree(data);
            }
        }
        this.key = key;
        this.indexCount = indexCount;
        this.type = wide ? VertexFormat.IndexType.INT : VertexFormat.IndexType.SHORT;
        return true;
    }

    public GpuBuffer buffer() {
        return page.buffer;
    }

    /** Where the list starts in {@link #buffer}, in indices. */
    public int firstIndex() {
        return offset / type.bytes;
    }

    public int indexCount() {
        return indexCount;
    }

    public VertexFormat.IndexType type() {
        return type;
    }

    /** Forgets the list and the visible groups (the mesh changed). */
    public void reset() {
        key = Long.MIN_VALUE;
        camX = Double.NaN;
    }

    private void free() {
        if (page != null && !page.buffer.isClosed()) page.release(offset, capacity);
        page = null;
        capacity = 0;
    }

    @Override
    public void close() {
        free();
        reset();
    }

    /** One shared index buffer, its free space by offset (neighbouring free blocks joined). */
    private static final class Page {
        final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "BlockLand face groups", GpuBuffer.USAGE_INDEX
                | GpuBuffer.USAGE_COPY_DST, PAGE_BYTES);
        private final TreeMap<Integer, Integer> free = new TreeMap<>(Map.of(0, PAGE_BYTES));

        int allocate(int size) {
            for (Map.Entry<Integer, Integer> e : free.entrySet()) {
                if (e.getValue() < size) continue;
                int at = e.getKey(), left = e.getValue() - size;
                free.remove(at);
                if (left > 0) free.put(at + size, left);
                return at;
            }
            return -1;
        }

        void release(int at, int size) {
            Map.Entry<Integer, Integer> before = free.floorEntry(at);
            if (before != null && before.getKey() + before.getValue() == at) {
                at = before.getKey();
                size += before.getValue();
                free.remove(at);
            }
            Integer after = free.get(at + size);
            if (after != null) {
                free.remove(at + size);
                size += after;
            }
            free.put(at, size);
        }
    }
}
