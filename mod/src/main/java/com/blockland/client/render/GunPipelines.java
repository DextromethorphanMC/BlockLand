package com.blockland.client.render;

import com.blockland.BlockLand;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * GPU pipelines for retained-mode models (blockland:core/gun shader, PBR). The vertex is 24 bytes: position
 * (3 floats), uv (2 floats) and four unsigned normalized bytes holding the normal (xyz, 0..1 = −1..1) and the baked
 * ambient occlusion (w) — about half of Minecraft's entity vertex — and lives in a GPU buffer uploaded once per model,
 * so a frame only uploads one small uniform block. Samplers: {@code Sampler0} albedo, {@code NormalSampler} and
 * {@code SpecularSampler} the LabPBR {@code _n} / {@code _s} maps (asset spec §20), {@code Sampler2} the lightmap,
 * {@code BlShadowMap} the sun's shadow atlas; the {@code BlockLandScene} block brings the world's lighting (rendering spec §2,
 * §8 — both from {@link SceneLighting}, bound by {@link DrawList}).
 */
public final class GunPipelines {
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Normal", VertexFormatElement.COLOR)
            .build();

    public static final RenderPipeline OPAQUE = base("gun_opaque").withCull(true).build();
    public static final RenderPipeline OPAQUE_NO_CULL = base("gun_opaque_no_cull").withCull(false).build();
    public static final RenderPipeline TRANSLUCENT = base("gun_translucent")
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(false)
            .build();
    public static final RenderPipeline ADDITIVE = base("gun_additive")
            .withCull(false)
            .withBlend(BlendFunction.ADDITIVE)
            .withDepthWrite(false)
            .build();

    /**
     * The viewmodel's motion ({@link DrawList#motion}, rendering spec §4): the same geometry again into the frame's
     * {@link HandMotion} texture, only where it is the nearest surface (its own depth, equal test, no writes).
     */
    public static final RenderPipeline VELOCITY = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(BlockLand.id("pipeline/gun_velocity"))
            .withVertexShader(BlockLand.id("core/gun_velocity"))
            .withFragmentShader(BlockLand.id("core/gun_velocity"))
            .withUniform("HandMotionInfo", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withVertexFormat(FORMAT, VertexFormat.Mode.TRIANGLES)
            .build();

    /**
     * Stateless GPU particles ({@link GpuParticles}): every particle's path is worked out in the vertex shader from its
     * seeds and the draw's parameters; additive, fullbright, depth-tested without writing depth.
     */
    public static final RenderPipeline PARTICLES = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(BlockLand.id("pipeline/gun_particle"))
            .withVertexShader(BlockLand.id("core/gun_particle"))
            .withFragmentShader(BlockLand.id("core/gun_particle"))
            .withSampler("Sampler0")
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(BlendFunction.ADDITIVE)
            .withVertexFormat(FORMAT, VertexFormat.Mode.TRIANGLES)
            .build();

    private GunPipelines() {
    }

    private static RenderPipeline.Builder base(String name) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                .withLocation(BlockLand.id("pipeline/" + name))
                .withVertexShader(BlockLand.id("core/gun"))
                .withFragmentShader(BlockLand.id("core/gun"))
                .withSampler("Sampler0")
                .withSampler("NormalSampler")
                .withSampler("SpecularSampler")
                .withSampler("Sampler2")
                .withSampler("BlShadowMap")
                .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withVertexFormat(FORMAT, VertexFormat.Mode.TRIANGLES);
    }

    public static boolean isOpaque(RenderPipeline pipeline) {
        return pipeline == OPAQUE || pipeline == OPAQUE_NO_CULL;
    }
}
