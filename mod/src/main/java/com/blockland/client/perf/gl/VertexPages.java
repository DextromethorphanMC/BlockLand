package com.blockland.client.perf.gl;

import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code TERRAIN_PAGES} (rendering spec §20): the vertices of the opaque section layers (solid, cutout mipped, cutout)
 * kept in a few large shared vertex buffers — pages — instead of one buffer per section and layer, so that sections
 * drawn one after another mostly share their vertex buffer and a run of them goes out as one multi-draw with a base
 * vertex per section ({@link PagedDraws}). A layer's range in its page is kept on its {@code SectionBuffers}
 * ({@link Holder}); the buffers' vertex buffer is the page itself, so every reader must draw it with the range's base
 * vertex (the terrain draws, the sun shadow casters), never from the buffer's start. Ranges are allocated first-fit in
 * steps of {@link #GRANULE} bytes, written in place while a mesh fits the range it had (as vanilla rewrites its buffer)
 * and freed when the buffers close; pages are never freed. Flipping the switch rebuilds every section, so the sections
 * drawn with it off hold buffers of their own again; while both kinds exist the draws take each as it is.
 */
public final class VertexPages {
    public static final int PAGE_BYTES = 64 << 20;
    private static final int STRIDE = DefaultVertexFormat.BLOCK.getVertexSize();
    /** A range grows in these steps (whole vertices), so a section rebuilt a little larger keeps its place. */
    private static final int GRANULE = 64 * STRIDE;
    private static final List<Page> PAGES = new ArrayList<>();
    /** Ranges allocated and not yet freed: while any exists, the draws must take pages into account, switch or not. */
    private static int live;

    /** {@code SectionBuffers}: the page range its vertices lie in (null: a buffer of its own, vanilla's way). */
    public interface Holder {
        @Nullable
        Range blockland$pageRange();

        void blockland$setPageRange(@Nullable Range range);
    }

    /** A layer's vertices in a page: {@code offset} bytes into it, {@code capacity} bytes reserved. */
    public static final class Range {
        private final Page page;
        private final int offset, capacity;

        private Range(Page page, int offset, int capacity) {
            this.page = page;
            this.offset = offset;
            this.capacity = capacity;
        }

        /** The page (the buffer the section's buffers hold as their vertex buffer). */
        public GpuBuffer buffer() {
            return page.buffer;
        }

        /** The vertex the range starts at (what the draws add to every index). */
        public int baseVertex() {
            return offset / STRIDE;
        }
    }

    private VertexPages() {
    }

    /** Whether uploads go into pages now (the switch, the batch path it rides on, and the GPU features it needs). */
    public static boolean on() {
        return Optimization.TERRAIN_PAGES.on() && TerrainDraws.on() && PagedDraws.supported();
    }

    /** The layers kept in pages: those drawn without a sort of their own, as one batch per frame. */
    public static boolean paged(ChunkSectionLayer layer) {
        return layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT_MIPPED || layer == ChunkSectionLayer.CUTOUT;
    }

    /** Whether any section layer lies in a page now: the draws then go the paged way whatever the switch says. */
    public static boolean anyPaged() {
        return live > 0;
    }

    /** Whether a layer's buffers lie in a page (drawn from a base vertex) rather than a buffer of their own. */
    public static boolean inPage(Object sectionBuffers) {
        return ((Holder) sectionBuffers).blockland$pageRange() != null;
    }

    /** The base vertex to draw a layer's buffers with (0 for a buffer of its own). */
    public static int baseVertex(Object sectionBuffers) {
        Range range = ((Holder) sectionBuffers).blockland$pageRange();
        return range == null ? 0 : range.baseVertex();
    }

    /**
     * Writes the vertices (position to limit) into a page: the range they had when they fit it, else a new one (the
     * old freed). Render thread.
     */
    public static Range upload(@Nullable Range had, ByteBuffer vertices) {
        int size = vertices.remaining();
        Range range = had;
        if (range == null || range.capacity < size || range.page.buffer.isClosed()) {
            if (range != null) release(range);
            range = allocate(Math.max(GRANULE, (size + GRANULE - 1) / GRANULE * GRANULE));
        }
        if (size > 0) RenderSystem.getDevice().createCommandEncoder().writeToBuffer(range.page.buffer.slice(range.offset, size), vertices);
        return range;
    }

    public static void release(Range range) {
        live--;
        if (!range.page.buffer.isClosed()) range.page.release(range.offset, range.capacity);
    }

    private static Range allocate(int capacity) {
        live++;
        for (Page page : PAGES) {
            if (page.buffer.isClosed()) continue;
            int at = page.allocate(capacity);
            if (at >= 0) return new Range(page, at, capacity);
        }
        Page page = new Page();
        PAGES.add(page);
        int at = page.allocate(capacity);
        if (at < 0) throw new IllegalStateException("A section layer's vertices (" + capacity + " bytes) do not fit a vertex page");
        return new Range(page, at, capacity);
    }

    /** One shared vertex buffer, its free space by offset (neighbouring free blocks joined). */
    private static final class Page {
        final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "BlockLand section vertex page",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, PAGE_BYTES);
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
