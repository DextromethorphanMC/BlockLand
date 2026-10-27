package com.blockland.client.graphics.upscale;

import com.blockland.BlockLand;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * BlockLand's own temporal anti-aliasing and upscaling ({@code gfx_taa.fsh}): every frame is drawn with a different
 * sub-pixel jitter (Halton 2,3), and each output pixel blends this frame's nearby samples into the reprojected history,
 * clipped to what this frame shows around it. At render scale 1 it is TAA; below 1 the same pass reconstructs the output
 * resolution from several jittered frames (TAAU).
 */
public final class TemporalUpscaler implements Upscaler {
    private static final RenderPipeline TAA = Fullscreen.pipeline("gfx_taa")
            .withSampler("CurrentSampler")
            .withSampler("HistorySampler")
            .withSampler("WorldDepthSampler")
            .withSampler("HandVelocitySampler")
            .withUniform(com.blockland.client.graphics.post.ObjectMotionBlock.BLOCK, com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();
    /** The same pass reading the hand's motion only on hand pixels ({@link Optimization#POST_DEAD_WORK}). */
    private static final RenderPipeline TAA_HAND_BRANCH = Fullscreen.pipeline("gfx_taa")
            .withLocation(BlockLand.id("pipeline/gfx_taa_hand_branch"))
            .withShaderDefine("HAND_MOTION_IN_BRANCH")
            .withSampler("CurrentSampler")
            .withSampler("HistorySampler")
            .withSampler("WorldDepthSampler")
            .withSampler("HandVelocitySampler")
            .withUniform(com.blockland.client.graphics.post.ObjectMotionBlock.BLOCK, com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();
    /** Share of the current frame in the blend (the history keeps the rest). */
    private static final float BLEND = 0.1f;

    private final RenderTexture[] history = {
            new RenderTexture("BlockLand TAA history A", RenderTexture.Format.RGBA16F, FilterMode.LINEAR),
            new RenderTexture("BlockLand TAA history B", RenderTexture.Format.RGBA16F, FilterMode.LINEAR)};
    private final Ubo ubo = Ubo.pass("TAA").skipUnchanged();
    private final RenderTexture still = new RenderTexture("BlockLand no motion", RenderTexture.Format.RG16F, FilterMode.NEAREST);
    private int current;
    private boolean valid;
    private boolean upscaling;

    @Override
    public String id() {
        return upscaling ? "taau" : "taa";
    }

    @Override
    public boolean temporal() {
        return true;
    }

    /** Jitter phases for a render scale: more below native, so every output pixel is covered by some frame. */
    public static int phases(float scale) {
        return Math.max(8, Math.min(32, Math.round(8 / Math.max(0.1f, scale * scale))));
    }

    /** The Halton (2, 3) jitter of a frame, in pixels of the internal resolution, each in −0.5 … 0.5. */
    public static void jitter(long frame, int phases, float[] out) {
        int index = (int) (frame % phases) + 1;
        out[0] = halton(index, 2) - 0.5f;
        out[1] = halton(index, 3) - 0.5f;
    }

    private static float halton(int index, int base) {
        float result = 0, f = 1;
        while (index > 0) {
            f /= base;
            result += f * (index % base);
            index /= base;
        }
        return result;
    }

    @Override
    public GpuTextureView resolve(Frame frame) {
        var ctx = frame.context();
        int outW = ctx.outputWidth, outH = ctx.outputHeight;
        upscaling = ctx.internalWidth < outW || ctx.internalHeight < outH;
        int previous = current;
        current ^= 1;
        boolean resized = history[current].ensure(outW, outH) | history[previous].ensure(outW, outH);
        if (resized) valid = false;
        boolean historyValid = valid && frame.historyValid() && frame.worldDepth() != null;
        GpuTextureView depth = frame.worldDepth() != null ? frame.worldDepth() : frame.color();
        ubo.pass(frame.reprojection(),
                ctx.internalWidth, ctx.internalHeight, 1f / ctx.internalWidth, 1f / ctx.internalHeight,
                outW, outH, 1f / outW, 1f / outH,
                ctx.jitterX * 0.5f, ctx.jitterY * 0.5f, historyValid ? 1 : 0, BLEND);
        Fullscreen.pass("BlockLand TAA", history[current].view(), Optimization.POST_DEAD_WORK.on() ? TAA_HAND_BRANCH : TAA)
                .sampler("CurrentSampler", frame.color())
                .sampler("HistorySampler", history[previous].view())
                .sampler("WorldDepthSampler", depth)
                .sampler("HandVelocitySampler", ctx.handVelocity != null ? ctx.handVelocity : still())
                .uniform(ubo)
                .uniform(com.blockland.client.graphics.post.ObjectMotionBlock.BLOCK, com.blockland.client.graphics.post.ObjectMotionBlock.slice())
                .draw();
        valid = true;
        return history[current].view();
    }

    /** A 1×1 zero motion texture for frames without the viewmodel's motion. */
    private GpuTextureView still() {
        if (still.ensure(1, 1)) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(still.texture(), 0);
        return still.view();
    }

    @Override
    public void invalidate() {
        valid = false;
    }

    @Override
    public void close() {
        for (RenderTexture texture : history) texture.close();
        still.close();
        ubo.close();
        valid = false;
    }
}
