package com.blockland.client.graphics.gpu;

import com.blockland.BlockLand;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Full-screen passes: one quad over the whole target with a fragment shader ({@code blockland:core/gfx_screen} as the
 * vertex shader: {@code texCoord} 0..1, the {@code GfxPass} block from {@code blockland:gfx_screen.glsl}). A pass
 * covers every pixel of its target, so nothing is cleared first.
 * <pre>
 * Fullscreen.pass("BlockLand bloom down 2", target.view(), DOWN).sampler("InSampler", source).uniform(ubo).draw();
 * </pre>
 */
public final class Fullscreen {
    /** The name of the shared per-pass block. */
    public static final String PASS_BLOCK = "GfxPass";

    private Fullscreen() {
    }

    /**
     * A pipeline for {@code assets/blockland/shaders/core/<fragment>.fsh} with the {@code GfxPass} block: no depth, no
     * blending; add samplers (and blending or other blocks) before {@code build()}.
     */
    public static RenderPipeline.Builder pipeline(String fragment) {
        return RenderPipeline.builder()
                .withLocation(BlockLand.id("pipeline/" + fragment))
                .withVertexShader(BlockLand.id("core/gfx_screen"))
                .withFragmentShader(BlockLand.id("core/" + fragment))
                .withUniform(PASS_BLOCK, UniformType.UNIFORM_BUFFER)
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS);
    }

    public static Pass pass(String label, GpuTextureView into, RenderPipeline pipeline) {
        return new Pass(label, into, pipeline);
    }

    @Nullable
    private static Ubo empty;

    /** An all-zero {@code GfxPass} block for passes without parameters. */
    private static Ubo emptyPass() {
        if (empty == null) empty = Ubo.pass("empty pass").pass(new org.joml.Matrix4f().zero());
        return empty;
    }

    /** One draw being set up; {@link #draw()} runs it. */
    public static final class Pass {
        private final String label;
        private final GpuTextureView into;
        private final RenderPipeline pipeline;
        private final List<String> samplerNames = new ArrayList<>(4);
        private final List<GpuTextureView> samplers = new ArrayList<>(4);
        private final List<String> uniformNames = new ArrayList<>(2);
        private final List<GpuBufferSlice> uniforms = new ArrayList<>(2);

        private Pass(String label, GpuTextureView into, RenderPipeline pipeline) {
            this.label = label;
            this.into = into;
            this.pipeline = pipeline;
        }

        public Pass sampler(String name, GpuTextureView view) {
            samplerNames.add(name);
            samplers.add(view);
            return this;
        }

        /** The {@code GfxPass} block. */
        public Pass uniform(Ubo ubo) {
            return uniform(PASS_BLOCK, ubo.buffer().slice());
        }

        public Pass uniform(String name, GpuBufferSlice slice) {
            uniformNames.add(name);
            uniforms.add(slice);
            return this;
        }

        public Pass uniform(String name, @Nullable GpuBuffer buffer) {
            return buffer == null ? this : uniform(name, buffer.slice());
        }

        public void draw() {
            // every pipeline declares the block, and Blaze3D's validation wants each declared block bound
            if (!uniformNames.contains(PASS_BLOCK)) uniform(emptyPass());
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indexBuffer = indices.getBuffer(6);
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, into, OptionalInt.empty())) {
                pass.setPipeline(pipeline);
                for (int i = 0; i < uniforms.size(); i++) pass.setUniform(uniformNames.get(i), uniforms.get(i));
                for (int i = 0; i < samplers.size(); i++) pass.bindSampler(samplerNames.get(i), samplers.get(i));
                pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, 0, 6, 1);
            }
        }
    }
}
