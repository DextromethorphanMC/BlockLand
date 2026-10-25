package com.blockland.client.graphics.post;

import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;

/**
 * Auto exposure ({@code gfx_luminance.fsh}): the frame's mean log luminance, measured on a 64×64 copy and reduced 64 → 16
 * → 4 → 1 on the GPU (never read back), then eased towards over time. The final pass turns it into a gentle exposure change (a fraction of an EV each way), so caves get a little
 * brighter and snow a little darker without changing Minecraft's look.
 */
final class Exposure implements AutoCloseable {
    private static final RenderPipeline PASS = Fullscreen.pipeline("gfx_luminance").withSampler("InSampler").withSampler("PreviousSampler").build();
    private static final Matrix4f NONE = new Matrix4f();
    /** Adaptation speed (1/s): about a second and a half to settle, like an eye. */
    private static final float SPEED = 1.6f;

    private final RenderTexture l64 = new RenderTexture("BlockLand exposure 64", RenderTexture.Format.R16F, FilterMode.LINEAR);
    private final RenderTexture l16 = new RenderTexture("BlockLand exposure 16", RenderTexture.Format.R16F, FilterMode.LINEAR);
    private final RenderTexture l4 = new RenderTexture("BlockLand exposure 4", RenderTexture.Format.R16F, FilterMode.LINEAR);
    private final RenderTexture l1 = new RenderTexture("BlockLand exposure 1", RenderTexture.Format.R16F, FilterMode.LINEAR);
    private final RenderTexture[] adapted = {
            new RenderTexture("BlockLand exposure A", RenderTexture.Format.R16F, FilterMode.NEAREST),
            new RenderTexture("BlockLand exposure B", RenderTexture.Format.R16F, FilterMode.NEAREST)};
    private final Ubo measure = Ubo.pass("exposure measure").skipUnchanged();
    private final Ubo reduce16 = Ubo.pass("exposure 16").skipUnchanged();
    private final Ubo reduce4 = Ubo.pass("exposure 4").skipUnchanged();
    private final Ubo reduce1 = Ubo.pass("exposure 1").skipUnchanged();
    private final Ubo adapt = Ubo.pass("exposure adapt").skipUnchanged();
    private int current;
    private boolean valid;

    /** Measures {@code color} and returns the adapted log2 luminance (1×1, red). */
    GpuTextureView render(GpuTextureView color, int width, int height, float seconds) {
        l64.ensure(64, 64);
        l16.ensure(16, 16);
        l4.ensure(4, 4);
        l1.ensure(1, 1);
        int previous = current;
        current ^= 1;
        if (adapted[current].ensure(1, 1) | adapted[previous].ensure(1, 1)) valid = false;
        measure.pass(NONE, 1f / 64, 1f / 64, 0);
        reduce16.pass(NONE, 1f / 64, 1f / 64, 1);
        reduce4.pass(NONE, 1f / 16, 1f / 16, 1);
        reduce1.pass(NONE, 1f / 4, 1f / 4, 1);
        Fullscreen.pass("BlockLand exposure measure", l64.view(), PASS).sampler("InSampler", color).sampler("PreviousSampler", color)
                .uniform(measure).draw();
        Fullscreen.pass("BlockLand exposure 16", l16.view(), PASS).sampler("InSampler", l64.view()).sampler("PreviousSampler", l64.view())
                .uniform(reduce16).draw();
        Fullscreen.pass("BlockLand exposure 4", l4.view(), PASS).sampler("InSampler", l16.view()).sampler("PreviousSampler", l16.view())
                .uniform(reduce4).draw();
        Fullscreen.pass("BlockLand exposure 1", l1.view(), PASS).sampler("InSampler", l4.view()).sampler("PreviousSampler", l4.view())
                .uniform(reduce1).draw();
        // the measurement never comes back to the CPU (no stall), so one speed serves both directions
        float k = valid ? 1 - (float) Math.exp(-Math.max(0, seconds) * SPEED) : 1;
        adapt.pass(NONE, 0, 0, 2, k);
        Fullscreen.pass("BlockLand exposure adapt", adapted[current].view(), PASS).sampler("InSampler", l1.view())
                .sampler("PreviousSampler", adapted[previous].view()).uniform(adapt).draw();
        valid = true;
        return adapted[current].view();
    }

    void invalidate() {
        valid = false;
    }

    @Override
    public void close() {
        l64.close();
        l16.close();
        l4.close();
        l1.close();
        for (RenderTexture texture : adapted) texture.close();
        measure.close();
        reduce16.close();
        reduce4.close();
        reduce1.close();
        adapt.close();
        valid = false;
    }
}
