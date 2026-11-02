package com.blockland.client.render;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.client.renderer.chunk.SectionMesh;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Which uploaded section meshes draw animated block textures, told by the compile that built them (rendering spec §20).
 * A layer whose quads carry animated sprites changes on the screen whenever the block atlas cycles its frames (once per
 * client tick): anything alpha-tested against them — the sun shadow map's cutout casters ({@code SHADOW_REUSE}) — can
 * only be kept across ticks for meshes without any. The compile reports each such layer's mesh ({@link #pending},
 * worker thread), the upload moves the fact onto the layer's buffers ({@link #take}, {@link Holder}, render thread), and
 * readers ask the mesh ({@link #animatedCutout}).
 */
public final class TerrainAnimation {
    /** Meshes compiled with animated sprites, until they are uploaded (or dropped: weak keys). */
    private static final Map<MeshData, Boolean> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    private TerrainAnimation() {
    }

    /** A compile's mesh draws animated sprites (worker): remembered until the mesh is uploaded. */
    public static void pending(MeshData mesh) {
        PENDING.put(mesh, Boolean.TRUE);
    }

    /** Whether a mesh being uploaded (render thread) draws animated sprites; forgotten here. */
    public static boolean take(MeshData mesh) {
        return PENDING.remove(mesh) != null;
    }

    /** Whether either alpha-tested layer of a section mesh (cutout, cutout-mipped) draws an animated texture. */
    public static boolean animatedCutout(SectionMesh mesh) {
        return animated(mesh.getBuffers(ChunkSectionLayer.CUTOUT_MIPPED)) || animated(mesh.getBuffers(ChunkSectionLayer.CUTOUT));
    }

    private static boolean animated(@Nullable SectionBuffers buffers) {
        return (Object) buffers instanceof Holder holder && holder.blockland$animatedTextures();
    }

    /** {@code SectionBuffers}: whether the mesh in them draws animated sprites (attached at upload). */
    public interface Holder {
        boolean blockland$animatedTextures();

        void blockland$setAnimatedTextures(boolean animated);
    }
}
