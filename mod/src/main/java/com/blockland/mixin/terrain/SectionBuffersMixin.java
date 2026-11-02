package com.blockland.mixin.terrain;

import com.blockland.client.perf.gl.VertexPages;
import com.blockland.client.perf.terrain.FaceGroups;
import com.blockland.client.perf.terrain.FaceIndex;
import com.blockland.client.render.MeshBounds;
import com.blockland.client.render.TerrainAnimation;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * {@code FACE_GROUPS}: the facing groups of the buffers' mesh (null: drawn whole) and the index list of the quads drawn;
 * {@code TerrainAnimation}: whether the mesh draws animated sprites; {@code MeshBounds}: the box round its vertices.
 */
@Mixin(SectionBuffers.class)
public abstract class SectionBuffersMixin implements FaceGroups.Holder, TerrainAnimation.Holder, MeshBounds.Holder, VertexPages.Holder {
    @Shadow
    @Nullable
    public abstract GpuBuffer getIndexBuffer();

    /** TERRAIN_PAGES: the page range the vertices lie in (the vertex buffer is then the page). */
    @Unique
    @Nullable
    private VertexPages.Range blockland$range;
    @Unique
    private boolean blockland$animated;
    @Unique
    @Nullable
    private float[] blockland$bounds;
    @Unique
    @Nullable
    private FaceGroups blockland$groups;
    @Unique
    @Nullable
    private FaceIndex blockland$index;

    @Override
    @Nullable
    public FaceGroups blockland$faceGroups() {
        return blockland$groups;
    }

    @Override
    public void blockland$setFaceGroups(@Nullable FaceGroups groups) {
        blockland$groups = groups;
        if (blockland$index != null) blockland$index.reset();
    }

    @Override
    public FaceIndex blockland$faceIndex() {
        if (blockland$index == null) blockland$index = new FaceIndex();
        return blockland$index;
    }

    @Override
    public boolean blockland$animatedTextures() {
        return blockland$animated;
    }

    @Override
    public void blockland$setAnimatedTextures(boolean animated) {
        blockland$animated = animated;
    }

    @Override
    @Nullable
    public float[] blockland$meshBounds() {
        return blockland$bounds;
    }

    @Override
    public void blockland$setMeshBounds(@Nullable float[] bounds) {
        blockland$bounds = bounds;
    }

    @Override
    @Nullable
    public VertexPages.Range blockland$pageRange() {
        return blockland$range;
    }

    @Override
    public void blockland$setPageRange(@Nullable VertexPages.Range range) {
        blockland$range = range;
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void blockland$close(CallbackInfo ci) {
        if (blockland$index != null) blockland$index.close();
        blockland$index = null;
        VertexPages.Range range = blockland$range;
        if (range == null) return;
        // TERRAIN_PAGES: the vertex buffer is a shared page — its range is freed, the rest closed as vanilla would
        blockland$range = null;
        VertexPages.release(range);
        GpuBuffer index = getIndexBuffer();
        if (index != null) index.close();
        ci.cancel();
    }
}
