package com.blockland.client.graphics.post;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.WorldEffects;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.graphics.shadow.SunPath;
import com.blockland.client.graphics.upscale.Upscaler;
import com.blockland.client.perf.FrameTimings;
import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/**
 * The HDR post chain (rendering spec §1, §3, §4), run once per frame between the hand phase and the HUD:
 * <ol>
 *   <li>decode — the scene's display colours to linear HDR, emission (main-target alpha) boosted, the world's ambient
 *   occlusion (or the ray traced light and occlusion) and volumetric light folded in (internal resolution);</li>
 *   <li>the upscaler — TAA / TAAU to the output resolution (or nothing at native resolution);</li>
 *   <li>relighting — DLSS 5 neural rendering on the upscaled picture, when it is on ({@link Relight});</li>
 *   <li>motion blur — a real shutter: the camera's and the entities' motion, the weapon's own ({@link MotionBlur});</li>
 *   <li>water on the lens — drops after the camera leaves water, a wavering under it ({@link LensWater});</li>
 *   <li>bloom and auto exposure — measured on that picture;</li>
 *   <li>final — sharpening, chromatic aberration, bloom, exposure, white balance, tone mapping, grading, vignette, grain;
 *   straight into the window's target, or through FXAA.</li>
 * </ol>
 * Every effect set to 0 skips its passes.
 */
public final class PostChain implements AutoCloseable {
    private static final RenderPipeline DECODE = Fullscreen.pipeline("gfx_decode")
            .withSampler("SceneSampler").withSampler("WorldDepthSampler").withSampler("HandDepthSampler")
            .withSampler("AoSampler").withSampler("VolumetricSampler").withSampler("RayTracedSampler").withSampler("CloudSampler")
            .withSampler("FarDepthSampler")
            .build();
    private static final RenderPipeline FINAL = Fullscreen.pipeline("gfx_final")
            .withSampler("ColorSampler").withSampler("BloomSampler").withSampler("ExposureSampler")
            .build();
    /**
     * The final pass leaving the window target's alpha at 0 ({@link Optimization#POST_DEAD_WORK}): what Frame Generation's
     * HUD capture would otherwise clear in a pass of its own right after; the colour is computed exactly as by {@link #FINAL}.
     */
    private static final RenderPipeline FINAL_HUD_COVERAGE = Fullscreen.pipeline("gfx_final")
            .withLocation(BlockLand.id("pipeline/gfx_final_hud_coverage"))
            .withShaderDefine("HUD_COVERAGE")
            .withSampler("ColorSampler").withSampler("BloomSampler").withSampler("ExposureSampler")
            .build();
    private static final RenderPipeline FXAA = Fullscreen.pipeline("gfx_fxaa").withSampler("InSampler").build();
    private static final RenderPipeline COPY = Fullscreen.pipeline("gfx_copy").withSampler("InSampler").build();
    private static final Matrix4f NONE = new Matrix4f();
    /** HDR multiplier added at full emission (times the emission setting). */
    private static final float EMISSION_BOOST = 2.5f;
    /** Bloom added at the bloom setting's full strength (the chain sums six levels). */
    private static final float BLOOM_STRENGTH = 0.18f;
    /**
     * Auto exposure: how much of the measured difference to correct, its range in EV and the mean (log) luminance it aims
     * at — that of an ordinary Minecraft day, so daylight stays exactly as vanilla draws it.
     */
    private static final float EXPOSURE_STRENGTH = 0.4f, EXPOSURE_MIN = -0.5f, EXPOSURE_MAX = 0.4f, EXPOSURE_KEY = 0.15f;
    /** Vanilla's fog of this frame's level render (the sky's physical model meets it at the horizon): colour (display). */
    private static final float[] fogColor = new float[3];
    /** Its sky fog end (blocks) and whether it was the open air's (no fluid, no dimension or boss fog). */
    private static float skyEnd;
    private static boolean openAirFog;

    static {
        // read last, after every other listener had its say: the colour and distances the level was drawn with
        ViewportEvent.RenderFog.BUS.addListener(Priority.LOWEST, PostChain::onFog);
    }

    private final RenderTexture hdr = new RenderTexture("BlockLand HDR", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    private final RenderTexture ldr = new RenderTexture("BlockLand LDR", RenderTexture.Format.RGBA8, FilterMode.LINEAR);
    private final RenderTexture black = new RenderTexture("BlockLand black", RenderTexture.Format.RGBA16F, FilterMode.NEAREST);
    private final Bloom bloom = new Bloom();
    private final Exposure exposure = new Exposure();
    private final MotionBlur motionBlur = new MotionBlur();
    private final FarAtmosphere atmosphere = new FarAtmosphere();
    private final LensWater lensWater = new LensWater();
    private final Ubo decodeUbo = Ubo.pass("decode").skipUnchanged();
    private final Ubo finalUbo = Ubo.pass("final").skipUnchanged();
    private final Ubo fxaaUbo = Ubo.pass("FXAA").skipUnchanged();
    private int frameSeed;
    /** The target whose alpha this frame's final pass left at 0 ({@link #FINAL_HUD_COVERAGE}), until taken. */
    @Nullable
    private static GpuTextureView coverageCleared;

    /**
     * Everything one run needs.
     *
     * @param scene        the scene target's colour (internal resolution; alpha = emission)
     * @param handDepth    the scene's depth after the hand phase (&lt; 1 where the hand / viewmodel is)
     * @param worldDepth   the world's depth from after the level, or null (then the hand depth stands in)
     * @param reprojection current unjittered NDC → previous clip
     * @param historyValid the camera did not cut since the last frame
     * @param emissionEncoded the lighting pack wrote emission into the opaque pixels' alpha this frame (else the alpha
     *                        is whatever textures had and means nothing)
     * @param output       the window's colour target
     * @param seconds      time since the last frame
     */
    public record Inputs(FrameContext frame, GpuTextureView scene, GpuTextureView handDepth, @Nullable GpuTextureView worldDepth,
                         Matrix4f reprojection, boolean historyValid, Upscaler upscaler, boolean fxaa, boolean emissionEncoded,
                         GraphicsSettings settings, WorldEffects effects, GpuTextureView output, float seconds,
                         @Nullable Relight relight) {
    }

    /** A stage on the upscaled linear-HDR picture before motion blur and bloom (DLSS 5 neural rendering). */
    public interface Relight {
        /** The relit picture, or {@code color} itself when nothing was done ({@code sceneDepth}: with the hand in it). */
        GpuTextureView apply(Upscaler.Frame frame, GpuTextureView color, GpuTextureView sceneDepth);
    }

    public void run(Inputs in) {
        FrameContext ctx = in.frame();
        GraphicsSettings s = in.settings();
        int outW = ctx.outputWidth, outH = ctx.outputHeight;
        GpuTextureView worldDepth = in.worldDepth() != null ? in.worldDepth() : in.handDepth();
        GpuTextureView none = black();
        coverageCleared = null;

        // 1. decode
        try (FrameTimings.Span ignored = FrameTimings.span("bl/post/decode")) {
            hdr.ensure(ctx.internalWidth, ctx.internalHeight);
            GpuTextureView ao = s.ambientOcclusion > 0.001f ? in.effects().ambientOcclusion() : null;
            GpuTextureView volumetric = in.effects().volumetricLight();
            GpuTextureView traced = in.effects().rayTraced();
            GpuTextureView clouds = in.effects().clouds();
            // the far terrain (rendering spec §22) lies where the scene's depth is 1, like the sky: its own depth tells them apart
            GpuTextureView far = ctx.farDepth;
            float[] params = new float[24];
            params[0] = EMISSION_BOOST * s.emission;
            params[1] = s.ambientOcclusion;
            params[2] = ao != null ? 1 : 0;
            params[3] = volumetric != null ? 1 : 0;
            params[4] = in.emissionEncoded() ? 1 : 0;
            params[5] = traced != null ? 1 : 0;
            params[6] = clouds != null ? 1 : 0;
            params[7] = far != null ? 1 : 0;
            boolean sky = physicalSky(ctx, params);
            decodeUbo.pass(sky ? new Matrix4f(ctx.projection).mul(ctx.viewRotation).invert() : NONE, params);
            Fullscreen.pass("BlockLand post: decode", hdr.view(), DECODE)
                    .sampler("SceneSampler", in.scene())
                    .sampler("WorldDepthSampler", worldDepth)
                    .sampler("HandDepthSampler", in.handDepth())
                    .sampler("AoSampler", ao != null ? ao : none)
                    .sampler("VolumetricSampler", volumetric != null ? volumetric : none)
                    .sampler("RayTracedSampler", traced != null ? traced : none)
                    .sampler("CloudSampler", clouds != null ? clouds : none)
                    .sampler("FarDepthSampler", far != null ? far : worldDepth)
                    .uniform(decodeUbo)
                    .draw();
        }

        // 1b. the distant atmosphere (rendering spec §24): aerial perspective, far detail blurring out, heat shimmer
        GpuTextureView decoded = hdr.view();
        try (FrameTimings.Span ignored = FrameTimings.span("bl/post/far_atmosphere")) {
            decoded = atmosphere.apply(ctx, s, decoded, worldDepth, in.handDepth());
        }

        // 2. anti-aliasing / upscaling (world pixels on moving vehicles carry their own motion: ObjectMotionBlock)
        ObjectMotionBlock.update(ctx);
        Upscaler.Frame resolve = new Upscaler.Frame(ctx, decoded, in.worldDepth(), in.reprojection(), in.historyValid());
        GpuTextureView color;
        try (FrameTimings.Span ignored = FrameTimings.span("bl/post/upscale")) {
            color = in.upscaler().resolve(resolve);
        }
        if (in.relight() != null) {
            try (FrameTimings.Span ignored = FrameTimings.span("bl/post/relight")) {
                color = in.relight().apply(resolve, color, in.handDepth());
            }
        }

        // 3. motion blur: the shutter's exposure, the same at any frame rate; the world (camera and entities) and the
        // first-person weapon and arms each by their own shutter
        if (in.worldDepth() != null && in.historyValid() && in.seconds() > 0) {
            try (FrameTimings.Span ignored = FrameTimings.span("bl/post/motion_blur")) {
                color = motionBlur.apply(ctx, s, color, in.worldDepth(), in.seconds(), none);
            }
        } else {
            motionBlur.invalidate();
        }

        // 3b. water on the lens (rendering spec §27): drops after leaving water, a wavering under it
        try (FrameTimings.Span ignored = FrameTimings.span("bl/post/lens_water")) {
            color = lensWater.apply(ctx, s, color);
        }

        // 4. bloom and exposure, measured on that picture
        GpuTextureView bloomView = none;
        if (s.bloom > 0.001f) {
            try (FrameTimings.Span ignored = FrameTimings.span("bl/post/bloom")) {
                bloomView = bloom.render(color, outW, outH);
            }
        }
        GpuTextureView exposureView = none;
        if (s.autoExposure) {
            try (FrameTimings.Span ignored = FrameTimings.span("bl/post/exposure")) {
                exposureView = exposure.render(color, outW, outH, in.seconds());
            }
        } else {
            exposure.invalidate();
        }

        // 5. final
        try (FrameTimings.Span ignored = FrameTimings.span("bl/post/final")) {
            frameSeed = (frameSeed + 1) & 1023;
            finalUbo.pass(NONE,
                    1f / outW, 1f / outH, ("dlss".equals(in.upscaler().id()) ? s.dlssSharpness : s.sharpness), s.chromaticAberration,
                    BLOOM_STRENGTH * s.bloom, s.autoExposure ? 1 : 0, (float) Math.pow(2, s.exposure), s.tonemapMode().ordinal(),
                    s.saturation, s.contrast, s.temperature, s.vignette,
                    s.filmGrain, frameSeed, in.fxaa() ? 1 : 0, outW / (float) Math.max(1, outH),
                    EXPOSURE_STRENGTH, EXPOSURE_MIN, EXPOSURE_MAX, EXPOSURE_KEY);
            GpuTextureView finalTarget = in.output();
            if (in.fxaa()) {
                ldr.ensure(outW, outH);
                finalTarget = ldr.view();
            }
            // Frame Generation captures the HUD's coverage in the window target's alpha, cleared right after this pass;
            // without FXAA (whose luma rides in the alpha) the final pass can leave it at 0 itself
            boolean coverage = !in.fxaa() && ctx.frameGenerating && Optimization.POST_DEAD_WORK.on();
            Fullscreen.pass("BlockLand post: final", finalTarget, coverage ? FINAL_HUD_COVERAGE : FINAL)
                    .sampler("ColorSampler", color)
                    .sampler("BloomSampler", bloomView)
                    .sampler("ExposureSampler", exposureView)
                    .uniform(finalUbo)
                    .draw();
            if (coverage) coverageCleared = in.output();
            if (in.fxaa()) {
                fxaaUbo.pass(NONE, 1f / outW, 1f / outH);
                Fullscreen.pass("BlockLand post: FXAA", in.output(), FXAA)
                        .sampler("InSampler", ldr.view()).uniform(fxaaUbo).draw();
            }
        }
    }

    /**
     * Writes the decode pass's physical sky parameters (Params[2..5] from index 8, see gfx_decode.fsh); false (and zeros)
     * when the sky is left as vanilla drew it: the setting is off, the sky is not the overworld's open-air sky (no sky
     * light, the Nether's or the End's, under water, in lava or powder snow, dimension or boss fog) or a blinding effect
     * hides it (vanilla draws no sky then).
     */
    private static boolean physicalSky(FrameContext ctx, float[] params) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!Graphics.settings().physicalSky || level == null || ctx.submerged || !openAirFog || skyEnd <= 0) return false;
        if (!level.dimensionType().hasSkyLight() || level.effects().skyType() != DimensionSpecialEffects.SkyType.OVERWORLD) return false;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) return false;
        float partial = ctx.partialTick;
        // vanilla's sky colour and sunrise fan exactly as LevelRenderer passed them to the sky renderer
        int sky = level.getSkyColor(camera.getPosition(), partial);
        float timeOfDay = level.getTimeOfDay(partial);
        float fanR = 0, fanG = 0, fanB = 0, fanAlpha = 0;
        if (level.effects().isSunriseOrSunset(timeOfDay)) {
            int fan = level.effects().getSunriseOrSunsetColor(timeOfDay);
            fanR = ARGB.redFloat(fan);
            fanG = ARGB.greenFloat(fan);
            fanB = ARGB.blueFloat(fan);
            fanAlpha = ARGB.alphaFloat(fan) * (Mth.sin(level.getSunAngle(partial)) < 0 ? -1 : 1);
        }
        Vector3f sun = SunPath.sun(level, partial, new Vector3f());
        float overcast = Mth.clamp(0.7f * level.getRainLevel(partial) + 0.3f * level.getThunderLevel(partial), 0, 1);
        float[] values = {
                ARGB.redFloat(sky), ARGB.greenFloat(sky), ARGB.blueFloat(sky), skyEnd,
                fogColor[0], fogColor[1], fogColor[2], level.getMoonBrightness(),
                fanR, fanG, fanB, fanAlpha,
                sun.x, sun.y, sun.z, overcast};
        System.arraycopy(values, 0, params, 8, values.length);
        return true;
    }

    private static boolean onFog(ViewportEvent.RenderFog event) {
        openAirFog = event.getType() == FogType.ATMOSPHERIC;
        Vector4f color = event.getColor();
        fogColor[0] = color.x();
        fogColor[1] = color.y();
        fogColor[2] = color.z();
        skyEnd = event.getData().skyEnd;
        return false;
    }

    /**
     * Whether this frame's final pass left {@code target}'s alpha at 0 (Frame Generation's HUD capture with
     * {@link Optimization#POST_DEAD_WORK}, see {@link #FINAL_HUD_COVERAGE}); answered once per run.
     */
    public static boolean takeCoverageCleared(GpuTextureView target) {
        boolean cleared = coverageCleared != null && coverageCleared == target;
        coverageCleared = null;
        return cleared;
    }

    /**
     * The last run's decoded linear-HDR colour at the internal resolution (alpha 1 on hand / viewmodel pixels), or null
     * before the first run; Frame Generation draws its depth and motion inputs from it at the end of the frame.
     */
    @Nullable
    public GpuTextureView decoded() {
        return hdr.exists() ? hdr.view() : null;
    }

    /** The scene stretched onto the output as it is (when the chain cannot run this frame). */
    public static void copy(GpuTextureView from, GpuTextureView to) {
        Fullscreen.pass("BlockLand post: copy", to, COPY).sampler("InSampler", from).draw();
    }

    /** Forgets the time-based state (exposure); the upscaler's history is the caller's. */
    public void invalidate() {
        exposure.invalidate();
        lensWater.invalidate();
    }

    private GpuTextureView black() {
        if (black.ensure(1, 1)) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(black.texture(), 0);
        return black.view();
    }

    @Override
    public void close() {
        hdr.close();
        motionBlur.close();
        atmosphere.close();
        lensWater.close();
        ldr.close();
        black.close();
        bloom.close();
        exposure.close();
        decodeUbo.close();
        finalUbo.close();
        fxaaUbo.close();
    }
}
