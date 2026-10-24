package com.blockland.client.graphics.upscale.dlss;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;

/**
 * NVIDIA DLSS Super Resolution for an OpenGL renderer (rendering spec §4), on a {@link SlRuntime}: the four textures DLSS
 * works on live in Vulkan memory OpenGL imports, and frames are handed across with a pair of shared semaphores. Per frame
 * the caller draws the inputs into the GL side of {@link #colorTexture()}, {@link #depthTexture()} and
 * {@link #motionTexture()}, calls {@link #evaluate}, and reads {@link #outputTexture()}; GL and Vulkan never wait on the
 * CPU for each other.
 * <p>
 * The model is whatever the runtime's {@code nvngx_dlss.dll} (or the driver's DLSS override) brings, so a newer one upgrades
 * it without touching this code. Depends only on LWJGL, so it can be tested outside the game ({@code tools/dlss}). Render
 * thread (the GL context's thread) only.
 */
public final class DlssBridge implements AutoCloseable {
    /** DLSS quality modes (Streamline's DLSSMode values). */
    public enum Mode {
        DLAA(6), QUALITY(3), BALANCED(2), PERFORMANCE(1), ULTRA_PERFORMANCE(4);

        final int sl;

        Mode(int sl) {
            this.sl = sl;
        }
    }

    /**
     * Everything DLSS needs about one frame besides the textures. Matrices are row-major for row vectors (an OpenGL
     * column-vector matrix written out column by column), without jitter; DLSS Frame Generation uses them too (its scene
     * change detection reads the camera vectors).
     *
     * @param jitterX       sub-pixel jitter of this frame in render pixels, in the textures' row space (see {@code tools/dlss})
     * @param motionScaleX  factor from motion vector texels to normalised [-1, 1] screen units (1 / render width for pixels)
     * @param reset         the history is unusable (camera cut, new level)
     * @param fovRadians    vertical field of view
     */
    public record Frame(float jitterX, float jitterY, float motionScaleX, float motionScaleY, boolean reset,
                        float[] viewToClip, float[] clipToView, float[] clipToPrevClip, float[] prevClipToClip,
                        float[] cameraPos, float[] cameraUp, float[] cameraRight, float[] cameraForward,
                        float near, float far, float fovRadians, float aspect) {
    }

    private static final int GL_RGBA16F = 0x881A, GL_RG16F = 0x822F, GL_R32F = 0x822E;
    private static final int USAGE = Vk.USAGE_SAMPLED | Vk.USAGE_STORAGE | Vk.USAGE_COLOR_ATTACHMENT | Vk.USAGE_TRANSFER_SRC | Vk.USAGE_TRANSFER_DST;

    private final SlRuntime runtime;
    /** The bridge brought its runtime up itself (the standalone check) and takes it down with it. */
    private final boolean ownsRuntime;
    private final long commandPool, commandBuffer, fence;
    private final SlRuntime.Handoff handoff;
    private SlRuntime.SharedImage color, depth, motion, output;
    /** Ray Reconstruction's guides at the render size (made on first use): diffuse albedo, specular albedo, normal + roughness. */
    private SlRuntime.SharedImage albedo, specular, normals;
    private boolean optionsReconstruction;
    private int renderWidth, renderHeight, outputWidth, outputHeight;
    private Mode optionsMode;
    private int optionsPreset;
    private int optionsWidth, optionsHeight;
    private boolean closed;

    private DlssBridge(SlRuntime runtime, boolean ownsRuntime) {
        this.runtime = runtime;
        this.ownsRuntime = ownsRuntime;
        commandPool = runtime.commandPool();
        commandBuffer = runtime.commandBuffer(commandPool);
        fence = runtime.fence();
        handoff = runtime.handoff();
    }

    /** DLSS on a runtime the caller owns. */
    public static DlssBridge on(SlRuntime runtime) {
        return new DlssBridge(runtime, false);
    }

    /**
     * Brings up a runtime of its own (Super Resolution only) for the current GL context.
     *
     * @param runtime       folder with sl.interposer.dll, sl.common.dll, sl.dlss.dll and nvngx_dlss.dll
     * @param logs          folder for Streamline's log
     * @param engineVersion the host's version string (NGX identifies custom engines by it and a project id)
     * @throws DlssException with the reason when anything is missing or refuses
     */
    public static DlssBridge create(Path runtime, Path logs, String engineVersion) {
        SlRuntime sl = SlRuntime.create(runtime, logs, engineVersion, false);
        try {
            return new DlssBridge(sl, true);
        } catch (RuntimeException e) {
            sl.close();
            throw e;
        }
    }

    /** "GPU name · SL x / DLSS y" for the status line. */
    public String description() {
        return runtime.description();
    }

    /** The render size DLSS wants for a mode at this output size: {width, height}. */
    public int[] optimalRenderSize(Mode mode, int outWidth, int outHeight) {
        int[] size = runtime.sl.optimalSize(mode.sl, outWidth, outHeight);
        if (size[0] <= 0 || size[1] <= 0) {
            throw new DlssException("blockland.graphics.status.dlss.streamline", "no render size for " + mode + " at " + outWidth + "x" + outHeight);
        }
        return size;
    }

    // ---- textures ---------------------------------------------------------------------------------------------------

    /** The shared textures exist at exactly these sizes ({@link #resize} would keep them). */
    public boolean matches(int renderW, int renderH, int outW, int outH) {
        return renderW == renderWidth && renderH == renderHeight && outW == outputWidth && outH == outputHeight && color != null;
    }

    /**
     * Makes sure the shared textures have these sizes ({@code true} when they were re-created: their GL ids change and
     * DLSS starts without history).
     */
    public boolean resize(int renderW, int renderH, int outW, int outH) {
        if (matches(renderW, renderH, outW, outH)) return false;
        runtime.waitIdle();
        runtime.sl.freeResources(runtime.viewport);
        destroyImages();
        renderWidth = renderW;
        renderHeight = renderH;
        outputWidth = outW;
        outputHeight = outH;
        color = runtime.image("colour", renderW, renderH, Vk.FORMAT_R16G16B16A16_SFLOAT, GL_RGBA16F, USAGE);
        depth = runtime.image("depth", renderW, renderH, Vk.FORMAT_R32_SFLOAT, GL_R32F, USAGE);
        motion = runtime.image("motion", renderW, renderH, Vk.FORMAT_R16G16_SFLOAT, GL_RG16F, USAGE);
        output = runtime.image("output", outW, outH, Vk.FORMAT_R16G16B16A16_SFLOAT, GL_RGBA16F, USAGE);
        runtime.initialLayouts(commandBuffer, fence, handoff, color, depth, motion, output);
        optionsMode = null;
        return true;
    }

    /** Ray Reconstruction is loaded and supported on the runtime. */
    public boolean rayReconstructionAvailable() {
        return runtime.rayReconstruction();
    }

    /**
     * Makes sure Ray Reconstruction's guide textures exist at the render size ({@code true} when they were made: their GL
     * ids are new). Call after {@link #resize}.
     */
    public boolean ensureGuides() {
        if (albedo != null) return false;
        runtime.waitIdle();
        albedo = runtime.image("RR albedo", renderWidth, renderHeight, Vk.FORMAT_R16G16B16A16_SFLOAT, GL_RGBA16F, USAGE);
        specular = runtime.image("RR specular albedo", renderWidth, renderHeight, Vk.FORMAT_R16G16B16A16_SFLOAT, GL_RGBA16F, USAGE);
        normals = runtime.image("RR normals", renderWidth, renderHeight, Vk.FORMAT_R16G16B16A16_SFLOAT, GL_RGBA16F, USAGE);
        runtime.initialLayouts(commandBuffer, fence, handoff, albedo, specular, normals);
        return true;
    }

    /** GL texture for Ray Reconstruction's diffuse albedo (RGBA16F linear, render size, rows flipped). */
    public int albedoTexture() {
        return albedo.glTexture;
    }

    /** GL texture for Ray Reconstruction's specular albedo (RGBA16F linear, render size, rows flipped). */
    public int specularTexture() {
        return specular.glTexture;
    }

    /** GL texture for Ray Reconstruction's normals (world, xyz) with linear roughness in alpha (render size, rows flipped). */
    public int normalTexture() {
        return normals.glTexture;
    }

    /** GL texture the caller draws the jittered linear-HDR colour into (RGBA16F, render size). */
    public int colorTexture() {
        return color.glTexture;
    }

    /** GL texture the caller draws hardware depth into (R32F, 0 near … 1 far, render size). */
    public int depthTexture() {
        return depth.glTexture;
    }

    /** GL texture the caller draws motion vectors into (RG16F, render size; see {@link Frame#motionScaleX}). */
    public int motionTexture() {
        return motion.glTexture;
    }

    /** GL texture DLSS writes the upscaled colour into (RGBA16F, output size). */
    public int outputTexture() {
        return output.glTexture;
    }

    public int renderWidth() {
        return renderWidth;
    }

    public int renderHeight() {
        return renderHeight;
    }

    // ---- per frame --------------------------------------------------------------------------------------------------

    /**
     * Upscales this frame: the inputs must be drawn; afterwards GL commands may read the output. Sets the frame's common
     * constants; when OpenGL presents (no {@link FrameGeneration}), this also ends the runtime's frame.
     *
     * @param preset Streamline's DLSSPreset (0 = NVIDIA's default per mode, 10..13 = presets J, K, L, M)
     */
    public void evaluate(Mode mode, int preset, Frame frame) {
        evaluate(mode, preset, frame, null, null);
    }

    /**
     * As {@link #evaluate(Mode, int, Frame)}; with the camera's world-to-view and view-to-world matrices (row-major, see
     * {@link Frame}) it runs DLSS Ray Reconstruction instead, which denoises the frame's ray traced light while it upscales
     * (the guides must be drawn: {@link #ensureGuides}).
     */
    public void evaluate(Mode mode, int preset, Frame frame, float[] worldToView, float[] viewToWorld) {
        if (color == null) throw new IllegalStateException("evaluate before resize");
        Streamline sl = runtime.sl;
        boolean reconstruction = worldToView != null && albedo != null;
        if (reconstruction) {
            // the matrices may change every frame: Ray Reconstruction's options go out each time
            sl.rayReconstructionOptions(runtime.viewport, mode.sl, outputWidth, outputHeight, worldToView, viewToWorld);
            optionsMode = null;
        } else if (mode != optionsMode || preset != optionsPreset || optionsWidth != outputWidth || optionsHeight != outputHeight
                || optionsReconstruction) {
            sl.setOptions(runtime.viewport, mode.sl, preset, outputWidth, outputHeight);
            optionsMode = mode;
            optionsPreset = preset;
            optionsWidth = outputWidth;
            optionsHeight = outputHeight;
        }
        optionsReconstruction = reconstruction;
        SlRuntime.SharedImage[] all = reconstruction
                ? new SlRuntime.SharedImage[]{color, depth, motion, output, albedo, specular, normals}
                : new SlRuntime.SharedImage[]{color, depth, motion, output};
        // GL → Vulkan: the inputs are written
        handoff.glSignal(all);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            runtime.await(fence);
            runtime.begin(stack, commandBuffer);
            runtime.handBarriers(stack, commandBuffer, true, all);

            long token = runtime.frameToken();
            runtime.constants(frame);
            SlRuntime.SharedImage[] tagged = reconstruction
                    ? new SlRuntime.SharedImage[]{depth, motion, color, output, albedo, specular, normals}
                    : new SlRuntime.SharedImage[]{depth, motion, color, output};
            int[] types = {Streamline.BUFFER_DEPTH, Streamline.BUFFER_MOTION_VECTORS, Streamline.BUFFER_SCALING_INPUT,
                    Streamline.BUFFER_SCALING_OUTPUT, Streamline.BUFFER_ALBEDO, Streamline.BUFFER_SPECULAR_ALBEDO,
                    Streamline.BUFFER_NORMAL_ROUGHNESS};
            long resources = MemoryUtil.memAddress(stack.calloc(8, Streamline.RESOURCE_SIZE * tagged.length));
            long tags = MemoryUtil.memAddress(stack.calloc(8, Streamline.TAG_SIZE * tagged.length));
            for (int i = 0; i < tagged.length; i++) {
                long resource = resources + (long) i * Streamline.RESOURCE_SIZE;
                tagged[i].resource(resource);
                Streamline.tag(tags + (long) i * Streamline.TAG_SIZE, resource, types[i]);
            }
            sl.setTags(token, runtime.viewport, tags, tagged.length, commandBuffer);
            sl.evaluate(reconstruction ? Streamline.FEATURE_DLSS_RR : Streamline.FEATURE_DLSS, token, runtime.viewport, commandBuffer);

            runtime.handBarriers(stack, commandBuffer, false, all);
            Vk.check(runtime.vk.endCommandBuffer.callInt(commandBuffer), "vkEndCommandBuffer");
            runtime.submit(stack, commandBuffer, new long[]{handoff.vkReady}, new int[]{Vk.STAGE_ALL_COMMANDS},
                    new long[]{handoff.vkDone}, fence);
        }
        if (!runtime.presenting()) runtime.nextFrame(false);
        // Vulkan → GL: DLSS is done (the GL side waits on the GPU, not here)
        handoff.glWait(all);
    }

    private void destroyImages() {
        for (SlRuntime.SharedImage image : new SlRuntime.SharedImage[]{color, depth, motion, output, albedo, specular, normals}) {
            if (image != null) image.destroy();
        }
        color = depth = motion = output = albedo = specular = normals = null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            runtime.waitIdle();
            runtime.sl.freeResources(runtime.viewport);
            if (runtime.rayReconstruction()) runtime.sl.freeResources(Streamline.FEATURE_DLSS_RR, runtime.viewport);
            destroyImages();
            handoff.destroy();
            runtime.vk.destroyFence.call(runtime.device, fence, 0);
            runtime.vk.destroyCommandPool.call(runtime.device, commandPool, 0);
        } finally {
            if (ownsRuntime) runtime.close();
        }
    }
}
