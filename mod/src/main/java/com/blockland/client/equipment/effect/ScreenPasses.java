package com.blockland.client.equipment.effect;

import com.blockland.BlockLand;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;

import javax.annotation.Nullable;
import java.util.OptionalInt;

/**
 * Full-screen passes of the screen effects (the flashbang's white-out, the adrenaline rush): one quad over the main
 * target with a fragment shader, a copy of the frame where a shader needs to read it, and a small uniform block
 * ({@code EffectInfo}: two vec4). Run from the HUD's first layer — after the world and the hand, before any HUD
 * element is drawn — and only without a shader pack (the callers fall back to plain HUD drawing there).
 */
final class ScreenPasses {
    static final RenderPipeline COPY = screen("effect_copy").build();
    static final RenderPipeline FLASH = screen("effect_flash")
            .withUniform("EffectInfo", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withColorWrite(true, false)
            .build();
    static final RenderPipeline RUSH = screen("effect_adrenaline")
            .withUniform("EffectInfo", UniformType.UNIFORM_BUFFER)
            .withColorWrite(true, false)
            .build();
    private static final int UBO_SIZE = new Std140SizeCalculator().putVec4().putVec4().get();

    private ScreenPasses() {
    }

    private static RenderPipeline.Builder screen(String fragment) {
        return RenderPipeline.builder()
                .withLocation(BlockLand.id("pipeline/" + fragment))
                .withVertexShader(BlockLand.id("core/effect_screen"))
                .withFragmentShader(BlockLand.id("core/" + fragment))
                .withSampler("InSampler")
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS);
    }

    /** The colour texture the frame is being drawn into, or null. */
    @Nullable
    static GpuTextureView mainColor() {
        var main = Minecraft.getInstance().getMainRenderTarget();
        return RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
    }

    /** A target the size of the main one (created or resized as needed). */
    static TextureTarget fullSize(@Nullable TextureTarget target, String label) {
        var main = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) {
            target = new TextureTarget(label, main.width, main.height, false);
        } else if (target.width != main.width || target.height != main.height) {
            target.resize(main.width, main.height);
        } else {
            return target;
        }
        target.setFilterMode(FilterMode.LINEAR);
        return target;
    }

    static MappableRingBuffer ring(String name) {
        return new MappableRingBuffer(() -> "BlockLand " + name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
    }

    static void write(MappableRingBuffer ubo, float a, float b, float c, float d, float e, float f, float g, float h) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(ubo.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(a, b, c, d).putVec4(e, f, g, h);
        }
    }

    static void pass(String label, GpuTextureView into, RenderPipeline pipeline, GpuTextureView from, @Nullable MappableRingBuffer ubo) {
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(6);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, into, OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            if (ubo != null) pass.setUniform("EffectInfo", ubo.currentBuffer());
            pass.bindSampler("InSampler", from);
            pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(0, 0, 6, 1);
        }
        if (ubo != null) ubo.rotate();
    }
}
