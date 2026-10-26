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
import com.blockland.client.graphics.water.BlissNoise;
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
 * Volumetric clouds (rendering spec §14): Bliss's clouds (a Chocapic13's shaders edit, by Xonk; its cloud code is ported
 * as {@code blockland:bliss_clouds.glsl}, see {@code shaders/include/bliss/LICENSE.md}) — two cumulus layers and an
 * altostratus sheet — marched per pixel at a share of the internal resolution in the frame's after-level phase (stopped
 * by the world's depth), accumulated over frames, and folded into the picture by the post chain's decode pass
 * (transmittance, then the light they scatter). Their noise is Bliss's own texture ({@link BlissNoise}). Lit by the scene
 * uniforms' sun or moon and sky, so they follow the time of day, the weather and the player's light colours.
 * <p>
 * The clouds' inputs (Bliss's camera position and clock, the coverage setting, rain) go into the scene uniforms at
 * {@code cameraReady} ({@link FrameContext#clouds}): every lit surface and the volumetric fog take the clouds' shadow from
 * the same code (Bliss's GetCloudShadow). While they run, vanilla's clouds are left out by
 * the lighting pack's cloud shader ({@code BlBlockLight.a}, decided at frame start through
 * {@link GraphicsStatus#cloudsActive}); without world lighting the pack is not loaded and vanilla's clouds stay. Any
 * failure switches them off for the session (the status says so).
 */
public final class VolumetricClouds implements FrameStage, WorldEffects {
    /** Bliss's clock repeats after 100 days (its worldTime + worldDay % 100, in ticks). */
    private static final long CLOCK = 100L * 24000L;
    /** Bliss's layer heights are absolute for the overworld, whose clouds are at this height. */
    private static final float BLISS_CLOUD_HEIGHT = 192f;
    /** Weight of this frame against the accumulated clouds. */
    private static final float BLEND = 0.12f;
    /** Bliss's CLOUD_SHADOW_STRENGTH. */
    private static final float SHADOW = 1f;
    /** Bliss's ray-march steps (minRayMarchSteps, maxRayMarchSteps) per 云层质量: its LQ, its HQ, and twice HQ's. */
    private static final int[][] STEPS = {{10, 30}, {15, 15}, {30, 30}};

    private static final RenderPipeline MARCH = Fullscreen.pipeline("gfx_clouds").withSampler("DepthSampler").withSampler("FarDepthSampler")
            .withSampler("noisetex").withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER).build();
    private static final RenderPipeline TEMPORAL = Fullscreen.pipeline("gfx_clouds_temporal").withSampler("CurrentSampler")
            .withSampler("HistorySampler").build();

    private final RenderTexture marched = new RenderTexture("BlockLand clouds (this frame)", RenderTexture.Format.RGBA16F, FilterMode.NEAREST);
    private final RenderTexture[] history = {
            new RenderTexture("BlockLand clouds A", RenderTexture.Format.RGBA16F, FilterMode.LINEAR),
            new RenderTexture("BlockLand clouds B", RenderTexture.Format.RGBA16F, FilterMode.LINEAR)};
    /** Made on the render thread at the first march (the stage is built during mod construction). */
    @Nullable
    private Ubo march, temporal;
    @Nullable
    private GpuTextureView output;
    private int current;
    private boolean historyValid, broken, active;
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
        float[] c = frame.clouds;
        // Bliss's cameraPosition: the world position, its heights as in the overworld whatever this dimension's clouds
        c[0] = (float) frame.camX;
        c[1] = (float) (frame.camY - (height.get() - BLISS_CLOUD_HEIGHT));
        c[2] = (float) frame.camZ;
        // Bliss's clock (worldTime + worldDay % 100) from the game time, so the clouds move where the day clock is stopped
        c[3] = (float) (level.getGameTime() % CLOCK + frame.partialTick);
        // the weather is Bliss's: rain closes the low layer, clears the high one, thickens the altostratus
        c[4] = settings.cloudCoverage;
        c[5] = level.getRainLevel(frame.partialTick);
        c[6] = SHADOW;
        c[7] = 0;
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
        }
        GpuTextureView noise = BlissNoise.view();
        if (noise == null) throw new IllegalStateException("Bliss's noise texture is missing: " + BlissNoise.LOCATION);

        GraphicsSettings.CloudQuality quality = Graphics.settings().cloudQualityMode();
        int w = Math.max(1, Math.round(frame.internalWidth * quality.scale));
        int h = Math.max(1, Math.round(frame.internalHeight * quality.scale));
        boolean resized = marched.ensure(w, h);
        for (RenderTexture t : history) resized |= t.ensure(w, h);
        if (resized) historyValid = false;

        Matrix4f toWorld = new Matrix4f(frame.projection).mul(frame.viewRotation).invert();
        int[] steps = STEPS[Math.min(quality.ordinal(), STEPS.length - 1)];
        march.pass(toWorld, frame.internalWidth, frame.internalHeight, steps[0], steps[1], frame.frame % 1024,
                frame.farNear, frame.farFar, frame.farDepth != null ? 1 : 0);
        Fullscreen.pass("BlockLand clouds: march", marched.view(), MARCH).sampler("DepthSampler", frame.worldDepth)
                .sampler("FarDepthSampler", frame.farDepth != null ? frame.farDepth : frame.worldDepth)
                .sampler("noisetex", noise).uniform("BlockLandScene", SceneLighting.uniforms()).uniform(march).draw();

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

    /** Frees the per-frame targets. */
    private void release() {
        output = null;
        historyValid = false;
        marched.close();
        for (RenderTexture t : history) t.close();
    }
}
