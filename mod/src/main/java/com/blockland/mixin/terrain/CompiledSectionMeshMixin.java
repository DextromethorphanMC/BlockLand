package com.blockland.mixin.terrain;

import com.blockland.client.perf.gl.VertexPages;
import com.blockland.client.perf.terrain.FaceGroups;
import com.blockland.client.render.MeshBounds;
import com.blockland.client.render.TerrainAnimation;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * A layer's uploaded buffers carry what its compile found: the facing groups ({@code FACE_GROUPS}, or none), whether
 * the mesh draws animated sprites ({@code client/render/TerrainAnimation}) and the box round its vertices
 * ({@code client/render/MeshBounds}). {@code TERRAIN_PAGES}: an opaque layer's vertices go into a shared page instead
 * of a buffer of their own ({@code client/perf/gl/VertexPages}), the index buffer kept as vanilla keeps it.
 */
@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin {
    @Shadow
    @Final
    private Map<ChunkSectionLayer, SectionBuffers> buffers;

    @Inject(method = "uploadMeshLayer", at = @At("HEAD"), cancellable = true)
    private void blockland$uploadPaged(ChunkSectionLayer layer, MeshData mesh, long section, CallbackInfo ci) {
        if (!VertexPages.on() || !VertexPages.paged(layer)) return;
        SectionBuffers uploaded = buffers.get(layer);
        VertexPages.Holder holder = uploaded == null ? null : (VertexPages.Holder) (Object) uploaded;
        // a layer that holds a buffer of its own is rewritten by vanilla (the page kinds never mix in one layer)
        if (holder != null && holder.blockland$pageRange() == null) return;
        VertexPages.Range range = VertexPages.upload(holder == null ? null : holder.blockland$pageRange(), mesh.vertexBuffer());
        ByteBuffer indices = mesh.indexBuffer();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (uploaded == null) {
            GpuBuffer indexBuffer = indices == null ? null : RenderSystem.getDevice().createBuffer(() -> blockland$label(layer, section),
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, indices);
            uploaded = new SectionBuffers(range.buffer(), indexBuffer, mesh.drawState().indexCount(), mesh.drawState().indexType());
            ((VertexPages.Holder) (Object) uploaded).blockland$setPageRange(range);
            buffers.put(layer, uploaded);
        } else {
            if (uploaded.getVertexBuffer() != range.buffer()) uploaded.setVertexBuffer(range.buffer());
            holder.blockland$setPageRange(range);
            if (indices != null) {
                if (uploaded.getIndexBuffer() != null && uploaded.getIndexBuffer().size() >= indices.remaining()) {
                    if (!uploaded.getIndexBuffer().isClosed()) encoder.writeToBuffer(uploaded.getIndexBuffer().slice(), indices);
                } else {
                    if (uploaded.getIndexBuffer() != null) uploaded.getIndexBuffer().close();
                    uploaded.setIndexBuffer(RenderSystem.getDevice().createBuffer(() -> blockland$label(layer, section),
                            GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, indices));
                }
            } else if (uploaded.getIndexBuffer() != null) {
                uploaded.getIndexBuffer().close();
                uploaded.setIndexBuffer(null);
            }
            uploaded.setIndexCount(mesh.drawState().indexCount());
            uploaded.setIndexType(mesh.drawState().indexType());
        }
        blockland$attach(uploaded, mesh);
        ci.cancel();
    }

    @Inject(method = "uploadMeshLayer", at = @At("TAIL"))
    private void blockland$uploaded(ChunkSectionLayer layer, MeshData mesh, long section, CallbackInfo ci) {
        blockland$attach(buffers.get(layer), mesh);
    }

    /** What the compile found about the mesh, onto the buffers it was uploaded to (the pendings taken either way). */
    private static void blockland$attach(SectionBuffers uploaded, MeshData mesh) {
        FaceGroups groups = FaceGroups.take(mesh);
        boolean animated = TerrainAnimation.take(mesh);
        float[] bounds = MeshBounds.take(mesh);
        if (uploaded == null) return;
        ((FaceGroups.Holder) (Object) uploaded).blockland$setFaceGroups(groups);
        ((TerrainAnimation.Holder) (Object) uploaded).blockland$setAnimatedTextures(animated);
        ((MeshBounds.Holder) (Object) uploaded).blockland$setMeshBounds(bounds);
    }

    private static String blockland$label(ChunkSectionLayer layer, long section) {
        return "Section index buffer - layer: " + layer.label() + "; cords: " + SectionPos.x(section) + ", " + SectionPos.y(section) + ", "
                + SectionPos.z(section);
    }
}
