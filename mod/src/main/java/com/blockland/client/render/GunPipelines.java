package com.blockland.client.render;

import com.blockland.BlockLand;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * GPU pipelines for retained-mode models (blockland:core/gun shader). The vertex is 24 bytes:
 * position (3 floats), uv (2 floats), normal (3 signed bytes + 1 padding) — about half of Minecraft's entity
 * vertex — and lives in a GPU buffer uploaded once per model, so a frame only uploads one small uniform block.
 */
public final class GunPipelines {
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
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

    private GunPipelines() {
    }

    private static RenderPipeline.Builder base(String name) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                .withLocation(BlockLand.id("pipeline/" + name))
                .withVertexShader(BlockLand.id("core/gun"))
                .withFragmentShader(BlockLand.id("core/gun"))
                .withSampler("Sampler0")
                .withSampler("Sampler2")
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withVertexFormat(FORMAT, VertexFormat.Mode.TRIANGLES);
    }

    public static boolean isOpaque(RenderPipeline pipeline) {
        return pipeline == OPAQUE || pipeline == OPAQUE_NO_CULL;
    }
}
