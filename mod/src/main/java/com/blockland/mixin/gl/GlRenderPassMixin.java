package com.blockland.mixin.gl;

import com.blockland.client.perf.gl.TerrainDraws;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;

/** A render pass's pipeline, for {@code TERRAIN_DRAWS}' batch loop ({@link TerrainDraws#drawMultiple}). */
@Mixin(GlRenderPass.class)
public abstract class GlRenderPassMixin implements TerrainDraws.Pass {
    @Shadow
    @Nullable
    protected GlRenderPipeline pipeline;

    @Override
    public GlRenderPipeline blockland$pipeline() {
        return pipeline;
    }
}
