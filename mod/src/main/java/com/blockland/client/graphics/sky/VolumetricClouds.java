package com.blockland.client.graphics.sky;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.GraphicsStatus;
import com.blockland.client.graphics.WorldEffects;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.frame.FrameStage;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;

/**
 * Volumetric clouds (rendering spec §14): a cloud layer from the dimension's cloud height up, marched per pixel at a share
 * of the internal resolution in the frame's after-level phase (stopped by the world's depth), accumulated over frames,
 * and folded into the picture by the post chain's decode pass (transmittance, then the light they scatter). Their noise
 * (a Perlin-Worley shape volume, a Worley detail volume, a weather map) is made once on the GPU. Lit by the scene
 * uniforms' sun or moon and sky, so they follow the time of day, the weather and the player's light colours.
 * <p>
 * The layer's numbers go into the scene uniforms at {@code cameraReady} ({@link FrameContext#clouds}): every lit surface
 * and the volumetric fog take the clouds' shadow from the same coverage. While they run, vanilla's clouds are left out by
 * the lighting pack's cloud shader ({@code BlBlockLight.a}, decided at frame start through
 * {@link GraphicsStatus#cloudsActive}); without world lighting the pack is not loaded and vanilla's clouds stay. Any
 * failure switches them off for the session (the status says so).
 */
public final class VolumetricClouds implements FrameStage, WorldEffects {
    /** Layer thickness in blocks above the dimension's cloud height (towering cumulus reach the top). */
    private static final float THICKNESS = 240f;
    /** Blocks per second the clouds drift (along +x, a little +z). */
    private static final float WIND = 6f;
    /** Where the noise repeats (scene.glsl, gfx_clouds.fsh): positions wrap there without a seam. */
    private static final double PERIOD = 4096;
    /** Weight of this frame against the accumulated clouds. */
    private static final float BLEND = 0.12f;
    /** How much sunlight the thickest clouds keep off the ground (the rest arrives scattered). */
    private static final float SHADOW = 0.9f;

    // the noise uses scene.glsl's cloud hash: the block comes along with it and is bound like everywhere else
    private static final RenderPipeline NOISE = Fullscreen.pipeline("gfx_cloud_noise")
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER).build();
    private static final RenderPipeline MARCH = Fullscreen.pipeline("gfx_clouds").withSampler("DepthSampler")
            .withSampler("ShapeNoise").withSampler("DetailNoise").withSampler("WeatherMap")
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER).build();
    private static final RenderPipeline TEMPORAL = Fullscreen.pipeline("gfx_clouds_temporal").withSampler("CurrentSampler")
            .withSampler("HistorySampler").build();

    private final RenderTexture shape = new RenderTexture("BlockLand cloud shape noise", RenderTexture.Format.RGBA8, FilterMode.LINEAR);
    private final RenderTexture detail = new RenderTexture("BlockLand cloud detail noise", RenderTexture.Format.RGBA8, FilterMode.LINEAR);
    private final RenderTexture weather = new RenderTexture("BlockLand cloud weather map", RenderTexture.Format.RGBA8, FilterMode.LINEAR);
    private final RenderTexture marched = new RenderTexture("BlockLand clouds (this frame)", RenderTexture.Format.RGBA16F, FilterMode.NEAREST);
    private final RenderTexture[] history = {
            new RenderTexture("BlockLand clouds A", RenderTexture.Format.RGBA16F, FilterMode.LINEAR),
            new RenderTexture("BlockLand clouds B", RenderTexture.Format.RGBA16F, FilterMode.LINEAR)};
    /** Made on the render thread at the first march (the stage is built during mod construction). */
    @Nullable
    private Ubo march, temporal, noise;
    @Nullable
    private GpuTextureView output;
    private int current;
    private boolean historyValid, noiseReady, broken, active;
    private float millis;

    private VolumetricClouds() {
    }

    public static void init() {
        VolumetricClouds clouds = new VolumetricClouds();
        Graphics.addStage(clouds);
        Graphics.setClouds(clouds);
    }

    @Nullable
    @Override
    public GpuTextureView clouds() {
        return output;
    }

    /** Decided before the scene uniforms are written, so vanilla's clouds are hidden in exactly the frames these draw. */
    @Override
    public void restart() {
        historyValid = false;
    }

    @Override
    public void beginFrame(FrameContext frame) {
        output = null;
        Arrays.fill(frame.clouds, 0);
        GraphicsSettings settings = Graphics.settings();
        GraphicsStatus status = Graphics.status();
        String reason = reason(status);
        active = settings.volumetricClouds && reason == null;
        status.cloudsActive = active;
        status.cloudsReason = settings.volumetricClouds ? reason : null;
        if (!active) release();
    }

    @Nullable
    private String reason(GraphicsStatus status) {
        if (broken) return "blockland.graphics.status.clouds.failed";
        if (!status.worldLightingActive) return "blockland.graphics.status.clouds.no_lighting";
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.dimensionType().cloudHeight().isEmpty()) return "blockland.graphics.status.clouds.no_sky";
        return null;
    }

    /** The layer this frame, for the scene uniforms (registered before the light module, so it is there in time). */
    @Override
    public void cameraReady(FrameContext frame) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!active || level == null) return;
        Optional<Integer> height = level.dimensionType().cloudHeight();
        if (height.isEmpty()) return;
        GraphicsSettings settings = Graphics.settings();
        double seconds = (level.getGameTime() + frame.partialTick) / 20.0;
        float rain = level.getRainLevel(frame.partialTick), thunder = level.getThunderLevel(frame.partialTick);
        float bottom = height.get() + 0.33f;
        float[] c = frame.clouds;
        c[0] = (float) wrap(frame.camX + seconds * WIND);
        c[1] = (float) wrap(frame.camZ + seconds * WIND * 0.35);
        c[2] = (float) (bottom - frame.camY);
        c[3] = (float) (bottom + THICKNESS - frame.camY);
        // rain and thunder close the sky and thicken it
        c[4] = Math.min(1, settings.cloudCoverage + 0.4f * rain + 0.25f * thunder);
        c[5] = 1 + 1.2f * rain + 0.8f * thunder;
        c[6] = SHADOW;
        // the detail noise drifts on its own: wrapped where its offset (2 blocks/s) has gone once round its tile
        c[7] = (float) (seconds % 64.0);
    }

    @Override
    public void afterLevel(FrameContext frame) {
        if (!active || frame.worldDepth == null || frame.clouds[6] <= 0) return;
        long start = System.nanoTime();
        try {
            render(frame);
        } catch (Throwable t) {
            broken = true;
            active = false;
            output = null;
            Graphics.status().cloudsActive = false;
            BlockLand.LOGGER.error("[BlockLand] Volumetric clouds failed and are off for this session", t);
        }
        millis = millis * 0.9f + (System.nanoTime() - start) / 1e6f * 0.1f;
        Graphics.status().cloudMillis = millis;
    }

    private void render(FrameContext frame) {
        if (march == null) {
            march = Ubo.pass("clouds");
            temporal = Ubo.pass("clouds temporal");
            noise = Ubo.pass("cloud noise");
        }
        if (!noiseReady) makeNoise();

        GraphicsSettings.CloudQuality quality = Graphics.settings().cloudQualityMode();
        int w = Math.max(1, Math.round(frame.internalWidth * quality.scale));
        int h = Math.max(1, Math.round(frame.internalHeight * quality.scale));
        boolean resized = marched.ensure(w, h);
        for (RenderTexture t : history) resized |= t.ensure(w, h);
        if (resized) historyValid = false;

        Matrix4f toWorld = new Matrix4f(frame.projection).mul(frame.viewRotation).invert();
        march.pass(toWorld, frame.internalWidth, frame.internalHeight, quality.steps, quality.lightSteps, frame.frame % 1024);
        Fullscreen.pass("BlockLand clouds: march", marched.view(), MARCH).sampler("DepthSampler", frame.worldDepth)
                .sampler("ShapeNoise", shape.view()).sampler("DetailNoise", detail.view()).sampler("WeatherMap", weather.view())
                .uniform("BlockLandScene", SceneLighting.uniforms()).uniform(march).draw();

        int previous = current;
        current ^= 1;
        // rotation only: the clouds are far enough that the camera's move hardly shifts them
        Matrix4f reprojection = new Matrix4f(frame.prevProjection).mul(frame.prevViewRotation)
                .mul(new Matrix4f(frame.viewRotation).invert()).mul(new Matrix4f(frame.projection).invert());
        temporal.pass(reprojection, historyValid ? 1 : 0, BLEND);
        Fullscreen.pass("BlockLand clouds: accumulate", history[current].view(), TEMPORAL).sampler("CurrentSampler", marched.view())
                .sampler("HistorySampler", history[previous].view()).uniform(temporal).draw();
        historyValid = true;
        output = history[current].view();
    }

    /** The shape volume (128³ as 16 × 8 slices), the detail volume (32³ as 8 × 4) and the weather map, once. */
    private void makeNoise() {
        shape.ensure(128 * 16, 128 * 8);
        detail.ensure(32 * 8, 32 * 4);
        weather.ensure(1024, 1024);
        noise.pass(new Matrix4f(), 128, 16, 0);
        Fullscreen.pass("BlockLand clouds: shape noise", shape.view(), NOISE).uniform(noise).uniform("BlockLandScene", SceneLighting.uniforms()).draw();
        noise.pass(new Matrix4f(), 32, 8, 1);
        Fullscreen.pass("BlockLand clouds: detail noise", detail.view(), NOISE).uniform(noise).uniform("BlockLandScene", SceneLighting.uniforms()).draw();
        noise.pass(new Matrix4f(), 1024, 1, 2);
        Fullscreen.pass("BlockLand clouds: weather map", weather.view(), NOISE).uniform(noise).uniform("BlockLandScene", SceneLighting.uniforms()).draw();
        noiseReady = true;
    }

    private static double wrap(double v) {
        return v - Math.floor(v / PERIOD) * PERIOD;
    }

    /** Frees the per-frame targets; the noise stays (it never changes). */
    private void release() {
        output = null;
        historyValid = false;
        marched.close();
        for (RenderTexture t : history) t.close();
    }
}
