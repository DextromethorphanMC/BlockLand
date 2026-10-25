package com.blockland.mixin.gl;

import com.blockland.client.perf.gl.GlStateCache;
import com.blockland.client.perf.gl.ImmediateUploads;
import com.blockland.client.perf.gl.TerrainDraws;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.VertexArrayCache;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Blaze3D's draw setup: {@code GL_STATE}'s caches at trySetup's uniform block, sampler and mip level calls and at the end
 * of a pass ({@link GlStateCache}); {@code TERRAIN_DRAWS}' batch loop and element buffer bind ({@link TerrainDraws});
 * {@code IMMEDIATE_UPLOADS}' vertex buffer offset ({@link ImmediateUploads}).
 */
@Mixin(GlCommandEncoder.class)
public abstract class GlCommandEncoderMixin implements TerrainDraws.Encoder {
    @Shadow
    @Nullable
    private GlProgram lastProgram;

    @Shadow
    private boolean trySetup(GlRenderPass pass, Collection<String> dynamicUniforms) {
        throw new AssertionError();
    }

    @Shadow
    private void drawFromBuffers(GlRenderPass pass, int baseVertex, int firstIndex, int indexCount,
                                 @Nullable VertexFormat.IndexType indexType, GlRenderPipeline pipeline, int instances) {
        throw new AssertionError();
    }

    @Override
    public boolean blockland$trySetup(GlRenderPass pass, Collection<String> dynamicUniforms) {
        return trySetup(pass, dynamicUniforms);
    }

    @Override
    public void blockland$drawFromBuffers(GlRenderPass pass, int baseVertex, int firstIndex, int indexCount,
                                          @Nullable VertexFormat.IndexType indexType, GlRenderPipeline pipeline, int instances) {
        drawFromBuffers(pass, baseVertex, firstIndex, indexCount, indexType, pipeline, instances);
    }

    // ---- GL_STATE ----------------------------------------------------------------------------------------------------

    @Redirect(method = "trySetup", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32;glBindBufferRange(IIIJJ)V"))
    private void blockland$bindUniformBuffer(int target, int index, int buffer, long offset, long length) {
        GlStateCache.bindUniformBuffer(target, index, buffer, offset, length);
    }

    @Redirect(method = "trySetup", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glUniform1i(II)V"))
    private void blockland$samplerUnit(int location, int unit) {
        GlStateCache.samplerUnit(lastProgram, location, unit);
    }

    @Redirect(method = "trySetup", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_texParameter(III)V"))
    private void blockland$mipRange(int target, int pname, int value) {
        GlStateCache.deferMipRange(target, pname, value);
    }

    @Redirect(method = "trySetup", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlTexture;flushModeChanges(I)V"))
    private void blockland$flushSampler(GlTexture texture, int target) {
        GlStateCache.flushSampler(texture, target);
    }

    /** A pass ends: vanilla binds framebuffer 0; with GL_STATE the pass's framebuffer stays bound. */
    @Redirect(method = "finishRenderPass", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glBindFramebuffer(II)V"))
    private void blockland$endPassFramebuffer(int target, int framebuffer) {
        if (!GlStateCache.keepFramebuffer()) GlStateManager._glBindFramebuffer(target, framebuffer);
    }

    // ---- TERRAIN_DRAWS (and the batches' uniform block binds for GL_STATE) --------------------------------------------

    @Inject(method = "executeDrawMultiple", at = @At("HEAD"), cancellable = true)
    private <T> void blockland$drawMultiple(GlRenderPass pass, Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer indexBuffer,
                                            @Nullable VertexFormat.IndexType indexType, Collection<String> dynamicUniforms, T uniformArgument,
                                            CallbackInfo ci) {
        if (TerrainDraws.replacesBatches()) {
            TerrainDraws.drawMultiple(this, pass, draws, indexBuffer, indexType, dynamicUniforms, uniformArgument);
            ci.cancel();
        }
    }

    /** Vanilla's batch bound each draw's uniform blocks directly: the binding cache cannot know them. */
    @Inject(method = "executeDrawMultiple", at = @At("RETURN"))
    private void blockland$afterVanillaBatch(CallbackInfo ci) {
        GlStateCache.forgetUniformBuffers();
    }

    @Redirect(method = "drawFromBuffers", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glBindBuffer(II)V"))
    private void blockland$bindElementBuffer(int target, int buffer) {
        TerrainDraws.bindElementBuffer(target, buffer);
    }

    // ---- IMMEDIATE_UPLOADS -------------------------------------------------------------------------------------------

    @Redirect(method = "drawFromBuffers", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/VertexArrayCache;bindVertexArray(Lcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/opengl/GlBuffer;)V"))
    private void blockland$bindVertexArray(VertexArrayCache cache, VertexFormat format, GlBuffer buffer) {
        ImmediateUploads.bindVertexArray(cache, format, buffer);
    }
}
