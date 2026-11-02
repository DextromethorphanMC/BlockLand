package com.blockland.client.graphics.far;

import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * {@code FAR_GPU_CULLING} (rendering spec §23): a far unit's quads of one layer cut into clusters - runs of consecutive
 * quads in the mesh's order, each with the box round its quads - for the GPU to cull by. A run ends after
 * {@link #MAX_QUADS} quads or when the next quad would stretch the box past {@link #EXTENT} cubes along an axis (a
 * section's width; the mesher walks a unit section by section, so a cluster never straddles far-apart blocks). The mesh
 * itself is not touched or reordered. Positions are read as the first three floats of each vertex, in the mesh's space
 * (blocks relative to the region's corner). Worker thread; immutable afterwards.
 */
final class Clusters {
    /** Quads per cluster at most. */
    static final int MAX_QUADS = 512;
    /** A cluster's box spans at most this many grid cubes along an axis. */
    static final int EXTENT = 16;
    /** Bytes per cluster on the GPU: the box's minimum and the first index, the maximum and the index count. */
    static final int BYTES = 32;

    final int count;
    /** Per cluster: min x, y, z, max x, y, z. */
    final float[] box;
    /** Per cluster: its first quad in the mesh and how many quads. */
    final int[] first, quads;

    private Clusters(int count, float[] box, int[] first, int[] quads) {
        this.count = count;
        this.box = box;
        this.first = first;
        this.quads = quads;
    }

    /**
     * The clusters of a vertex buffer of {@code QUADS} ({@code stride} bytes a vertex, from its position to its limit;
     * not changed), the grid's cube being {@code cube} blocks. Null for an empty buffer.
     */
    @Nullable
    static Clusters of(ByteBuffer vertices, int stride, float cube) {
        int quadBytes = stride * 4;
        int total = vertices.remaining() / quadBytes;
        if (total == 0) return null;
        long base = MemoryUtil.memAddress(vertices);
        float extent = EXTENT * cube;
        int capacity = Math.max(4, total / 64 + 2);
        float[] box = new float[capacity * 6];
        int[] first = new int[capacity], quads = new int[capacity];
        int n = 0, open = -1;
        float minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        for (int q = 0; q < total; q++) {
            long at = base + (long) q * quadBytes;
            float qMinX = Float.POSITIVE_INFINITY, qMinY = Float.POSITIVE_INFINITY, qMinZ = Float.POSITIVE_INFINITY;
            float qMaxX = Float.NEGATIVE_INFINITY, qMaxY = Float.NEGATIVE_INFINITY, qMaxZ = Float.NEGATIVE_INFINITY;
            for (int v = 0; v < 4; v++) {
                long p = at + (long) v * stride;
                float x = MemoryUtil.memGetFloat(p), y = MemoryUtil.memGetFloat(p + 4), z = MemoryUtil.memGetFloat(p + 8);
                if (x < qMinX) qMinX = x;
                if (x > qMaxX) qMaxX = x;
                if (y < qMinY) qMinY = y;
                if (y > qMaxY) qMaxY = y;
                if (z < qMinZ) qMinZ = z;
                if (z > qMaxZ) qMaxZ = z;
            }
            boolean start = open < 0 || quads[open] == MAX_QUADS
                    || Math.max(maxX, qMaxX) - Math.min(minX, qMinX) > extent
                    || Math.max(maxY, qMaxY) - Math.min(minY, qMinY) > extent
                    || Math.max(maxZ, qMaxZ) - Math.min(minZ, qMinZ) > extent;
            if (start) {
                if (open >= 0) store(box, open, minX, minY, minZ, maxX, maxY, maxZ);
                if (n == capacity) {
                    capacity *= 2;
                    box = Arrays.copyOf(box, capacity * 6);
                    first = Arrays.copyOf(first, capacity);
                    quads = Arrays.copyOf(quads, capacity);
                }
                open = n++;
                first[open] = q;
                quads[open] = 0;
                minX = qMinX;
                minY = qMinY;
                minZ = qMinZ;
                maxX = qMaxX;
                maxY = qMaxY;
                maxZ = qMaxZ;
            } else {
                if (qMinX < minX) minX = qMinX;
                if (qMinY < minY) minY = qMinY;
                if (qMinZ < minZ) minZ = qMinZ;
                if (qMaxX > maxX) maxX = qMaxX;
                if (qMaxY > maxY) maxY = qMaxY;
                if (qMaxZ > maxZ) maxZ = qMaxZ;
            }
            quads[open]++;
        }
        store(box, open, minX, minY, minZ, maxX, maxY, maxZ);
        return new Clusters(n, box, first, quads);
    }

    private static void store(float[] box, int i, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        box[i * 6] = minX;
        box[i * 6 + 1] = minY;
        box[i * 6 + 2] = minZ;
        box[i * 6 + 3] = maxX;
        box[i * 6 + 4] = maxY;
        box[i * 6 + 5] = maxZ;
    }
}
