package com.blockland.client.perf.gl;

import com.blockland.BlockLand;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * {@code TERRAIN_PAGES}: the frame's opaque section draws as {@link PagedDraws} batches — one per layer, in the order
 * vanilla draws the sections — and the opaque group drawn from them ({@link #render}: {@code ChunkSectionsToRender
 * .renderGroup} line for line, the layer's draw list replaced by its batch and the per-draw transform by one shared
 * slice, the sections' own offsets coming from the batch). The pipelines are vanilla's terrain pipelines with the
 * mod's own vertex shader ({@code blockland:core/terrain_pages}: the lighting pack's terrain vertex shader, shared as
 * {@code blockland:terrain_vertex.glsl}, with the storage buffer path), paired with vanilla's fragment shader as the
 * pack overrides it or not; the lighting pack patches them like the originals.
 */
public final class PagedTerrain {
    /** The vertex shader of the pages pipelines: the lighting pack's terrain vertex shader with the storage buffer path. */
    static final ResourceLocation VERTEX = BlockLand.id("core/terrain_pages");
    private static final RenderPipeline SOLID = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
            .withLocation(BlockLand.id("pipeline/terrain_pages_solid")).withVertexShader(VERTEX).build();
    private static final RenderPipeline CUTOUT_MIPPED = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
            .withLocation(BlockLand.id("pipeline/terrain_pages_cutout_mipped")).withVertexShader(VERTEX).withShaderDefine("ALPHA_CUTOUT", 0.5F).build();
    private static final RenderPipeline CUTOUT = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
            .withLocation(BlockLand.id("pipeline/terrain_pages_cutout")).withVertexShader(VERTEX).withShaderDefine("ALPHA_CUTOUT", 0.1F).build();

    /** The frame's batches, taken by {@link #render}. */
    @Nullable
    private static Plan plan;
    private static final Plan POOLED = new Plan();

    private PagedTerrain() {
    }

    /** The paged layers' draws of one frame. */
    public static final class Plan {
        private final PagedDraws.Batch[] batches = {new PagedDraws.Batch(), new PagedDraws.Batch(), new PagedDraws.Batch()};
        @Nullable
        private ChunkSectionsToRender owner;
        @Nullable
        private GpuBufferSlice transform;
        private int maxIndices;

        /** The batch of a paged layer ({@link VertexPages#paged}). */
        public PagedDraws.Batch batch(ChunkSectionLayer layer) {
            return batches[layer == ChunkSectionLayer.SOLID ? 0 : layer == ChunkSectionLayer.CUTOUT_MIPPED ? 1 : 2];
        }
    }

    /** A frame's plan to fill (the last frame's, emptied). */
    public static Plan begin() {
        plan = null;
        Plan p = POOLED;
        for (PagedDraws.Batch batch : p.batches) batch.clear();
        p.owner = null;
        p.transform = null;
        return p;
    }

    /**
     * The plan is complete: {@code owner} is the frame's section draws (its opaque lists empty), {@code transform} the
     * slice every paged draw shares (the view and colour; its own offset is unused), {@code maxIndices} the most
     * sequential indices any draw needs.
     */
    public static void finish(Plan p, ChunkSectionsToRender owner, GpuBufferSlice transform, int maxIndices) {
        p.owner = owner;
        p.transform = transform;
        p.maxIndices = maxIndices;
        plan = p;
    }

    /** Draws the opaque group from the frame's plan; false when this group or frame has none (vanilla draws it). */
    public static boolean render(ChunkSectionsToRender sections, ChunkSectionLayerGroup group) {
        Plan p = plan;
        if (p == null || p.owner != sections || p.transform == null || group != ChunkSectionLayerGroup.OPAQUE) return false;
        RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer sequentialBuffer = p.maxIndices == 0 ? null : sequential.getBuffer(p.maxIndices);
        VertexFormat.IndexType sequentialType = p.maxIndices == 0 ? null : sequential.type();
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = group.outputTarget();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Section layers for " + group.label(),
                target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindSampler("Sampler2", minecraft.gameRenderer.lightTexture().getTextureView());
            pass.setUniform("DynamicTransforms", p.transform);
            for (ChunkSectionLayer layer : group.layers()) {
                PagedDraws.Batch batch = p.batch(layer);
                if (batch.isEmpty()) continue;
                pass.setPipeline(layer == ChunkSectionLayer.SOLID ? SOLID : layer == ChunkSectionLayer.CUTOUT_MIPPED ? CUTOUT_MIPPED : CUTOUT);
                pass.bindSampler("Sampler0", layer.textureView());
                batch.draw((GlRenderPass) pass, sequentialBuffer, sequentialType);
            }
        }
        return true;
    }
}
