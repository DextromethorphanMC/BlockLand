package com.blockland.mixin.gl;

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

/** The end of a frame (after the swap, with vanilla's own per-frame ring rotation) for the gl area's caches and rings. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Inject(method = "flipFrame", at = @At("RETURN"))
    private static void blockland$endFrame(long window, @Nullable TracyFrameCapture capture, CallbackInfo ci) {
        ImmediateUploads.endFrame();
        GlStateCache.endFrame();
        TerrainDraws.endFrame();
    }
}
