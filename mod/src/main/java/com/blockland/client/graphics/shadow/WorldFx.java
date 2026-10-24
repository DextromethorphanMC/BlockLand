package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.ShadowInfo;
import com.blockland.client.graphics.WorldEffects;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * The screen-space effects of the world (rendering spec §1), made in the frame's after-level phase from the world depth,
 * before the hand and the viewmodel are drawn: ambient occlusion and volumetric light. Both run at half the internal
 * resolution, are smoothed with a depth-aware blur and brought back to the internal resolution with a depth-aware
 * upsample, so the post chain's decode pass can sample them per pixel without halos along silhouettes.
 */
final class WorldFx implements WorldEffects {
    private static final int FX_SIZE = new Std140SizeCalculator().putVec4().putVec4().putVec4().putVec4().get();
    private static final int VOLUMETRIC_SIZE = volumetricSize();
    /** Ambient occlusion radius in blocks. */
    private static final float AO_RADIUS = 1.25f;
    /** Volumetric light is marched at most this far (blocks). */
    private static final float VOLUMETRIC_RANGE = 128f;

    private static final RenderPipeline AO = screen("fx_ssao").build();
    private static final RenderPipeline BLUR = screen("fx_blur").withSampler("InSampler").build();
    private static final RenderPipeline UPSAMPLE = screen("fx_upsample").withSampler("InSampler").build();
    private static final RenderPipeline VOLUMETRIC = screen("fx_volumetric")
            .withSampler("BlShadowMap")
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
            .withUniform("VolumetricInfo", UniformType.UNIFORM_BUFFER)
            .build();

    private final RenderTexture aoHalf = new RenderTexture("BlockLand ambient occlusion (half)", RenderTexture.Format.R16F, FilterMode.LINEAR);
    private final RenderTexture aoScratch = new RenderTexture("BlockLand ambient occlusion (blur)", RenderTexture.Format.R16F, FilterMode.LINEAR);
    private final RenderTexture aoFull = new RenderTexture("BlockLand ambient occlusion", RenderTexture.Format.R16F, FilterMode.LINEAR);
    private final RenderTexture lightHalf = new RenderTexture("BlockLand volumetric light (half)", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    private final RenderTexture lightScratch = new RenderTexture("BlockLand volumetric light (blur)", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    private final RenderTexture lightFull = new RenderTexture("BlockLand volumetric light", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    @Nullable
    private Ubo aoInfo, lightInfo, blurH, blurV, upsample, volumetricInfo;
    @Nullable
    private GpuTextureView ao, light;
    private final Vector3f sun = new Vector3f();
    private final Matrix4f viewToWorld = new Matrix4f();
    private float millis;
    private boolean broken;

    private static RenderPipeline.Builder screen(String fragment) {
        return RenderPipeline.builder()
                .withLocation(BlockLand.id("pipeline/" + fragment))
                .withVertexShader(BlockLand.id("core/fx_screen"))
                .withFragmentShader(BlockLand.id("core/" + fragment))
                .withUniform("FxInfo", UniformType.UNIFORM_BUFFER)
                .withSampler("DepthSampler")
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS);
    }

    private static int volumetricSize() {
        Std140SizeCalculator size = new Std140SizeCalculator().putMat4f();
        for (int i = 0; i < 4; i++) size.putMat4f();
        for (int i = 0; i < 5; i++) size.putVec4();
        return size.get();
    }

    @Nullable
    @Override
    public GpuTextureView ambientOcclusion() {
        return ao;
    }

    @Nullable
    @Override
    public GpuTextureView volumetricLight() {
        return light;
    }

    String stats() {
        return String.format("ao %s, volumetric %s, %.2f ms CPU", ao != null ? aoHalf.width() + "x" + aoHalf.height() : "off",
                light != null ? lightHalf.width() + "x" + lightHalf.height() : "off", millis);
    }

    /** A new frame: last frame's effects are not this frame's. */
    void reset() {
        ao = null;
        light = null;
    }

    void render(FrameContext frame, GraphicsSettings settings, @Nullable ShadowInfo shadows) {
        ao = null;
        light = null;
        if (broken || frame.worldDepth == null) return;
        // ray tracing brings its own ambient occlusion
        boolean wantAo = settings.ambientOcclusion > 0.01f && !(settings.rayTracing && com.blockland.client.graphics.Graphics.status().rayTracingActive);
        boolean wantLight = settings.volumetricLight > 0.01f && shadows != null && shadows.count() > 0 && shadows.strength() > 0.01f;
        if (!wantAo && !wantLight) {
            release();
            return;
        }
        long start = System.nanoTime();
        try {
            int w = Math.max(1, frame.internalWidth), h = Math.max(1, frame.internalHeight);
            int hw = Math.max(1, (w + 1) / 2), hh = Math.max(1, (h + 1) / 2);
            ensureUbos();
            fx(upsample, frame, w, h, 0, 0, 0, 0, 0, 0, 0, hw / (float) w);
            if (wantAo) {
                ao = ambientOcclusion(frame, settings, w, h, hw, hh);
            } else {
                aoHalf.close();
                aoScratch.close();
                aoFull.close();
            }
            if (wantLight) {
                light = volumetric(frame, settings, shadows, w, h, hw, hh);
            } else {
                lightHalf.close();
                lightScratch.close();
                lightFull.close();
            }
        } catch (Throwable t) {
            broken = true;
            ao = null;
            light = null;
            BlockLand.LOGGER.error("[BlockLand] Ambient occlusion and volumetric light failed and are off for this session", t);
        }
        millis = millis * 0.9f + (System.nanoTime() - start) / 1e6f * 0.1f;
    }

    private GpuTextureView ambientOcclusion(FrameContext frame, GraphicsSettings settings, int w, int h, int hw, int hh) {
        fx(aoInfo, frame, hw, hh, AO_RADIUS, settings.ambientOcclusion, frame.frame % 64, 0.12f, 0, 0, 0, 0);
        aoHalf.ensure(hw, hh);
        aoScratch.ensure(hw, hh);
        aoFull.ensure(w, h);
        GpuTextureView depth = frame.worldDepth;
        Fullscreen.pass("BlockLand ambient occlusion", aoHalf.view(), AO).sampler("DepthSampler", depth)
                .uniform("FxInfo", aoInfo.buffer().slice()).draw();
        blur(frame, aoHalf, aoScratch, depth, 30f);
        Fullscreen.pass("BlockLand ambient occlusion: upsample", aoFull.view(), UPSAMPLE).sampler("DepthSampler", depth)
                .sampler("InSampler", aoHalf.view()).uniform("FxInfo", upsample.buffer().slice()).draw();
        return aoFull.view();
    }

    private GpuTextureView volumetric(FrameContext frame, GraphicsSettings settings, ShadowInfo shadows, int w, int h, int hw, int hh) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        SunPath.light(level, frame.partialTick, sun);
        boolean day = SunPath.isSun(level, frame.partialTick);
        float elevation = Math.max(0, sun.y);
        float rain = level.getRainLevel(frame.partialTick);
        // thicker haze at dawn and dusk and in rain; dense, blue and short-sighted under water
        float density = frame.submerged ? 0.06f : 0.006f * (1 + 2.5f * Math.max(0, 1 - elevation / 0.3f)) * (1 + 3 * rain);
        float range = Math.min(VOLUMETRIC_RANGE, shadows.splits()[shadows.count() - 1]);
        int steps = 12 + Math.round(8 * settings.volumetricLight);
        float intensity = settings.volumetricLight * 1.6f;
        float anisotropy = frame.submerged ? 0.3f : 0.62f;
        // the light's colour when the scene uniforms carry none (world lighting off): warm low, white high, cold moon
        float warm = Mth.clamp(elevation / 0.35f, 0, 1);
        float r = day ? 1f : 0.14f, g = day ? Mth.lerp(warm, 0.55f, 0.95f) : 0.18f, bl = day ? Mth.lerp(warm, 0.3f, 0.88f) : 0.26f;
        float frameIndex = frame.frame % 64;
        viewToWorld.set(frame.viewRotation).transpose();
        float underwater = frame.submerged ? 1 : 0;
        volumetricInfo.write(b -> {
            b.putMat4f(viewToWorld);
            for (int c = 0; c < 4; c++) b.putMat4f(shadows.cascades()[c]);
            float[] s = shadows.splits();
            b.putVec4(s[0], s[1], s[2], s[3]);
            b.putVec4(sun.x, sun.y, sun.z, shadows.strength());
            b.putVec4(r, g, bl, frameIndex);
            b.putVec4(density, range, steps, (float) frame.camY);
            b.putVec4(underwater, shadows.count(), intensity, anisotropy);
        });
        fx(lightInfo, frame, hw, hh, 0, 0, frameIndex, 0, 0, 0, 0, 0);
        lightHalf.ensure(hw, hh);
        lightScratch.ensure(hw, hh);
        lightFull.ensure(w, h);
        GpuTextureView depth = frame.worldDepth;
        Fullscreen.pass("BlockLand volumetric light", lightHalf.view(), VOLUMETRIC).sampler("DepthSampler", depth)
                .sampler("BlShadowMap", shadows.atlas())
                .uniform("FxInfo", lightInfo.buffer().slice())
                .uniform("BlockLandScene", SceneLighting.uniforms())
                .uniform("VolumetricInfo", volumetricInfo.buffer().slice()).draw();
        blur(frame, lightHalf, lightScratch, depth, 12f);
        Fullscreen.pass("BlockLand volumetric light: upsample", lightFull.view(), UPSAMPLE).sampler("DepthSampler", depth)
                .sampler("InSampler", lightHalf.view()).uniform("FxInfo", upsample.buffer().slice()).draw();
        return lightFull.view();
    }

    /** Depth-aware blur of a half-resolution effect, horizontally into the scratch and back vertically. */
    private void blur(FrameContext frame, RenderTexture target, RenderTexture scratch, GpuTextureView depth, float sharpness) {
        int hw = target.width(), hh = target.height();
        float ratio = hw / (float) Math.max(1, frame.internalWidth);
        fx(blurH, frame, hw, hh, 0, 0, 0, 0, 1f / hw, 0, sharpness, ratio);
        fx(blurV, frame, hw, hh, 0, 0, 0, 0, 0, 1f / hh, sharpness, ratio);
        Fullscreen.pass("BlockLand world effect: blur H", scratch.view(), BLUR).sampler("DepthSampler", depth)
                .sampler("InSampler", target.view()).uniform("FxInfo", blurH.buffer().slice()).draw();
        Fullscreen.pass("BlockLand world effect: blur V", target.view(), BLUR).sampler("DepthSampler", depth)
                .sampler("InSampler", scratch.view()).uniform("FxInfo", blurV.buffer().slice()).draw();
    }

    /**
     * Writes an {@code FxInfo} block (world_fx.glsl): the projection, the world depth's and this pass's sizes, then the
     * {@code FxAo} and {@code FxBlur} numbers.
     */
    private static void fx(Ubo ubo, FrameContext frame, int outW, int outH, float a0, float a1, float a2, float a3,
                           float b0, float b1, float b2, float b3) {
        Matrix4f p = frame.projection;
        int w = Math.max(1, frame.internalWidth), h = Math.max(1, frame.internalHeight);
        ubo.write(b -> b.putVec4(p.m00(), p.m11(), p.m22(), p.m32()).putVec4(w, h, outW, outH)
                .putVec4(a0, a1, a2, a3).putVec4(b0, b1, b2, b3));
    }

    private void ensureUbos() {
        if (aoInfo != null) return;
        aoInfo = new Ubo("ambient occlusion", FX_SIZE);
        lightInfo = new Ubo("volumetric light pass", FX_SIZE);
        blurH = new Ubo("world effects blur H", FX_SIZE);
        blurV = new Ubo("world effects blur V", FX_SIZE);
        upsample = new Ubo("world effects upsample", FX_SIZE);
        volumetricInfo = new Ubo("volumetric light", VOLUMETRIC_SIZE);
    }

    /** Frees the targets (both effects switched off). */
    void release() {
        aoHalf.close();
        aoScratch.close();
        aoFull.close();
        lightHalf.close();
        lightScratch.close();
        lightFull.close();
        ao = null;
        light = null;
    }
}
