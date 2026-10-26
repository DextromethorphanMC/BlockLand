package com.blockland.client.graphics.post;

import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.render.RenderClock;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Water on the lens (rendering spec §27; after Chocapic13's Bliss shaders' water on the camera): one full-screen pass on
 * the upscaled linear-HDR picture, after motion blur and before bloom. When the camera leaves water a film drains off
 * the screen and drops stay on the glass — sliding, leaving trails, drying — until it is clear after a few seconds;
 * while the camera is under water the picture wavers gently. Setting 镜头水滴 ({@link GraphicsSettings#lensWater}); the
 * picture is handed back as it is when there is nothing to do.
 */
final class LensWater implements AutoCloseable {
    private static final RenderPipeline PASS = Fullscreen.pipeline("gfx_lens_water").withSampler("ColorSampler").build();
    private static final Matrix4f NONE = new Matrix4f();
    /** The last drop is gone by then (seconds after leaving the water; the shader's longest life). */
    private static final float CLEAR_SECONDS = 5.6f;

    private final RenderTexture out = new RenderTexture("BlockLand lens water", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    @Nullable
    private Ubo ubo;
    private boolean wasUnder;
    /** When the camera last came out of the water (render clock), or -1. */
    private long leftAt = -1;
    /** Every exit lays different drops (counted, not random: the benchmark's frames repeat exactly). */
    private int exits;

    /** The picture with water on the lens, or {@code color} itself when there is none. */
    GpuTextureView apply(FrameContext frame, GraphicsSettings s, GpuTextureView color) {
        Minecraft mc = Minecraft.getInstance();
        boolean under = s.lensWater && mc.level != null && mc.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER;
        long now = RenderClock.nanos();
        if (wasUnder && !under && s.lensWater) {
            leftAt = now;
            exits++;
        }
        wasUnder = under;
        float age = leftAt < 0 ? Float.MAX_VALUE : (now - leftAt) / 1e9f;
        if (!s.lensWater || (!under && !(age < CLEAR_SECONDS))) return color;
        if (ubo == null) ubo = Ubo.pass("lens water");
        int w = frame.outputWidth, h = frame.outputHeight;
        float time = (float) (now / 1e9 % 3600.0);
        float seed = (exits * 0.6180339f) % 1f;
        ubo.pass(NONE, under ? 0 : age, under ? 1 : 0, time, w / (float) Math.max(1, h),
                1f / Math.max(1, w), 1f / Math.max(1, h), seed, 0);
        out.ensure(w, h);
        Fullscreen.pass("BlockLand post: lens water", out.view(), PASS)
                .sampler("ColorSampler", color)
                .uniform(ubo)
                .draw();
        return out.view();
    }

    /** Forgets a drying lens (a camera cut, a world change). */
    void invalidate() {
        leftAt = -1;
        wasUnder = false;
    }

    @Override
    public void close() {
        out.close();
        if (ubo != null) ubo.close();
        ubo = null;
    }
}
