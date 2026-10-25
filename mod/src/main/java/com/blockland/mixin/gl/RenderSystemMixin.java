package com.blockland.mixin.gl;

import com.blockland.client.perf.PerfHooks;
import com.blockland.client.perf.gl.GlStateCache;
import com.blockland.client.perf.gl.ImmediateUploads;
import com.blockland.client.perf.gl.TerrainDraws;
import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * The end of a frame (after the swap, with vanilla's own per-frame ring rotation) for the gl area's caches and rings;
 * the swap and the window's event polls inside it are timed for the freeze log.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Inject(method = "flipFrame", at = @At("RETURN"))
    private static void blockland$endFrame(long window, @Nullable TracyFrameCapture capture, CallbackInfo ci) {
        ImmediateUploads.endFrame();
        GlStateCache.endFrame();
        TerrainDraws.endFrame();
    }

    @Inject(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"))
    private static void blockland$swapStart(long window, @Nullable TracyFrameCapture capture, CallbackInfo ci) {
        PerfHooks.displayStep(PerfHooks.DisplayStep.SWAP, true);
    }

    @Inject(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", shift = At.Shift.AFTER))
    private static void blockland$swapEnd(long window, @Nullable TracyFrameCapture capture, CallbackInfo ci) {
        PerfHooks.displayStep(PerfHooks.DisplayStep.SWAP, false);
    }

    @Inject(method = "flipFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents()V"))
    private static void blockland$pollStart(long window, @Nullable TracyFrameCapture capture, CallbackInfo ci) {
        PerfHooks.displayStep(PerfHooks.DisplayStep.EVENTS, true);
    }

    @Inject(method = "flipFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents()V", shift = At.Shift.AFTER))
    private static void blockland$pollEnd(long window, @Nullable TracyFrameCapture capture, CallbackInfo ci) {
        PerfHooks.displayStep(PerfHooks.DisplayStep.EVENTS, false);
    }
}
