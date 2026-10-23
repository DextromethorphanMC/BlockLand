package com.blockland.client.scope;

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
 * Focus while aiming (like COD's ADS blur): the eye is focused on the sight, so a circle around the aim point stays
 * sharp and the world beyond it goes out of focus — not at all at the circle's edge, more and more towards the screen's
 * corners. With a magnified scope the circle is the eyepiece and, with the setting 压暗镜外视野, the world around it also
 * darkens. Runs before the viewmodel is drawn, so the weapon itself stays sharp.
 * <p>
 * Two blur levels give the gradual falloff: the world at a quarter of the screen's size, blurred, and that again at an
 * eighth, blurred — a light and a heavy blur the composite blends between by the distance from the circle. Six small
 * passes, only while aiming.
 */
final class AimFocus {
    private static final RenderPipeline DOWNSAMPLE = screen("scope_downsample", "core/scope_downsample").build();
    private static final RenderPipeline BLUR = screen("scope_blur", "core/scope_blur").withUniform("BlurInfo", UniformType.UNIFORM_BUFFER).build();
    private static final RenderPipeline COMPOSITE = screen("scope_periphery", "core/scope_periphery")
            .withSampler("HeavySampler")
            .withUniform("BlurInfo", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withColorWrite(true, false)
            .build();
    private static final int UBO_SIZE = new Std140SizeCalculator().putVec4().putVec4().putVec4().get();

    /** Quarter-size light blur (and its scratch), eighth-size heavy blur (and its scratch). */
    @Nullable
    private TextureTarget light, lightScratch, heavy, heavyScratch;
    @Nullable
    private MappableRingBuffer lightH, lightV, heavyDown, heavyH, heavyV, composite;
    private double millis;

    private static RenderPipeline.Builder screen(String name, String fragment) {
        return RenderPipeline.builder()
                .withLocation(BlockLand.id("pipeline/" + name))
                .withVertexShader(BlockLand.id("core/scope_screen"))
                .withFragmentShader(BlockLand.id(fragment))
                .withSampler("InSampler")
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS);
    }

    String stats() {
        return light == null ? "off" : String.format("%d×%d, %.2f ms CPU", light.width, light.height, millis);
    }

    /**
     * Blurs (and darkens) the main target around the focus circle.
     *
     * @param x      focus centre, normalised device coordinates
     * @param radius focus radius in half screen heights
     * @param blur   0..1, how far towards the heavy blur the corners go
     * @param dim    0..1
     */
    void render(float x, float y, float radius, float blur, float dim) {
        long start = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        var main = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        if (color == null) return;
        int w4 = Math.max(1, main.width / 4), h4 = Math.max(1, main.height / 4);
        int w8 = Math.max(1, main.width / 8), h8 = Math.max(1, main.height / 8);
        ensureTargets(w4, h4, w8, h8);
        float aspect = (float) main.width / Math.max(1, main.height);
        write(lightH, 1f / w4, 0, x, y, radius, aspect, blur, dim);
        write(lightV, 0, 1f / h4, x, y, radius, aspect, blur, dim);
        write(heavyDown, 0, 0, x, y, radius, aspect, blur, dim);
        write(heavyH, 1f / w8, 0, x, y, radius, aspect, blur, dim);
        write(heavyV, 0, 1f / h8, x, y, radius, aspect, blur, dim);
        write(composite, 0, 0, x, y, radius, aspect, blur, dim);

        if (blur > 0.01f) {
            pass("BlockLand aim focus: downsample", light.getColorTextureView(), DOWNSAMPLE, color, null, null);
            pass("BlockLand aim focus: light H", lightScratch.getColorTextureView(), BLUR, light.getColorTextureView(), null, lightH.currentBuffer());
            pass("BlockLand aim focus: light V", light.getColorTextureView(), BLUR, lightScratch.getColorTextureView(), null, lightV.currentBuffer());
            // a zero step makes the blur shader a plain bilinear fetch: the 2×2 mean of the light blur
            pass("BlockLand aim focus: heavy down", heavy.getColorTextureView(), BLUR, light.getColorTextureView(), null, heavyDown.currentBuffer());
            pass("BlockLand aim focus: heavy H", heavyScratch.getColorTextureView(), BLUR, heavy.getColorTextureView(), null, heavyH.currentBuffer());
            pass("BlockLand aim focus: heavy V", heavy.getColorTextureView(), BLUR, heavyScratch.getColorTextureView(), null, heavyV.currentBuffer());
        }
        pass("BlockLand aim focus: composite", color, COMPOSITE, light.getColorTextureView(), heavy.getColorTextureView(), composite.currentBuffer());
        for (MappableRingBuffer ubo : new MappableRingBuffer[]{lightH, lightV, heavyDown, heavyH, heavyV, composite}) ubo.rotate();
        millis = millis * 0.9 + (System.nanoTime() - start) / 1e6 * 0.1;
    }

    private static void write(MappableRingBuffer ubo, float dx, float dy, float x, float y, float radius, float aspect, float blur, float dim) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(ubo.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putVec4(dx, dy, 0, 0)
                    .putVec4(x, y, radius, aspect)
                    .putVec4(blur, dim, 0, 0);
        }
    }

    private static void pass(String label, GpuTextureView into, RenderPipeline pipeline, GpuTextureView from, @Nullable GpuTextureView heavy,
                             @Nullable GpuBuffer ubo) {
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(6);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, into, OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            if (ubo != null) pass.setUniform("BlurInfo", ubo);
            pass.bindSampler("InSampler", from);
            if (heavy != null) pass.bindSampler("HeavySampler", heavy);
            pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(0, 0, 6, 1);
        }
    }

    private void ensureTargets(int w4, int h4, int w8, int h8) {
        if (composite == null) {
            lightH = ring("light H");
            lightV = ring("light V");
            heavyDown = ring("heavy down");
            heavyH = ring("heavy H");
            heavyV = ring("heavy V");
            composite = ring("composite");
        }
        light = ensure(light, "BlockLand aim focus light", w4, h4);
        lightScratch = ensure(lightScratch, "BlockLand aim focus light scratch", w4, h4);
        heavy = ensure(heavy, "BlockLand aim focus heavy", w8, h8);
        heavyScratch = ensure(heavyScratch, "BlockLand aim focus heavy scratch", w8, h8);
    }

    private static MappableRingBuffer ring(String name) {
        return new MappableRingBuffer(() -> "BlockLand aim focus " + name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
    }

    private static TextureTarget ensure(@Nullable TextureTarget target, String label, int width, int height) {
        if (target == null) {
            target = new TextureTarget(label, width, height, false);
        } else if (target.width == width && target.height == height) {
            return target;
        } else {
            target.resize(width, height);
        }
        target.setFilterMode(FilterMode.LINEAR);
        return target;
    }

    /** Frees the targets (not aimed for a while). */
    void release() {
        for (TextureTarget target : new TextureTarget[]{light, lightScratch, heavy, heavyScratch}) {
            if (target != null) target.destroyBuffers();
        }
        light = lightScratch = heavy = heavyScratch = null;
    }
}
