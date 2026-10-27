package com.blockland.client.graphics.upscale;

import com.blockland.BlockLand;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.graphics.upscale.dlss.DlssBridge;
import com.blockland.client.graphics.upscale.dlss.DlssException;
import com.blockland.client.graphics.upscale.dlss.SlRuntime;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * NVIDIA DLSS Super Resolution (rendering spec §4) through {@link DlssBridge}: NVIDIA's Streamline runtime on a Vulkan
 * device that shares its textures with Minecraft's OpenGL (the session's runtime, {@link StreamlineHost}, shared with
 * Frame Generation). Per frame three small passes draw DLSS's inputs straight into
 * the shared textures — the decoded HDR colour, the depth, the motion vectors — upside down, since DLSS's textures run top
 * to bottom; DLSS upscales; a last pass brings the result back the right way up with the hand / viewmodel mask the later
 * passes need. The conventions (jitter and motion-vector signs, texel space) were measured against the real runtime with
 * {@code tools/dlss/check_dlss.py}.
 * <p>
 * The runtime is not shipped with the mod: {@code tools/dlss/install_runtime.py} copies NVIDIA's binaries into
 * {@code <game>/blockland/native/streamline}. The DLSS model is whatever that {@code nvngx_dlss.dll} (or the driver's DLSS
 * override) brings, so a newer one upgrades it without a mod update. Anything that fails switches DLSS off for the session
 * with the reason on the 画质 tab, and the built-in temporal upscaler takes over the same frame.
 */
public final class DlssUpscaler implements Upscaler, StreamlineHost.Client {
    /** Files of NVIDIA's Streamline SDK (redistributable binaries) the bridge needs. */
    public static final List<String> RUNTIME = SlRuntime.DLSS_FILES;
    private static final long RECHECK_NANOS = 5_000_000_000L;

    private static final RenderPipeline COLOR = Fullscreen.pipeline("gfx_dlss_color").withSampler("InSampler").build();
    /** DLSS's depth and motion-vector inputs (Frame Generation draws the same ones for itself). */
    static final RenderPipeline DEPTH = Fullscreen.pipeline("gfx_dlss_depth")
            .withSampler("ColorSampler").withSampler("WorldDepthSampler").build();
    static final RenderPipeline MOTION = Fullscreen.pipeline("gfx_dlss_motion")
            .withSampler("ColorSampler").withSampler("WorldDepthSampler").withSampler("HandVelocitySampler")
            .withUniform(com.blockland.client.graphics.post.ObjectMotionBlock.BLOCK, com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER).build();
    private static final RenderPipeline OUTPUT = Fullscreen.pipeline("gfx_dlss_output")
            .withSampler("InSampler").withSampler("HandSampler").build();
    /** Ray Reconstruction's guides from the ray tracer's albedo and normals (rows flipped). */
    private static final RenderPipeline GUIDES = Fullscreen.pipeline("rt_guides").withSampler("InSampler").build();

    /**
     * @param available DLSS can run (or has not been tried yet and everything it needs is there)
     * @param reason    translation key of why not, or null
     * @param detail    shown as is (GPU and DLSS versions, missing files, the failing call)
     */
    public record Probe(boolean available, @Nullable String reason, String detail) {
    }

    @Nullable
    private static Probe probe;
    private static long probedAt;
    /** Why the bridge failed this session (it is not retried until the game restarts), or null. */
    @Nullable
    private static DlssException failure;
    @Nullable
    private static String running;
    private static boolean rrInstalled;
    private static long rrCheckedAt;

    private final TemporalUpscaler fallback;
    @Nullable
    private DlssBridge bridge;
    /** Blaze3D's view of the bridge's GL textures (for the passes that draw into them / read from them). */
    @Nullable
    private BridgeTexture color, depth, motion, output;
    @Nullable
    private BridgeTexture albedo, specular, normals;
    @Nullable
    private Ubo[] guidePasses;
    private final RenderTexture result = new RenderTexture("BlockLand DLSS output", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    private final Ubo ubo = Ubo.pass("DLSS motion");
    private final RenderTexture still = new RenderTexture("BlockLand DLSS no motion", RenderTexture.Format.RG16F, FilterMode.NEAREST);
    private boolean history;

    public DlssUpscaler(TemporalUpscaler fallback) {
        this.fallback = fallback;
    }

    @Override
    public String id() {
        return bridge != null && failure == null ? "dlss" : fallback.id();
    }

    @Override
    public boolean temporal() {
        return true;
    }

    @Override
    public GpuTextureView resolve(Frame frame) {
        if (failure != null) return fallback.resolve(frame);
        try {
            return evaluate(frame);
        } catch (DlssException e) {
            fail(e);
        } catch (RuntimeException e) {
            fail(new DlssException("blockland.graphics.status.dlss.failed", e.toString(), e));
        }
        return fallback.resolve(frame);
    }

    private GpuTextureView evaluate(Frame frame) {
        FrameContext ctx = frame.context();
        int rw = ctx.internalWidth, rh = ctx.internalHeight, ow = ctx.outputWidth, oh = ctx.outputHeight;
        if (bridge == null) {
            bridge = DlssBridge.on(StreamlineHost.acquire(this, false));
            running = bridge.description();
            BlockLand.LOGGER.info("[BlockLand] DLSS ready: {}", running);
            probe = null;
        }
        if (!bridge.matches(rw, rh, ow, oh)) {
            // Blaze3D's wrappers own the GL names they delete: let them go before the bridge re-creates its textures
            releaseWrappers();
            releaseGuides();
            bridge.resize(rw, rh, ow, oh);
            color = new BridgeTexture("BlockLand DLSS colour", bridge.colorTexture(), rw, rh);
            depth = new BridgeTexture("BlockLand DLSS depth", bridge.depthTexture(), rw, rh);
            motion = new BridgeTexture("BlockLand DLSS motion", bridge.motionTexture(), rw, rh);
            output = new BridgeTexture("BlockLand DLSS result", bridge.outputTexture(), ow, oh);
            history = false;
        }
        GpuTextureView worldDepth = frame.worldDepth() != null ? frame.worldDepth() : frame.color();

        Fullscreen.pass("BlockLand DLSS: colour", color.view, COLOR).sampler("InSampler", frame.color()).draw();
        Fullscreen.pass("BlockLand DLSS: depth", depth.view, DEPTH)
                .sampler("ColorSampler", frame.color()).sampler("WorldDepthSampler", worldDepth).draw();
        ubo.pass(frame.reprojection(), rw, rh, 1f / rw, 1f / rh);
        Fullscreen.pass("BlockLand DLSS: motion", motion.view, MOTION)
                .sampler("ColorSampler", frame.color()).sampler("WorldDepthSampler", worldDepth)
                .sampler("HandVelocitySampler", ctx.handVelocity != null ? ctx.handVelocity : still()).uniform(ubo)
                .uniform(com.blockland.client.graphics.post.ObjectMotionBlock.BLOCK, com.blockland.client.graphics.post.ObjectMotionBlock.slice()).draw();

        GraphicsSettings settings = com.blockland.client.graphics.Graphics.settings();
        DlssBridge.Frame parameters = parameters(ctx, frame.reprojection(), history && frame.historyValid() && frame.worldDepth() != null);
        boolean reconstruction = ctx.rayAlbedo != null && ctx.rayNormals != null && settings.rayReconstruction()
                && bridge.rayReconstructionAvailable();
        if (reconstruction) {
            if (bridge.ensureGuides() || albedo == null) {
                releaseGuides();
                albedo = new BridgeTexture("BlockLand RR albedo", bridge.albedoTexture(), rw, rh);
                specular = new BridgeTexture("BlockLand RR specular", bridge.specularTexture(), rw, rh);
                normals = new BridgeTexture("BlockLand RR normals", bridge.normalTexture(), rw, rh);
            }
            if (guidePasses == null) {
                guidePasses = new Ubo[3];
                for (int i = 0; i < 3; i++) guidePasses[i] = Ubo.pass("DLSS RR guide " + i).pass(new Matrix4f(), i);
            }
            Fullscreen.pass("BlockLand DLSS RR: albedo", albedo.view, GUIDES).sampler("InSampler", ctx.rayAlbedo).uniform(guidePasses[0]).draw();
            Fullscreen.pass("BlockLand DLSS RR: normals", normals.view, GUIDES).sampler("InSampler", ctx.rayNormals).uniform(guidePasses[1]).draw();
            Fullscreen.pass("BlockLand DLSS RR: specular", specular.view, GUIDES).sampler("InSampler", ctx.rayAlbedo).uniform(guidePasses[2]).draw();
            Matrix4f worldToView = new Matrix4f(ctx.viewRotation);
            bridge.evaluate(mode(), settings.dlssPresetMode().sl, parameters, worldToView.get(new float[16]),
                    new Matrix4f(worldToView).transpose().get(new float[16]));
        } else {
            bridge.evaluate(mode(), settings.dlssPresetMode().sl, parameters);
        }
        com.blockland.client.graphics.Graphics.status().rayReconstructionActive = reconstruction;
        history = true;

        result.ensure(ow, oh);
        Fullscreen.pass("BlockLand DLSS: output", result.view(), OUTPUT)
                .sampler("InSampler", output.view).sampler("HandSampler", frame.color()).draw();
        return result.view();
    }

    /**
     * Streamline's view of this frame (texel space top to bottom: see gfx_dlss_motion.fsh and tools/dlss); Frame Generation
     * sends the same.
     */
    static DlssBridge.Frame parameters(FrameContext ctx, Matrix4f reprojection, boolean historyValid) {
        int rw = ctx.internalWidth, rh = ctx.internalHeight;
        // the projection moved the picture by the jitter; DLSS wants that shift, with its rows running down
        float jitterX = ctx.jitterX * rw * 0.5f, jitterY = -ctx.jitterY * rh * 0.5f;
        float[] viewToClip = ctx.projection.get(new float[16]);
        float[] clipToView = new Matrix4f(ctx.projection).invert().get(new float[16]);
        float[] clipToPrev = reprojection.get(new float[16]);
        float[] prevToClip = new Matrix4f(reprojection).invert().get(new float[16]);
        Matrix4f toWorld = new Matrix4f(ctx.viewRotation).transpose();
        Vector3f right = toWorld.transformDirection(new Vector3f(1, 0, 0));
        Vector3f up = toWorld.transformDirection(new Vector3f(0, 1, 0));
        Vector3f forward = toWorld.transformDirection(new Vector3f(0, 0, -1));
        float fov = (float) (2 * Math.atan(1 / Math.max(1e-4f, ctx.projection.m11())));
        return new DlssBridge.Frame(jitterX, jitterY, 1f / rw, 1f / rh, !historyValid, viewToClip, clipToView, clipToPrev, prevToClip,
                new float[]{(float) ctx.camX, (float) ctx.camY, (float) ctx.camZ}, new float[]{up.x, up.y, up.z},
                new float[]{right.x, right.y, right.z}, new float[]{forward.x, forward.y, forward.z},
                ctx.near, ctx.far, fov, rw / (float) Math.max(1, rh));
    }

    private static DlssBridge.Mode mode() {
        GraphicsSettings settings = com.blockland.client.graphics.Graphics.settings();
        if (settings.upscalerMode() == GraphicsSettings.Upscaler.DLAA) return DlssBridge.Mode.DLAA;
        return switch (settings.upscaleQualityMode()) {
            case NATIVE -> DlssBridge.Mode.DLAA;
            case QUALITY -> DlssBridge.Mode.QUALITY;
            case BALANCED -> DlssBridge.Mode.BALANCED;
            case PERFORMANCE -> DlssBridge.Mode.PERFORMANCE;
            case ULTRA_PERFORMANCE -> DlssBridge.Mode.ULTRA_PERFORMANCE;
        };
    }

    private void fail(DlssException e) {
        failure = e;
        probe = null;
        BlockLand.LOGGER.error("[BlockLand] DLSS switched off for this session, the built-in upscaler takes over: {}", e.getMessage(), e);
        closeBridge();
        StreamlineHost.release(this);
        fallback.invalidate();
    }

    @Override
    public void releaseRuntime() {
        closeBridge();
    }

    private void closeBridge() {
        releaseWrappers();
        if (bridge != null) {
            try {
                bridge.close();
            } catch (RuntimeException closing) {
                BlockLand.LOGGER.warn("[BlockLand] Closing the DLSS bridge failed: {}", closing.toString());
            }
            bridge = null;
        }
        history = false;
    }

    private void releaseWrappers() {
        for (BridgeTexture wrapped : new BridgeTexture[]{color, depth, motion, output}) if (wrapped != null) wrapped.close();
        color = depth = motion = output = null;
        releaseGuides();
    }

    private void releaseGuides() {
        for (BridgeTexture wrapped : new BridgeTexture[]{albedo, specular, normals}) if (wrapped != null) wrapped.close();
        albedo = specular = normals = null;
    }

    /**
     * Ray Reconstruction on this machine: available when DLSS is, its runtime files are there and it did not fail (whether
     * the GPU supports it is known once the runtime is up: {@link #rayReconstructionFailure}).
     */
    public static boolean rayReconstructionAvailable() {
        return probe().available() && rayReconstructionReason() == null;
    }

    /** Why Ray Reconstruction cannot run (key), or null. */
    @Nullable
    public static String rayReconstructionReason() {
        long now = System.nanoTime();
        if (rrCheckedAt == 0 || now - rrCheckedAt > RECHECK_NANOS) {
            rrInstalled = SlRuntime.missing(runtimeFolder(), SlRuntime.RAY_RECONSTRUCTION_FILES).isEmpty();
            rrCheckedAt = now;
        }
        if (!rrInstalled) return "blockland.graphics.status.rr.no_runtime";
        DlssException failure = StreamlineHost.rayReconstructionFailure();
        return failure != null ? failure.reason : null;
    }

    /** A 1×1 zero motion texture for frames without the viewmodel's motion. */
    private GpuTextureView still() {
        if (still.ensure(1, 1)) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(still.texture(), 0);
        return still.view();
    }

    @Override
    public void invalidate() {
        history = false;
    }

    @Override
    public void close() {
        closeBridge();
        StreamlineHost.release(this);
        result.close();
        still.close();
        ubo.close();
    }

    // ---- availability -----------------------------------------------------------------------------------------------

    /** What this machine has (render thread; re-checked every few seconds, the runtime may be copied in meanwhile). */
    public static Probe probe() {
        long now = System.nanoTime();
        if (probe == null || now - probedAt > RECHECK_NANOS) {
            probe = check();
            probedAt = now;
        }
        return probe;
    }

    private static Probe check() {
        if (failure != null) return new Probe(false, failure.reason, failure.detail);
        String vendor = RenderSystem.getDevice().getVendor();
        String renderer = RenderSystem.getDevice().getRenderer();
        if (vendor == null || !vendor.toUpperCase(Locale.ROOT).contains("NVIDIA")) {
            // a laptop running the game on its integrated GPU lands here too
            return new Probe(false, "blockland.graphics.status.dlss.not_nvidia", renderer == null ? "" : renderer);
        }
        if (renderer == null || !renderer.toUpperCase(Locale.ROOT).contains("RTX")) {
            return new Probe(false, "blockland.graphics.status.dlss.not_rtx", renderer == null ? "" : renderer);
        }
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.GL_EXT_memory_object || !caps.GL_EXT_memory_object_win32 || !caps.GL_EXT_semaphore || !caps.GL_EXT_semaphore_win32) {
            return new Probe(false, "blockland.graphics.status.dlss.no_interop", renderer);
        }
        Path folder = runtimeFolder();
        List<String> missing = SlRuntime.missing(folder, RUNTIME);
        if (!missing.isEmpty()) {
            return new Probe(false, "blockland.graphics.status.dlss.no_runtime", String.join(", ", missing) + " → " + folder);
        }
        return new Probe(true, null, running != null ? running : renderer);
    }

    /** Where the Streamline runtime goes: {@code <game directory>/blockland/native/streamline}. */
    public static Path runtimeFolder() {
        return StreamlineHost.runtimeFolder();
    }
}
