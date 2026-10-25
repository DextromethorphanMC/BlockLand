package com.blockland.client.graphics.post;

import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;

/**
 * Bloom as a mip chain ({@code gfx_bloom_down/up.fsh}): the frame is halved up to six times — the first step keeps only
 * the light above a soft threshold — and walked back up, each level adding the blurred glow of all smaller ones. The
 * result (half the output size) is added in the final pass.
 */
final class Bloom implements AutoCloseable {
    private static final RenderPipeline DOWN = Fullscreen.pipeline("gfx_bloom_down").withSampler("InSampler").build();
    private static final RenderPipeline UP = Fullscreen.pipeline("gfx_bloom_up").withSampler("LowSampler").withSampler("HighSampler").build();
    private static final int LEVELS = 6;
    /** Linear luminance from which light blooms (Minecraft's own whites stay below), and the soft knee around it. */
    private static final float THRESHOLD = 1.0f, KNEE = 0.5f;
    private static final Matrix4f NONE = new Matrix4f();

    private final RenderTexture[] down = new RenderTexture[LEVELS];
    private final RenderTexture[] up = new RenderTexture[LEVELS - 1];
    private final Ubo[] downUbo = new Ubo[LEVELS];
    private final Ubo[] upUbo = new Ubo[LEVELS - 1];

    Bloom() {
        for (int i = 0; i < LEVELS; i++) {
            down[i] = new RenderTexture("BlockLand bloom down " + i, RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
            downUbo[i] = Ubo.pass("bloom down " + i).skipUnchanged();
        }
        for (int i = 0; i < LEVELS - 1; i++) {
            up[i] = new RenderTexture("BlockLand bloom up " + i, RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
            upUbo[i] = Ubo.pass("bloom up " + i).skipUnchanged();
        }
    }

    /** The bloom of {@code color} (output size {@code width}×{@code height}) at half that size. */
    GpuTextureView render(GpuTextureView color, int width, int height) {
        int levels = 0;
        int w = width, h = height;
        GpuTextureView source = color;
        int sourceW = width, sourceH = height;
        for (int i = 0; i < LEVELS; i++) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            if (i > 0 && Math.min(w, h) < 4) break;
            down[i].ensure(w, h);
            downUbo[i].pass(NONE, 1f / sourceW, 1f / sourceH, i == 0 ? 1 : 0, THRESHOLD, KNEE);
            Fullscreen.pass("BlockLand bloom down " + i, down[i].view(), DOWN).sampler("InSampler", source).uniform(downUbo[i]).draw();
            source = down[i].view();
            sourceW = w;
            sourceH = h;
            levels++;
        }
        if (levels == 1) return down[0].view();
        GpuTextureView low = down[levels - 1].view();
        int lowW = down[levels - 1].width(), lowH = down[levels - 1].height();
        for (int i = levels - 2; i >= 0; i--) {
            up[i].ensure(down[i].width(), down[i].height());
            upUbo[i].pass(NONE, 1f / lowW, 1f / lowH);
            Fullscreen.pass("BlockLand bloom up " + i, up[i].view(), UP)
                    .sampler("LowSampler", low).sampler("HighSampler", down[i].view()).uniform(upUbo[i]).draw();
            low = up[i].view();
            lowW = up[i].width();
            lowH = up[i].height();
        }
        return up[0].view();
    }

    @Override
    public void close() {
        for (RenderTexture texture : down) texture.close();
        for (RenderTexture texture : up) texture.close();
        for (Ubo ubo : downUbo) ubo.close();
        for (Ubo ubo : upUbo) ubo.close();
    }
}
