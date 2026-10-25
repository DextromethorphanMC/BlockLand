package com.blockland.mixin.gl;

import com.blockland.client.perf.gl.GlStateCache;
import com.blockland.client.perf.gl.TerrainDraws;
import com.mojang.blaze3d.opengl.GlBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A buffer's GL name, and its deletion dropping it from the binding caches (GL reuses names). */
@Mixin(GlBuffer.class)
public abstract class GlBufferMixin implements GlStateCache.Handle {
    @Shadow
    protected boolean closed;

    @Shadow
    @Final
    protected int handle;

    @Override
    public int blockland$handle() {
        return handle;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void blockland$deleted(CallbackInfo ci) {
        if (closed) return;
        GlStateCache.bufferDeleted(handle);
        TerrainDraws.bufferDeleted(handle);
    }
}
