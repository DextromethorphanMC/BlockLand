package com.blockland.mixin.gl;

import com.blockland.client.perf.gl.GlStateCache;
import com.blockland.client.perf.gl.TerrainDraws;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blaze3D's state calls that it does not cache itself: polygon mode, viewport, scissor box ({@code GL_STATE}) and the
 * vertex array binding ({@code TERRAIN_DRAWS}), each skipped while the context already holds the value.
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {
    @Shadow
    private static int readFbo;
    @Shadow
    private static int writeFbo;

    @Inject(method = "_polygonMode", at = @At("HEAD"), cancellable = true)
    private static void blockland$polygonMode(int face, int mode, CallbackInfo ci) {
        if (GlStateCache.samePolygonMode(face, mode)) ci.cancel();
    }

    @Inject(method = "_viewport", at = @At("HEAD"), cancellable = true)
    private static void blockland$viewport(int x, int y, int width, int height, CallbackInfo ci) {
        if (GlStateCache.sameViewport(x, y, width, height)) ci.cancel();
    }

    @Inject(method = "_scissorBox", at = @At("HEAD"), cancellable = true)
    private static void blockland$scissorBox(int x, int y, int width, int height, CallbackInfo ci) {
        if (GlStateCache.sameScissor(x, y, width, height)) ci.cancel();
    }

    @Inject(method = "_glBindVertexArray", at = @At("HEAD"), cancellable = true)
    private static void blockland$bindVertexArray(int vao, CallbackInfo ci) {
        if (TerrainDraws.sameVertexArray(vao)) ci.cancel();
    }

    /**
     * Deleting a bound framebuffer binds 0 in its place (GL): the binding cache follows, so a new framebuffer that gets
     * the same name is bound for real. Only matters while passes leave their framebuffer bound (GL_STATE); otherwise
     * nothing is bound when Blaze3D deletes one.
     */
    @Inject(method = "_glDeleteFramebuffers", at = @At("HEAD"))
    private static void blockland$framebufferDeleted(int framebuffer, CallbackInfo ci) {
        if (readFbo == framebuffer) readFbo = 0;
        if (writeFbo == framebuffer) writeFbo = 0;
    }
}
