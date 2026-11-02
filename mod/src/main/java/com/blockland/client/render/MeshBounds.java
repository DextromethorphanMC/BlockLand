package com.blockland.client.render;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The box round every vertex of an uploaded section mesh layer (section-relative blocks: min x, y, z, max x, y, z),
 * measured by the compile that built it (rendering spec §20). Block models reach out of their blocks (elements from
 * −16 to 32, rotated ones further) and random offsets move plants, so a section's box is not its casters' box; the
 * vertices are. Readers that must know where a section's geometry can land — the sun shadow map, deciding which
 * sections reach a patch of a tile — ask {@link #of}. The compile reports each layer's mesh ({@link #pending}, worker
 * thread), the upload moves the box onto the layer's buffers ({@link #take}, {@link Holder}, render thread).
 */
public final class MeshBounds {
    /** Meshes measured, until they are uploaded (or dropped: weak keys). */
    private static final Map<MeshData, float[]> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    private MeshBounds() {
    }

    /** The box round a mesh's vertices (position first in the format, {@code stride} bytes per vertex), or null for no vertices. */
    @Nullable
    public static float[] measure(ByteBuffer vertices, int stride) {
        int count = vertices.remaining() / stride;
        if (count == 0) return null;
        long at = MemoryUtil.memAddress(vertices);
        float[] b = {MemoryUtil.memGetFloat(at), MemoryUtil.memGetFloat(at + 4), MemoryUtil.memGetFloat(at + 8), 0, 0, 0};
        b[3] = b[0];
        b[4] = b[1];
        b[5] = b[2];
        for (int v = 1; v < count; v++) {
            long p = at + (long) v * stride;
            float x = MemoryUtil.memGetFloat(p), y = MemoryUtil.memGetFloat(p + 4), z = MemoryUtil.memGetFloat(p + 8);
            if (x < b[0]) b[0] = x;
            if (y < b[1]) b[1] = y;
            if (z < b[2]) b[2] = z;
            if (x > b[3]) b[3] = x;
            if (y > b[4]) b[4] = y;
            if (z > b[5]) b[5] = z;
        }
        return b;
    }

    /** A compile's mesh was measured (worker): remembered until the mesh is uploaded. */
    public static void pending(MeshData mesh, float[] bounds) {
        PENDING.put(mesh, bounds);
    }

    /** The box of a mesh being uploaded (render thread), or null; forgotten here. */
    @Nullable
    public static float[] take(MeshData mesh) {
        return PENDING.remove(mesh);
    }

    /**
     * The box round the vertices of a section mesh's shadow-casting layers (solid, cutout-mipped, cutout) into
     * {@code out}; false when none of them has a box (nothing to cast, or a mesh uploaded without one).
     */
    public static boolean of(SectionMesh mesh, float[] out) {
        boolean any = false;
        any |= add(mesh.getBuffers(ChunkSectionLayer.SOLID), out, any);
        any |= add(mesh.getBuffers(ChunkSectionLayer.CUTOUT_MIPPED), out, any);
        any |= add(mesh.getBuffers(ChunkSectionLayer.CUTOUT), out, any);
        return any;
    }

    private static boolean add(@Nullable SectionBuffers buffers, float[] out, boolean started) {
        if (!((Object) buffers instanceof Holder holder)) return false;
        float[] b = holder.blockland$meshBounds();
        if (b == null) return false;
        if (!started) {
            System.arraycopy(b, 0, out, 0, 6);
            return true;
        }
        for (int i = 0; i < 3; i++) {
            out[i] = Math.min(out[i], b[i]);
            out[i + 3] = Math.max(out[i + 3], b[i + 3]);
        }
        return true;
    }

    /** {@code SectionBuffers}: the box round the mesh in them (attached at upload; null: not measured). */
    public interface Holder {
        @Nullable
        float[] blockland$meshBounds();

        void blockland$setMeshBounds(@Nullable float[] bounds);
    }
}
