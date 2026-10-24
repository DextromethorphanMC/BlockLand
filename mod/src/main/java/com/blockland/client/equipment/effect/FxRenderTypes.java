package com.blockland.client.equipment.effect;

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
 * How effect sprites are drawn in the particle pass: <i>soft</i> (alpha blended: smoke, flames, fireballs, dust) and
 * <i>glow</i> (additive: the flash of a blast, the glow under flames). Both are Minecraft's particle shader (texture ×
 * colour × lightmap, fog) without its hard alpha cut, depth tested against the world but not writing depth, sorted
 * back to front.
 */
final class FxRenderTypes {
    static final ResourceLocation SMOKE = BlockLand.id("textures/effect/equipment/smoke_puff.png");
    static final ResourceLocation FLAME = BlockLand.id("textures/effect/equipment/flame.png");
    static final ResourceLocation FIREBALL = BlockLand.id("textures/effect/equipment/fireball.png");
    static final ResourceLocation RING = BlockLand.id("textures/effect/equipment/dust_ring.png");
    static final ResourceLocation GLOW = BlockLand.id("textures/effect/glow.png");

    private static final RenderPipeline SOFT = base("fx_soft").withBlend(BlendFunction.TRANSLUCENT).build();
    private static final RenderPipeline ADD = base("fx_glow").withBlend(BlendFunction.LIGHTNING).build();
    /** By texture, for [soft, glow] (looked up for every sprite, so no string keys). */
    @SuppressWarnings("unchecked")
    private static final Map<ResourceLocation, RenderType>[] types = new Map[]{new HashMap<>(), new HashMap<>()};

    private FxRenderTypes() {
    }

    private static RenderPipeline.Builder base(String name) {
        return RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
                .withLocation(BlockLand.id("pipeline/" + name))
                .withDepthWrite(false)
                .withCull(false)
                .withVertexShader(BlockLand.id("core/fx_particle"))
                .withFragmentShader(BlockLand.id("core/fx_particle"));
    }

    static RenderType soft(ResourceLocation texture) {
        return of(texture, false);
    }

    static RenderType glow(ResourceLocation texture) {
        return of(texture, true);
    }

    private static RenderType of(ResourceLocation texture, boolean additive) {
        return types[additive ? 1 : 0].computeIfAbsent(texture, t -> RenderType.create(
                "blockland_fx_" + (additive ? "glow_" : "soft_") + t.getPath(), 4096, false, true, additive ? ADD : SOFT,
                RenderType.CompositeState.builder()
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false))
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .createCompositeState(false)));
    }
}
