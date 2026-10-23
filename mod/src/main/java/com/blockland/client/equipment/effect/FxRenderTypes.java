package com.blockland.client.equipment.effect;

import com.blockland.BlockLand;
import com.blockland.client.render.ShaderPacks;
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
 * back to front. Under a shader pack the vanilla particle shader is used, so the pack can take it over.
 */
final class FxRenderTypes {
    static final ResourceLocation SMOKE = BlockLand.id("textures/effect/equipment/smoke_puff.png");
    static final ResourceLocation FLAME = BlockLand.id("textures/effect/equipment/flame.png");
    static final ResourceLocation FIREBALL = BlockLand.id("textures/effect/equipment/fireball.png");
    static final ResourceLocation RING = BlockLand.id("textures/effect/equipment/dust_ring.png");
    static final ResourceLocation GLOW = BlockLand.id("textures/effect/glow.png");

    private static final RenderPipeline SOFT = soft(false), SOFT_PACK = soft(true), ADD = additive(false), ADD_PACK = additive(true);
    /** By texture, for [soft, glow] × [own shader, shader pack] (looked up for every sprite, so no string keys). */
    @SuppressWarnings("unchecked")
    private static final Map<ResourceLocation, RenderType>[] types = new Map[]{new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()};

    private FxRenderTypes() {
    }

    private static RenderPipeline.Builder base(boolean pack, String name) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
                .withLocation(BlockLand.id("pipeline/" + name + (pack ? "_pack" : "")))
                .withDepthWrite(false)
                .withCull(false);
        if (!pack) builder.withVertexShader(BlockLand.id("core/fx_particle")).withFragmentShader(BlockLand.id("core/fx_particle"));
        return builder;
    }

    private static RenderPipeline soft(boolean pack) {
        return base(pack, "fx_soft").withBlend(BlendFunction.TRANSLUCENT).build();
    }

    private static RenderPipeline additive(boolean pack) {
        return base(pack, "fx_glow").withBlend(BlendFunction.LIGHTNING).build();
    }

    static RenderType soft(ResourceLocation texture) {
        return of(texture, false);
    }

    static RenderType glow(ResourceLocation texture) {
        return of(texture, true);
    }

    private static RenderType of(ResourceLocation texture, boolean additive) {
        boolean pack = ShaderPacks.active();
        return types[(additive ? 2 : 0) + (pack ? 1 : 0)].computeIfAbsent(texture, t -> RenderType.create(
                "blockland_fx_" + (additive ? "glow_" : "soft_") + (pack ? "pack_" : "") + t.getPath(), 4096, false, true,
                additive ? (pack ? ADD_PACK : ADD) : (pack ? SOFT_PACK : SOFT),
                RenderType.CompositeState.builder()
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false))
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .createCompositeState(false)));
    }
}
