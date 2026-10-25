package com.blockland.mixin.gl;

import com.blockland.client.perf.gl.GlStateCache;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code GL_STATE} on textures: the mip range last sent for the texture ({@link GlStateCache#flushSampler}), and
 * {@code setUseMipmaps} with the flag the texture already has leaving its modes clean.
 */
@Mixin(GlTexture.class)
public abstract class GlTextureMixin extends GpuTexture implements GlStateCache.Levels {
    @Unique
    private int blockland$baseLevel;
    @Unique
    private int blockland$maxLevel;

    private GlTextureMixin() {
        super(0, "", null, 0, 0, 0, 0);
    }

    /**
     * Render types set the flag before every draw; vanilla marks the modes dirty each time and so sends the wrap and
     * filter parameters again. Every change of the fields still marks them (address mode, filter, a different flag).
     */
    @Inject(method = "setUseMipmaps", at = @At("HEAD"), cancellable = true)
    private void blockland$sameMipmaps(boolean useMipmaps, CallbackInfo ci) {
        if (useMipmaps == this.useMipmaps && GlStateCache.skipUnchangedMipmaps()) ci.cancel();
    }

    @Override
    public int blockland$baseLevel() {
        return blockland$baseLevel;
    }

    @Override
    public void blockland$setBaseLevel(int level) {
        blockland$baseLevel = level;
    }

    @Override
    public int blockland$maxLevel() {
        return blockland$maxLevel;
    }

    @Override
    public void blockland$setMaxLevel(int level) {
        blockland$maxLevel = level;
    }
}
