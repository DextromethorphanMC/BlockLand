package com.blockland.client.graphics.upscale;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.GraphicsStatus;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.graphics.post.PostChain;
import com.blockland.client.graphics.upscale.dlss.DlssBridge;
import com.blockland.client.graphics.upscale.dlss.DlssException;
import com.blockland.client.graphics.upscale.dlss.FrameGeneration;
import com.blockland.client.graphics.upscale.dlss.SlRuntime;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

import javax.annotation.Nullable;
import java.util.List;

/**
 * DLSS Frame Generation and Multi Frame Generation in the frame (rendering spec §4), around {@link FrameGeneration}: while
 * it is switched on and the pipeline runs, the frames reach the screen through a Vulkan swapchain in a child window over
 * the game window (OpenGL still swaps its own, covered back buffer, without v-sync), so DLSS can insert the generated
 * frames at present.
 * <ul>
 *   <li>frame start ({@link #beginFrame}): presenter up or down, sizes, PC-latency markers;</li>
 *   <li>before the HUD ({@link #beforeHud}): the composited frame is kept as the HUD-less picture and the window target's
 *   alpha is cleared, so the HUD's own blending leaves its coverage there;</li>
 *   <li>frame end ({@link #endFrame}): the inputs are drawn into the shared textures — the frame, the HUD-less picture,
 *   the HUD alone (premultiplied) and, at the render size, depth and motion vectors drawn exactly as for Super Resolution —
 *   and presented.</li>
 * </ul>
 * Menus that pause the game, loading screens and frames without a world present without generation. Anything failing
 * switches Frame Generation off for the session (OpenGL presents again) with the reason on the 画质 tab.
 */
public final class FrameGenerator implements StreamlineHost.Client {
    private static final RenderPipeline FRAME = Fullscreen.pipeline("gfx_fg_frame").withSampler("InSampler").build();
    private static final RenderPipeline HUDLESS = Fullscreen.pipeline("gfx_fg_hudless")
            .withSampler("FrameSampler").withSampler("SceneSampler").build();
    private static final RenderPipeline UI = Fullscreen.pipeline("gfx_fg_ui").withSampler("FrameSampler").withSampler("SceneSampler").build();
    private static final RenderPipeline ALPHA_CLEAR = Fullscreen.pipeline("gfx_alpha_clear").withColorWrite(false, true).build();
    /** The alpha the final pass writes without a HUD capture (1), written alone. */
    private static final RenderPipeline ALPHA_ONE = Fullscreen.pipeline("gfx_alpha_one").withColorWrite(false, true).build();

    @Nullable
    private FrameGeneration presenter;
    @Nullable
    private BridgeTexture frame, hudless, ui, depth, motion;
    private final RenderTexture scene = new RenderTexture("BlockLand frame generation HUD-less", RenderTexture.Format.RGBA8, FilterMode.NEAREST);
    private final RenderTexture still = new RenderTexture("BlockLand frame generation no motion", RenderTexture.Format.RG16F, FilterMode.NEAREST);
    /** Made on first use (the generator itself exists before the render system does). */
    @Nullable
    private Ubo ubo;
    private int reflexMode;
    private boolean hudCaptured, generating;
    /** Frame Generation's runtime files missing from the game directory (re-checked every few seconds). */
    private List<String> missing = List.of();
    private long checkedAt;

    /**
     * Frame start: brings the presenter up or down for this frame and fits it to the sizes.
     *
     * @param running the pipeline runs this frame (a world is shown and the pipeline is switched on)
     * @return generation may happen this frame (the caller halves its motion blur)
     */
    public boolean beginFrame(boolean running, int outW, int outH, int renderW, int renderH) {
        hudCaptured = false;
        GraphicsSettings settings = Graphics.settings();
        boolean wanted = running && settings.frameGeneration > 1 && StreamlineHost.frameGenFailure() == null && DlssUpscaler.probe().available()
                && missing().isEmpty();
        if (!wanted) {
            if (presenter != null) close();
            generating = false;
            return false;
        }
        try {
            if (presenter == null) open();
            int mode = settings.reflexBoost() ? 2 : 1;
            if (mode != reflexMode) {
                presenter.reflex(mode);
                reflexMode = mode;
            }
            if (!presenter.matches(outW, outH, renderW, renderH)) {
                // the wrappers delete their GL names: they go first, before new textures can be given the same names
                releaseWrappers();
                presenter.resize(outW, outH, renderW, renderH);
                wrap(outW, outH, renderW, renderH);
            }
            presenter.beginFrame();
            // OpenGL's own swap must not wait for v-sync: its back buffer is covered and DLSS paces the frames
            GLFW.glfwSwapInterval(0);
        } catch (DlssException e) {
            fail(e);
        } catch (RuntimeException e) {
            fail(new DlssException("blockland.graphics.status.fg.failed", e.toString(), e));
        }
        Minecraft mc = Minecraft.getInstance();
        generating = presenter != null && (mc.screen == null || !mc.screen.isPauseScreen());
        return generating;
    }

    private void open() {
        SlRuntime runtime = StreamlineHost.acquire(this, true);
        if (!runtime.frameGeneration()) {
            DlssException failure = StreamlineHost.frameGenFailure();
            throw failure != null ? failure : new DlssException("blockland.graphics.status.fg.failed", "no frame generation in the runtime");
        }
        long window = GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().getWindow());
        reflexMode = Graphics.settings().reflexBoost() ? 2 : 1;
        presenter = FrameGeneration.create(runtime, window, reflexMode);
        BlockLand.LOGGER.info("[BlockLand] DLSS Frame Generation presents through Vulkan");
    }

    /**
     * The finished frame is about to get its HUD: keep it as the HUD-less picture and let the HUD write its coverage. The
     * post chain's final pass may have left the alpha at 0 for this already (POST_DEAD_WORK,
     * {@link PostChain#takeCoverageCleared}).
     */
    public void beforeHud(GpuTextureView main, int outW, int outH) {
        boolean cleared = PostChain.takeCoverageCleared(main);
        if (presenter == null || !generating) {
            // switched off since the frame started (a failure, the runtime given away): no capture, the usual alpha
            if (cleared) Fullscreen.pass("BlockLand frame generation: restore alpha", main, ALPHA_ONE).draw();
            return;
        }
        scene.ensure(outW, outH);
        PostChain.copy(main, scene.view());
        if (!cleared) Fullscreen.pass("BlockLand frame generation: clear HUD alpha", main, ALPHA_CLEAR).draw();
        hudCaptured = true;
    }

    /**
     * Frame end (before Minecraft's own blit and swap): draws the inputs and presents.
     *
     * @param main         the window's target with the finished frame and its HUD
     * @param decoded      the post chain's decoded colour (hand pixels flagged in alpha), or null
     * @param reprojection current unjittered NDC → previous clip, or null when this was no game frame
     */
    public void endFrame(GpuTextureView main, FrameContext ctx, @Nullable GpuTextureView decoded, @Nullable Matrix4f reprojection,
                         boolean historyValid) {
        if (presenter == null) return;
        try {
            Fullscreen.pass("BlockLand frame generation: frame", frame.view, FRAME).sampler("InSampler", main).draw();
            boolean game = generating && hudCaptured && decoded != null && reprojection != null && ctx.worldDepth != null;
            DlssBridge.Frame camera = null;
            if (game) {
                Fullscreen.pass("BlockLand frame generation: HUD-less", hudless.view, HUDLESS)
                        .sampler("FrameSampler", main).sampler("SceneSampler", scene.view()).draw();
                Fullscreen.pass("BlockLand frame generation: UI", ui.view, UI)
                        .sampler("FrameSampler", main).sampler("SceneSampler", scene.view()).draw();
                int rw = ctx.internalWidth, rh = ctx.internalHeight;
                Fullscreen.pass("BlockLand frame generation: depth", depth.view, DlssUpscaler.DEPTH)
                        .sampler("ColorSampler", decoded).sampler("WorldDepthSampler", ctx.worldDepth).draw();
                if (ubo == null) ubo = Ubo.pass("frame generation motion");
                ubo.pass(reprojection, rw, rh, 1f / rw, 1f / rh);
                Fullscreen.pass("BlockLand frame generation: motion", motion.view, DlssUpscaler.MOTION)
                        .sampler("ColorSampler", decoded).sampler("WorldDepthSampler", ctx.worldDepth)
                        .sampler("HandVelocitySampler", ctx.handVelocity != null ? ctx.handVelocity : still()).uniform(ubo).draw();
                camera = DlssUpscaler.parameters(ctx, reprojection, historyValid);
            }
            presenter.present(Graphics.settings().frameGeneration, camera);
        } catch (DlssException e) {
            fail(e);
        } catch (RuntimeException e) {
            fail(new DlssException("blockland.graphics.status.fg.failed", e.toString(), e));
        }
    }

    /** What Frame Generation can do on this machine and what it does now (every frame, also while it is off). */
    public void report(GraphicsStatus status) {
        DlssException failure = StreamlineHost.frameGenFailure();
        DlssUpscaler.Probe dlss = DlssUpscaler.probe();
        List<String> missing = missing();
        if (failure != null) {
            status.frameGenAvailable = false;
            status.frameGenReason = failure.reason;
            status.frameGenDetail = failure.detail;
        } else if (!dlss.available()) {
            status.frameGenAvailable = false;
            status.frameGenReason = dlss.reason();
            status.frameGenDetail = dlss.detail();
        } else if (!missing.isEmpty()) {
            status.frameGenAvailable = false;
            status.frameGenReason = "blockland.graphics.status.fg.no_runtime";
            status.frameGenDetail = String.join(", ", missing);
        } else {
            status.frameGenAvailable = true;
            status.frameGenReason = null;
            status.frameGenDetail = "";
        }
        status.frameGenPresenting = presenter != null;
        if (presenter != null) {
            FrameGeneration.State state = presenter.state();
            status.frameGenActive = state.generating();
            if (state.maxFrames() > 0) status.frameGenMax = state.maxFrames() + 1;
            status.frameGenPresented = state.presented();
            if (state.status() != 0) {
                status.frameGenReason = "blockland.graphics.status.fg.state";
                status.frameGenDetail = describe(state.status());
            }
        } else {
            status.frameGenActive = false;
            status.frameGenPresented = 1;
        }
    }

    private List<String> missing() {
        long now = System.nanoTime();
        if (checkedAt == 0 || now - checkedAt > 5_000_000_000L) {
            missing = SlRuntime.missing(StreamlineHost.runtimeFolder(), SlRuntime.FRAME_GENERATION_FILES);
            checkedAt = now;
        }
        return missing;
    }

    private static String describe(int bits) {
        StringBuilder text = new StringBuilder();
        String[] names = {"resolution too low", "Reflex not detected", "HDR format", "constants invalid", "back buffer index"};
        for (int i = 0; i < names.length; i++) if ((bits & (1 << i)) != 0) text.append(text.isEmpty() ? "" : ", ").append(names[i]);
        return text.isEmpty() ? "status " + bits : text.toString();
    }

    private void wrap(int outW, int outH, int renderW, int renderH) {
        releaseWrappers();
        frame = new BridgeTexture("BlockLand FG frame", presenter.colorTexture(), outW, outH);
        hudless = new BridgeTexture("BlockLand FG HUD-less", presenter.hudlessTexture(), outW, outH);
        ui = new BridgeTexture("BlockLand FG UI", presenter.uiTexture(), outW, outH);
        depth = new BridgeTexture("BlockLand FG depth", presenter.depthTexture(), renderW, renderH);
        motion = new BridgeTexture("BlockLand FG motion", presenter.motionTexture(), renderW, renderH);
    }

    private void releaseWrappers() {
        for (BridgeTexture texture : new BridgeTexture[]{frame, hudless, ui, depth, motion}) if (texture != null) texture.close();
        frame = hudless = ui = depth = motion = null;
    }

    /** A 1×1 zero motion texture for frames without the viewmodel's motion. */
    private GpuTextureView still() {
        if (still.ensure(1, 1)) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(still.texture(), 0);
        return still.view();
    }

    private void fail(DlssException e) {
        StreamlineHost.failFrameGeneration(e);
        BlockLand.LOGGER.error("[BlockLand] DLSS Frame Generation switched off for this session: {}", e.getMessage(), e);
        close();
    }

    @Override
    public void releaseRuntime() {
        closePresenter();
    }

    /** Back to OpenGL presenting: the child window goes, v-sync is Minecraft's again. */
    private void closePresenter() {
        releaseWrappers();
        if (presenter != null) {
            try {
                presenter.close();
            } catch (RuntimeException e) {
                BlockLand.LOGGER.warn("[BlockLand] Closing DLSS Frame Generation failed: {}", e.toString());
            }
            presenter = null;
            Minecraft mc = Minecraft.getInstance();
            mc.getWindow().updateVsync(mc.options.enableVsync().get());
        }
        generating = false;
        reflexMode = 0;
    }

    public void close() {
        closePresenter();
        StreamlineHost.release(this);
        scene.close();
        still.close();
    }
}
