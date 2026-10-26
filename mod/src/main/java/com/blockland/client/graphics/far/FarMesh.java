package com.blockland.client.graphics.far;

import com.mojang.blaze3d.buffers.GpuBuffer;

import javax.annotation.Nullable;

/** A far tile's mesh on the GPU (rendering spec §22): its vertex buffer (none when the tile has no terrain), quads, height range. */
final class FarMesh {
    @Nullable
    final GpuBuffer buffer;
    final int quads, minY, maxY;

    FarMesh(@Nullable GpuBuffer buffer, int quads, int minY, int maxY) {
        this.buffer = buffer;
        this.quads = quads;
        this.minY = minY;
        this.maxY = maxY;
    }

    long bytes() {
        return (long) quads * 4 * FarVertex.BYTES;
    }

    void close() {
        if (buffer != null && !buffer.isClosed()) buffer.close();
    }
}
