package com.blockland.mixin.terrain;

import com.blockland.client.perf.terrain.FaceGroups;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** {@code FACE_GROUPS}: a layer's uploaded buffers carry the facing groups its compile made (or none). */
@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin {
    @Shadow
    @Final
    private Map<ChunkSectionLayer, SectionBuffers> buffers;

    @Inject(method = "uploadMeshLayer", at = @At("TAIL"))
    private void blockland$uploaded(ChunkSectionLayer layer, MeshData mesh, long section, CallbackInfo ci) {
        SectionBuffers uploaded = buffers.get(layer);
        FaceGroups groups = FaceGroups.take(mesh);
        if (uploaded != null) ((FaceGroups.Holder) (Object) uploaded).blockland$setFaceGroups(groups);
    }
}
