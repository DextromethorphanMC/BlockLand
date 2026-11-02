package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import com.blockland.client.render.GunPipelines;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Depth-only pipelines of the sun shadow map. No colour is written (the pass's colour attachment only satisfies Blaze3D);
 * faces are drawn from both sides: an alpha-tested face is not closed at the texel level (a ray through a transparent
 * texel of a leaf's front face is stopped by an opaque texel of its back face), and even the solid layer's closed boxes
 * leave a few texels an ulp apart when only their front faces are drawn (coincident faces of neighbours rasterise their
 * depth a little differently) — culling was tried and measured (spec §6). A slope-scaled depth bias keeps lit faces
 * from shadowing themselves.
 */
final class ShadowPipelines {
    /** {@code ShadowCascade}: mat4 projection into the cascade's tile, vec4 (tile centre xy in NDC, alpha cut-off, -). */
    static final int CASCADE_UBO_SIZE = new Std140SizeCalculator().putMat4f().putVec4().get();
    /** Slope-scaled and constant depth bias of the casters (polygon offset). */
    private static final float BIAS_SLOPE = 1.6f, BIAS_CONSTANT = 3f;

    /**
     * The terrain casters' pipelines, by {@link #terrain}: solid or cutout (alpha-tested), the section's offset from
     * {@code DynamicTransforms} or by draw id from the batch's storage buffer ({@code TERRAIN_PAGES}, {@code BL_PAGES}
     * in shadow_terrain.vsh).
     */
    private static final RenderPipeline[] TERRAIN = new RenderPipeline[4];

    static {
        for (int i = 0; i < 4; i++) {
            boolean cutout = (i & 2) != 0, pages = (i & 1) != 0;
            String name = "shadow_terrain_" + (cutout ? "cutout" : "solid") + (pages ? "_pages" : "");
            RenderPipeline.Builder builder = caster(name, "core/shadow_terrain", cutout ? "core/shadow_cutout" : "core/shadow_solid", cutout)
                    .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS);
            if (pages) builder.withShaderDefine("BL_PAGES");
            else builder.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER);
            TERRAIN[i] = builder.build();
        }
    }

    static RenderPipeline terrain(boolean cutout, boolean pages) {
        return TERRAIN[(cutout ? 2 : 0) | (pages ? 1 : 0)];
    }
    static final RenderPipeline ENTITY = caster("shadow_entity", "core/shadow_entity", "core/shadow_cutout", true)
            .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
            .build();
    /**
     * SHADOW_GPU_CASTERS: a BlockLand model's triangles from its own buffers ({@code GunPipelines.FORMAT}, triangle
     * lists), placed per draw by {@code DynamicTransforms} exactly as the CPU would place them (shadow_entity_gpu.vsh).
     */
    static final RenderPipeline ENTITY_GPU = caster("shadow_entity_gpu", "core/shadow_entity_gpu", "core/shadow_cutout", true)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(GunPipelines.FORMAT, VertexFormat.Mode.TRIANGLES)
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
