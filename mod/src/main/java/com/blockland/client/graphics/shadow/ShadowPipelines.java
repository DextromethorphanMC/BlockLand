package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Depth-only pipelines of the sun shadow map. No colour is written (the pass's colour attachment only satisfies Blaze3D);
 * faces are drawn from both sides, since Minecraft's meshes leave out faces between solid blocks and thin parts (leaves,
 * plants) are single planes; a slope-scaled depth bias keeps lit faces from shadowing themselves.
 */
final class ShadowPipelines {
    /** {@code ShadowCascade}: mat4 projection into the cascade's tile, vec4 (tile centre xy in NDC, alpha cut-off, -). */
    static final int CASCADE_UBO_SIZE = new Std140SizeCalculator().putMat4f().putVec4().get();
    /** Slope-scaled and constant depth bias of the casters (polygon offset). */
    private static final float BIAS_SLOPE = 1.6f, BIAS_CONSTANT = 3f;

    static final RenderPipeline TERRAIN_SOLID = caster("shadow_terrain_solid", "core/shadow_terrain", "core/shadow_solid", false)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
            .build();
    static final RenderPipeline TERRAIN_CUTOUT = caster("shadow_terrain_cutout", "core/shadow_terrain", "core/shadow_cutout", true)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
            .build();
    static final RenderPipeline ENTITY = caster("shadow_entity", "core/shadow_entity", "core/shadow_cutout", true)
            .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
            .build();
    /** Resets one tile to the far plane (a cascade that is redrawn while the others keep last frame's depth). */
    static final RenderPipeline CLEAR = RenderPipeline.builder()
            .withLocation(BlockLand.id("pipeline/shadow_clear"))
            .withVertexShader(BlockLand.id("core/shadow_clear"))
            .withFragmentShader(BlockLand.id("core/shadow_clear"))
            .withUniform("ShadowCascade", UniformType.UNIFORM_BUFFER)
            .withColorWrite(false)
            .withCull(false)
            .withDepthWrite(true)
            .withDepthTestFunction(DepthTestFunction.GREATER_DEPTH_TEST)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .build();

    private ShadowPipelines() {
    }

    private static RenderPipeline.Builder caster(String name, String vertex, String fragment, boolean alphaTest) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(BlockLand.id("pipeline/" + name))
                .withVertexShader(BlockLand.id(vertex))
                .withFragmentShader(BlockLand.id(fragment))
                .withUniform("ShadowCascade", UniformType.UNIFORM_BUFFER)
                .withColorWrite(false)
                .withCull(false)
                .withDepthWrite(true)
                .withDepthBias(BIAS_SLOPE, BIAS_CONSTANT)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST);
        if (alphaTest) builder.withSampler("Sampler0");
        return builder;
    }
}
