package com.blockland.client.graphics.upscale;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.GraphicsStatus;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.graphics.upscale.dlss.DlssException;
import com.blockland.client.graphics.upscale.dlss.NeuralRendering;
import com.blockland.client.graphics.upscale.dlss.SlRuntime;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.nio.file.Files;

/**
 * DLSS 5 3D-guided neural rendering in the post chain (rendering spec §4), around {@link NeuralRendering}: right after the
 * upscaler — at the output size, before motion blur, bloom and tone mapping, where RTX Remix runs it — the linear-HDR
 * picture goes into SDR (identity below a knee, a soft shoulder above: {@code gfx_sdr}), the model relights it from the
 * depth and motion vectors Super Resolution uses, and the exact inverse brings it back to HDR (with RTX Remix's highlight
 * recovery). The control mask is uniform (the whole picture, the first-person weapon included); the strengths, the model
 * and NVIDIA's automatic character mask are the player's settings. Anything failing switches it off for the session with
 * the reason on the 画质 tab.
 */
public final class NeuralRenderer implements StreamlineHost.Client {
    private static final RenderPipeline INPUT = Fullscreen.pipeline("gfx_nr_input").withSampler("InSampler").build();
    /** The scene's depth with the hand and the weapon in it (the model is 3D-guided: it needs their shape). */
    private static final RenderPipeline DEPTH = Fullscreen.pipeline("gfx_nr_depth").withSampler("DepthSampler").build();
    private static final RenderPipeline OUTPUT = Fullscreen.pipeline("gfx_nr_output")
            .withSampler("OutSampler").withSampler("OriginalSampler").build();
    private static final Matrix4f NONE = new Matrix4f();
    private static final long RECHECK_NANOS = 5_000_000_000L;

    @Nullable
    private NeuralRendering bridge;
    @Nullable
    private BridgeTexture color, output, depth, motion, mask;
    private final RenderTexture result = new RenderTexture("BlockLand DLSS 5 result", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    private final RenderTexture still = new RenderTexture("BlockLand DLSS 5 no motion", RenderTexture.Format.RG16F, FilterMode.NEAREST);
    @Nullable
    private Ubo inputUbo, motionUbo, outputUbo;
    /** Why neural rendering failed this session (not retried until the game restarts), or null. */
    @Nullable
    private static DlssException failure;
    private boolean history, maskDirty, active;
    private boolean installed;
    private long checkedAt;

    /**
     * Relights this frame's upscaled picture, or returns it as it is (switched off, unavailable, no world depth).
     *
     * @param frame      the resolve's inputs (decoded colour with the hand flag in alpha, world depth, reprojection)
     * @param color      the upscaler's result: linear HDR at the output size, hand mask in alpha
     * @param sceneDepth the scene's depth after the hand phase (internal resolution)
     */
    public GpuTextureView apply(Upscaler.Frame frame, GpuTextureView color, GpuTextureView sceneDepth) {
        GraphicsSettings settings = Graphics.settings();
        active = false;
        if (!settings.neuralRendering || failure != null || frame.worldDepth() == null) {
            if (bridge != null && !settings.neuralRendering) close();
            return color;
        }
        try {
            if (bridge == null) {
                bridge = NeuralRendering.on(StreamlineHost.acquire(this, false));
                BlockLand.LOGGER.info("[BlockLand] DLSS 5 neural rendering ready");
            }
            FrameContext ctx = frame.context();
            int ow = ctx.outputWidth, oh = ctx.outputHeight, rw = ctx.internalWidth, rh = ctx.internalHeight;
            if (!bridge.matches(ow, oh, rw, rh)) {
                // the wrappers delete their GL names: they go first, before new textures can be given the same names
                releaseWrappers();
                bridge.resize(ow, oh, rw, rh);
                wrap(ow, oh, rw, rh);
                history = false;
                maskDirty = true;
            }
            if (maskDirty) {
                // the whole picture, at full strength in every channel (R overall, G tone, B structure)
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(mask.texture, 0xFFFFFFFF);
                maskDirty = false;
            }
            if (inputUbo == null) {
                inputUbo = Ubo.pass("DLSS 5 input");
                motionUbo = Ubo.pass("DLSS 5 motion");
                outputUbo = Ubo.pass("DLSS 5 output");
            }
            float exposure = (float) Math.pow(2, settings.exposure);
            inputUbo.pass(NONE, exposure);
            Fullscreen.pass("BlockLand DLSS 5: input", this.color.view, INPUT).sampler("InSampler", color).uniform(inputUbo).draw();
            Fullscreen.pass("BlockLand DLSS 5: depth", depth.view, DEPTH).sampler("DepthSampler", sceneDepth).draw();
            motionUbo.pass(frame.reprojection(), rw, rh, 1f / rw, 1f / rh);
            Fullscreen.pass("BlockLand DLSS 5: motion", motion.view, DlssUpscaler.MOTION)
                    .sampler("ColorSampler", frame.color()).sampler("WorldDepthSampler", frame.worldDepth())
                    .sampler("HandVelocitySampler", ctx.handVelocity != null ? ctx.handVelocity : still()).uniform(motionUbo).draw();

            // the jitter as DLSS wants it: the projection's shift of the picture in render pixels, rows running down
            float jitterX = ctx.jitterX * rw * 0.5f, jitterY = -ctx.jitterY * rh * 0.5f;
            bridge.evaluate(new NeuralRendering.Settings(settings.neuralIntensity, settings.neuralTone, settings.neuralStructure,
                    settings.neuralModel, settings.neuralAutoMask, settings.neuralCharacters), jitterX, jitterY, !(history && frame.historyValid()));
            history = true;

            result.ensure(ow, oh);
            outputUbo.pass(NONE, exposure, settings.neuralHighlightRecovery ? 1 : 0);
            Fullscreen.pass("BlockLand DLSS 5: output", result.view(), OUTPUT)
                    .sampler("OutSampler", output.view).sampler("OriginalSampler", color).uniform(outputUbo).draw();
            active = true;
            return result.view();
        } catch (DlssException e) {
            fail(e);
        } catch (RuntimeException e) {
            fail(new DlssException("blockland.graphics.status.nr.failed", e.toString(), e));
        }
        return color;
    }

    /** What DLSS 5 can do here and whether it relit the last frame (every frame, also while it is off). */
    public void report(GraphicsStatus status) {
        long now = System.nanoTime();
        if (checkedAt == 0 || now - checkedAt > RECHECK_NANOS) {
            installed = Files.isRegularFile(StreamlineHost.runtimeFolder().resolve(SlRuntime.NEURAL_RENDERING_FILE));
            checkedAt = now;
        }
        DlssUpscaler.Probe dlss = DlssUpscaler.probe();
        if (failure != null) {
            status.neuralAvailable = false;
            status.neuralReason = failure.reason;
            status.neuralDetail = failure.detail;
        } else if (!dlss.available()) {
            status.neuralAvailable = false;
            status.neuralReason = dlss.reason();
            status.neuralDetail = dlss.detail();
        } else if (!installed) {
            status.neuralAvailable = false;
            status.neuralReason = "blockland.graphics.status.nr.no_runtime";
            status.neuralDetail = SlRuntime.NEURAL_RENDERING_FILE;
        } else {
            status.neuralAvailable = true;
            status.neuralReason = null;
            status.neuralDetail = "";
        }
        status.neuralActive = active;
    }

    private void wrap(int ow, int oh, int rw, int rh) {
        releaseWrappers();
        color = new BridgeTexture("BlockLand DLSS 5 colour", bridge.colorTexture(), ow, oh);
        output = new BridgeTexture("BlockLand DLSS 5 output", bridge.outputTexture(), ow, oh);
        depth = new BridgeTexture("BlockLand DLSS 5 depth", bridge.depthTexture(), rw, rh);
        motion = new BridgeTexture("BlockLand DLSS 5 motion", bridge.motionTexture(), rw, rh);
        mask = new BridgeTexture("BlockLand DLSS 5 mask", bridge.maskTexture(), rw, rh);
    }

    private void releaseWrappers() {
        for (BridgeTexture texture : new BridgeTexture[]{color, output, depth, motion, mask}) if (texture != null) texture.close();
        color = output = depth = motion = mask = null;
    }

    /** A 1×1 zero motion texture for frames without the viewmodel's motion. */
    private GpuTextureView still() {
        if (still.ensure(1, 1)) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(still.texture(), 0);
        return still.view();
    }

    private void fail(DlssException e) {
        failure = e;
        BlockLand.LOGGER.error("[BlockLand] DLSS 5 neural rendering switched off for this session: {}", e.getMessage(), e);
        close();
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
            } catch (RuntimeException e) {
                BlockLand.LOGGER.warn("[BlockLand] Closing DLSS 5 neural rendering failed: {}", e.toString());
            }
            bridge = null;
        }
        history = false;
        active = false;
    }

    public void close() {
        closeBridge();
        StreamlineHost.release(this);
        result.close();
        still.close();
    }
}
