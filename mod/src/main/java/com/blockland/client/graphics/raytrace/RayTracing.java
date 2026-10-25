package com.blockland.client.graphics.raytrace;

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
import com.blockland.client.graphics.light.LightColors;
import com.blockland.client.graphics.shadow.SunPath;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Ray traced lighting of the world (rendering spec §4): Minecraft's blocks around the camera kept as voxels
 * ({@link VoxelWorld}) and traced per pixel in the frame's after-level phase — one bounce of diffuse light (Minecraft's own
 * sky and block light carried off the faces the rays hit, the glow of emissive blocks, sunlight bounced off sunlit faces)
 * and ray traced ambient occlusion in place of the screen-space one. The post chain's decode pass adds the light and
 * applies the occlusion ({@link WorldEffects#rayTraced()}).
 * <p>
 * Passes: a guide (world normal, linear depth) and the albedo at the internal resolution; the rays at the quality's
 * resolution; then either BlockLand's denoiser (temporal accumulation by reprojection with luminance moments, four
 * variance-guided à-trous passes, the first of which becomes the next frame's history) or nothing, when DLSS Ray
 * Reconstruction denoises the frame instead (its own albedo guide and the normals go to the upscaler through
 * {@link FrameContext}); a resolve brings the light to the internal resolution and multiplies the plain albedo in. Any failure switches ray tracing off
 * for the session (the status says so).
 */
public final class RayTracing implements FrameStage, WorldEffects {
    private static final int INFO_SIZE = new Std140SizeCalculator().putMat4f().putMat4f()
            .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();
    /** Ambient occlusion reaches this far (blocks). */
    private static final float AO_RADIUS = 3f;
    /** CPU time per frame for voxel updates. */
    private static final long VOXEL_BUDGET_NANOS = 2_500_000L;
    private static final float MAX_HISTORY = 32;

    private static final RenderPipeline GUIDE = pipeline("rt_guide").withSampler("DepthSampler").build();
    private static final RenderPipeline ALBEDO = pipeline("rt_albedo").withSampler("DepthSampler").withSampler("GuideSampler")
            .withSampler("SceneSampler").build();
    private static final RenderPipeline TRACE = pipeline("rt_trace").withSampler("DepthSampler").withSampler("GuideSampler").build();
    private static final RenderPipeline TEMPORAL = pipeline("rt_temporal").withSampler("DepthSampler").withSampler("GuideSampler")
            .withSampler("CurrentSampler").withSampler("HistorySampler").withSampler("MetaSampler").build();
    private static final RenderPipeline FILTER = pipeline("rt_filter").withSampler("InSampler").withSampler("GuideSampler")
            .withSampler("MetaSampler").build();
    private static final int FILTER_PASSES = 4;
    private static final RenderPipeline RESOLVE = pipeline("rt_resolve").withSampler("InSampler").withSampler("GuideSampler")
            .withSampler("AlbedoSampler").build();

    private static RayTracing instance;

    private final VoxelWorld voxels = new VoxelWorld();
    private final RenderTexture guide = new RenderTexture("BlockLand ray tracing guide", RenderTexture.Format.RGBA16F, FilterMode.NEAREST);
    private final RenderTexture albedo = new RenderTexture("BlockLand ray tracing albedo", RenderTexture.Format.RGBA16F, FilterMode.NEAREST);
    private final RenderTexture reconstructionAlbedo = new RenderTexture("BlockLand ray tracing RR albedo", RenderTexture.Format.RGBA16F,
            FilterMode.NEAREST);
    private final RenderTexture traced = new RenderTexture("BlockLand ray traced light", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    private final RenderTexture[] history = {
            new RenderTexture("BlockLand ray tracing history A", RenderTexture.Format.RGBA16F, FilterMode.LINEAR),
            new RenderTexture("BlockLand ray tracing history B", RenderTexture.Format.RGBA16F, FilterMode.LINEAR)};
    private final RenderTexture[] meta = {
            new RenderTexture("BlockLand ray tracing history length A", RenderTexture.Format.RGBA16F, FilterMode.NEAREST),
            new RenderTexture("BlockLand ray tracing history length B", RenderTexture.Format.RGBA16F, FilterMode.NEAREST)};
    private final RenderTexture[] scratch = {
            new RenderTexture("BlockLand ray tracing filter A", RenderTexture.Format.RGBA16F, FilterMode.NEAREST),
            new RenderTexture("BlockLand ray tracing filter B", RenderTexture.Format.RGBA16F, FilterMode.NEAREST)};
    private final RenderTexture result = new RenderTexture("BlockLand ray traced lighting", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    @Nullable
    private Ubo info, guidePass, albedoPass, reconstructionPass, temporalColor, temporalMeta, resolvePass;
    private final Ubo[] filterPasses = new Ubo[FILTER_PASSES];
    private final Matrix4f viewToWorld = new Matrix4f();
    private final Vector3f sun = new Vector3f();
    @Nullable
    private GpuTextureView output;
    private int current;
    private boolean historyValid, broken;
    private float millis;

    private RayTracing() {
    }

    public static void init(BusGroup modBus) {
        instance = new RayTracing();
        Graphics.addStage(instance);
        Graphics.setRayTracing(instance);
    }

    private static RenderPipeline.Builder pipeline(String fragment) {
        return Fullscreen.pipeline(fragment).withUniform("RtInfo", UniformType.UNIFORM_BUFFER)
                .withSampler("VoxelColor").withSampler("VoxelLight");
    }

    @Nullable
    @Override
    public GpuTextureView rayTraced() {
        return output;
    }

    @Override
    public void restart() {
        historyValid = false;
    }

    @Override
    public void beginFrame(FrameContext frame) {
        output = null;
        frame.rayAlbedo = null;
        frame.rayNormals = null;
    }

    @Override
    public void afterLevel(FrameContext frame) {
        GraphicsSettings settings = Graphics.settings();
        GraphicsStatus status = Graphics.status();
        output = null;
        status.rayReconstructionActive = false;
        if (!settings.rayTracing) {
            status.rayTracingActive = false;
            status.rayTracingReason = null;
            release();
            return;
        }
        if (broken) {
            status.rayTracingActive = false;
            status.rayTracingReason = "blockland.graphics.status.rt.failed";
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || frame.worldDepth == null || frame.scene == null) {
            status.rayTracingActive = false;
            return;
        }
        long start = System.nanoTime();
        try {
            trace(frame, settings, status, level);
            status.rayTracingActive = true;
            status.rayTracingReason = null;
        } catch (Throwable t) {
            broken = true;
            output = null;
            status.rayTracingActive = false;
            status.rayTracingReason = "blockland.graphics.status.rt.failed";
            BlockLand.LOGGER.error("[BlockLand] Ray tracing failed and is off for this session", t);
        }
        millis = millis * 0.9f + (System.nanoTime() - start) / 1e6f * 0.1f;
        status.rayMillis = millis;
    }

    private void trace(FrameContext frame, GraphicsSettings settings, GraphicsStatus status, ClientLevel level) {
        int sections = VoxelWorld.sectionsFor(settings.rayDistance);
        voxels.update(level, frame.camX, frame.camY, frame.camZ, sections, VOXEL_BUDGET_NANOS);
        status.rayVoxelsReady = voxels.readiness();
        status.rayVolume = voxels.sectionsX() * 16 + "×" + voxels.sectionsY() * 16 + "×" + voxels.sectionsZ() * 16;

        GraphicsSettings.RayQuality quality = settings.rayQualityMode();
        // Ray Reconstruction denoises the frame itself: full resolution, raw rays, its guides for the upscaler
        boolean reconstruction = settings.rayReconstruction() && status.rayReconstructionAvailable && "dlss".equals(status.activeAa);
        int w = frame.internalWidth, h = frame.internalHeight;
        float scale = reconstruction ? 1f : quality.scale;
        int tw = Math.max(1, Math.round(w * scale)), th = Math.max(1, Math.round(h * scale));
        boolean resized = guide.ensure(w, h) | albedo.ensure(w, h) | result.ensure(w, h) | traced.ensure(tw, th);
        for (RenderTexture t : history) resized |= t.ensure(tw, th);
        for (RenderTexture t : meta) resized |= t.ensure(tw, th);
        for (RenderTexture t : scratch) resized |= t.ensure(tw, th);
        // a camera cut: nothing of the last frame reprojects into this one
        double dx = frame.camX - frame.prevCamX, dy = frame.camY - frame.prevCamY, dz = frame.camZ - frame.prevCamZ;
        if (resized || dx * dx + dy * dy + dz * dz > 64) historyValid = false;
        ensureUbos();
        writeInfo(frame, settings, level, quality, w, h, tw, th);

        GpuTextureView depth = frame.worldDepth;
        GpuTextureView voxelColor = voxels.colors(), voxelLight = voxels.light();
        pass("BlockLand ray tracing: guide", guide.view(), GUIDE, guidePass, voxelColor, voxelLight).sampler("DepthSampler", depth).draw();
        pass("BlockLand ray tracing: albedo", albedo.view(), ALBEDO, albedoPass, voxelColor, voxelLight)
                .sampler("DepthSampler", depth).sampler("GuideSampler", guide.view())
                .sampler("SceneSampler", frame.scene.getColorTextureView()).draw();
        if (reconstruction) {
            // Ray Reconstruction's own albedo guide; the resolve keeps the plain one, or the added light would be the
            // already lit scene × the light again
            reconstructionAlbedo.ensure(w, h);
            pass("BlockLand ray tracing: RR albedo", reconstructionAlbedo.view(), ALBEDO, reconstructionPass, voxelColor, voxelLight)
                    .sampler("DepthSampler", depth).sampler("GuideSampler", guide.view())
                    .sampler("SceneSampler", frame.scene.getColorTextureView()).draw();
        } else {
            reconstructionAlbedo.close();
        }
        pass("BlockLand ray tracing: rays", traced.view(), TRACE, guidePass, voxelColor, voxelLight)
                .sampler("DepthSampler", depth).sampler("GuideSampler", guide.view()).draw();

        GpuTextureView light = traced.view();
        if (!reconstruction) {
            int previous = current;
            current ^= 1;
            temporalColor.pass(new Matrix4f(), 0, historyValid ? MAX_HISTORY : 1);
            temporalMeta.pass(new Matrix4f(), 1, historyValid ? MAX_HISTORY : 1);
            pass("BlockLand ray tracing: accumulate", scratch[0].view(), TEMPORAL, temporalColor, voxelColor, voxelLight)
                    .sampler("DepthSampler", depth).sampler("GuideSampler", guide.view()).sampler("CurrentSampler", traced.view())
                    .sampler("HistorySampler", history[previous].view()).sampler("MetaSampler", meta[previous].view()).draw();
            pass("BlockLand ray tracing: history", meta[current].view(), TEMPORAL, temporalMeta, voxelColor, voxelLight)
                    .sampler("DepthSampler", depth).sampler("GuideSampler", guide.view()).sampler("CurrentSampler", traced.view())
                    .sampler("HistorySampler", history[previous].view()).sampler("MetaSampler", meta[previous].view()).draw();
            historyValid = true;
            // the first filter pass is the next frame's history (SVGF): the history no longer holds raw noise
            light = scratch[0].view();
            for (int i = 0; i < FILTER_PASSES; i++) {
                RenderTexture into = i == 0 ? history[current] : scratch[i & 1];
                pass("BlockLand ray tracing: filter " + (1 << i), into.view(), FILTER, filterPasses[i], voxelColor, voxelLight)
                        .sampler("InSampler", light).sampler("GuideSampler", guide.view())
                        .sampler("MetaSampler", meta[current].view()).draw();
                light = into.view();
            }
        } else {
            historyValid = false;
            frame.rayAlbedo = reconstructionAlbedo.view();
            frame.rayNormals = guide.view();
        }
        pass("BlockLand ray tracing: resolve", result.view(), RESOLVE, resolvePass, voxelColor, voxelLight)
                .sampler("InSampler", light).sampler("GuideSampler", guide.view()).sampler("AlbedoSampler", albedo.view()).draw();
        output = result.view();
    }

    private Fullscreen.Pass pass(String label, GpuTextureView into, RenderPipeline pipeline, Ubo params, GpuTextureView voxelColor,
                                 GpuTextureView voxelLight) {
        return Fullscreen.pass(label, into, pipeline).uniform(params).uniform("RtInfo", info.buffer().slice())
                .sampler("VoxelColor", voxelColor).sampler("VoxelLight", voxelLight);
    }

    /** The frame's {@code RtInfo} block (rt_common.glsl). */
    private void writeInfo(FrameContext frame, GraphicsSettings settings, ClientLevel level, GraphicsSettings.RayQuality quality,
                           int w, int h, int tw, int th) {
        viewToWorld.set(frame.viewRotation).transpose();
        Matrix4f reprojection = reprojection(frame);
        Matrix4f p = frame.projection;
        float camX = (float) (frame.camX - voxels.cornerX() * 16.0), camY = (float) (frame.camY - voxels.cornerY() * 16.0);
        float camZ = (float) (frame.camZ - voxels.cornerZ() * 16.0);
        int nx = voxels.sectionsX(), ny = voxels.sectionsY(), nz = voxels.sectionsZ();
        float daylight = level.getSkyDarken(frame.partialTick);
        SunPath.light(level, frame.partialTick, sun);
        boolean day = SunPath.isSun(level, frame.partialTick);
        float elevation = Math.max(0, sun.y);
        float rain = level.getRainLevel(frame.partialTick);
        float strength = (day ? Mth.clamp(elevation / 0.15f, 0, 1) * 1.6f : Mth.clamp(elevation / 0.15f, 0, 1) * 0.12f) * (1 - 0.8f * rain);
        float warm = Mth.clamp(elevation / 0.35f, 0, 1);
        float[] color = {day ? 1f : 0.55f, day ? Mth.lerp(warm, 0.62f, 0.95f) : 0.65f, day ? Mth.lerp(warm, 0.4f, 0.88f) : 0.85f};
        // the player's light colours, as on the lit faces
        LightColors.tintSky(settings, day, color);
        float[] block = new float[3];
        LightColors.blockLight(settings, block);
        float distance = settings.rayDistance;
        int cells = Math.min(160, Math.round(distance * quality.cellsPerBlock * 1.7f));
        info.write(o -> o.putMat4f(viewToWorld).putMat4f(reprojection)
                .putVec4(p.m00(), p.m11(), p.m22(), p.m32())
                .putVec4(w, h, tw, th)
                .putVec4(camX, camY, camZ, frame.frame % 1024)
                .putVec4(nx, ny, nz, VoxelWorld.COLUMNS)
                .putVec4(Math.floorMod(voxels.cornerX(), nx), Math.floorMod(voxels.cornerY(), ny), Math.floorMod(voxels.cornerZ(), nz), 0)
                .putVec4(quality.rays, distance, cells, AO_RADIUS)
                .putVec4(daylight, settings.rayGlobalIllumination, settings.rayEmission, settings.raySunBounce ? 1 : 0)
                .putVec4(sun.x, sun.y, sun.z, strength)
                .putVec4(color[0], color[1], color[2], settings.rayAmbientOcclusion)
                .putVec4(block[0], block[1], block[2], 0));
        for (int i = 0; i < FILTER_PASSES; i++) filterPasses[i].pass(new Matrix4f(), 1 << i);
    }

    /** Current unjittered NDC → previous clip (as the post chain's, camera motion included). */
    private static Matrix4f reprojection(FrameContext frame) {
        Matrix4f current = new Matrix4f(frame.projection).mul(frame.viewRotation).invert();
        return new Matrix4f(frame.prevProjection).mul(frame.prevViewRotation)
                .translate((float) (frame.camX - frame.prevCamX), (float) (frame.camY - frame.prevCamY), (float) (frame.camZ - frame.prevCamZ))
                .mul(current);
    }

    private void ensureUbos() {
        if (info != null) return;
        info = new Ubo("ray tracing", INFO_SIZE);
        guidePass = Ubo.pass("ray tracing guide").pass(new Matrix4f());
        albedoPass = Ubo.pass("ray tracing albedo").pass(new Matrix4f(), 0);
        reconstructionPass = Ubo.pass("ray tracing RR albedo").pass(new Matrix4f(), 1);
        temporalColor = Ubo.pass("ray tracing accumulate");
        temporalMeta = Ubo.pass("ray tracing history");
        resolvePass = Ubo.pass("ray tracing resolve").pass(new Matrix4f());
        for (int i = 0; i < FILTER_PASSES; i++) filterPasses[i] = Ubo.pass("ray tracing filter " + i);
    }

    private void release() {
        output = null;
        historyValid = false;
        voxels.close();
        guide.close();
        albedo.close();
        reconstructionAlbedo.close();
        traced.close();
        result.close();
        for (RenderTexture t : history) t.close();
        for (RenderTexture t : meta) t.close();
        for (RenderTexture t : scratch) t.close();
    }
}
