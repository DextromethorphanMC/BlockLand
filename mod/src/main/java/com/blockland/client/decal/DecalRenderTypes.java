package com.blockland.client.decal;

import com.blockland.BlockLand;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * How decals are drawn: Minecraft's particle shader (texture × colour × lightmap, no normal shading — the face shade
 * is baked into the vertex colour), alpha blended, no depth writes, and a depth bias that keeps them on top of the
 * surface they sit on without z-fighting at any distance. One render type (= one draw) per decal texture.
 */
final class DecalRenderTypes {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
            .withLocation(BlockLand.id("pipeline/decal"))
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(false)
            .withCull(false)
            .withDepthBias(-1.0F, -10.0F)
            .build();
    private static final Map<ResourceLocation, RenderType> types = new HashMap<>();

    private DecalRenderTypes() {
    }

    static RenderType of(ResourceLocation texture) {
        return types.computeIfAbsent(texture, t -> RenderType.create("blockland_decal_" + t.getPath(), 1536, false, true, PIPELINE,
                RenderType.CompositeState.builder()
                        .setTextureState(new RenderStateShard.TextureStateShard(t, false))
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .createCompositeState(false)));
    }
}
