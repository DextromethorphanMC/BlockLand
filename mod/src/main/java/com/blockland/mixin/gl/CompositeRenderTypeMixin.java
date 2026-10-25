package com.blockland.mixin.gl;

import com.blockland.client.perf.gl.ImmediateUploads;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/** {@code IMMEDIATE_UPLOADS}: a render type's mesh goes into the immediate rings ({@link ImmediateUploads}). */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public abstract class CompositeRenderTypeMixin {
    @Inject(method = "draw", at = @At("HEAD"))
    private void blockland$beginMesh(MeshData mesh, CallbackInfo ci) {
        ImmediateUploads.begin(mesh);
    }

    @Redirect(method = "draw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexFormat;uploadImmediateVertexBuffer(Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/buffers/GpuBuffer;"))
    private GpuBuffer blockland$vertices(VertexFormat format, ByteBuffer data) {
        return ImmediateUploads.vertices(format, data);
    }

    @Redirect(method = "draw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexFormat;uploadImmediateIndexBuffer(Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/buffers/GpuBuffer;"))
    private GpuBuffer blockland$indices(VertexFormat format, ByteBuffer data) {
        return ImmediateUploads.indices(format, data);
    }

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V"))
    private void blockland$drawIndexed(RenderPass pass, int baseVertex, int firstIndex, int indexCount, int instances) {
        ImmediateUploads.drawIndexed(pass, baseVertex, firstIndex, indexCount, instances);
    }
}
