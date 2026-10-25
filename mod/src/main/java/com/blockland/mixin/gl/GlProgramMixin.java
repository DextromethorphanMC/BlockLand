package com.blockland.mixin.gl;

import com.blockland.client.perf.gl.GlStateCache;
import com.mojang.blaze3d.opengl.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The sampler units already set on a program ({@code GL_STATE}, {@link GlStateCache#samplerUnit}). */
@Mixin(GlProgram.class)
public abstract class GlProgramMixin implements GlStateCache.Units {
    @Unique
    private long blockland$units;

    @Override
    public long blockland$units() {
        return blockland$units;
    }

    @Override
    public void blockland$setUnits(long units) {
        blockland$units = units;
    }
}
