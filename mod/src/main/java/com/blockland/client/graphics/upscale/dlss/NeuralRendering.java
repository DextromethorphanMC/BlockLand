package com.blockland.client.graphics.upscale.dlss;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * DLSS 5 3D-guided neural rendering (DLSS-NR) for an OpenGL renderer (rendering spec §4), through NGX directly on the
 * {@link SlRuntime}'s Vulkan device — the way RTX Remix integrates it; Streamline does not wrap it. The model relights the
 * finished, upscaled picture (structure: contact shadows, ambient occlusion, materials; tone: lightness and colour) from
 * its colour, depth and motion vectors, steered by a per-pixel control mask.
 * <p>
 * Per frame the caller draws, upside down like every Vulkan-side image, the picture tone-mapped into SDR (the model works
 * in SDR) into {@link #colorTexture()} at the output size, and at the render size the depth, the motion vectors (the
 * Super Resolution conventions: render pixels, previous − current) and the control mask (R overall, G tone, B structure,
 * 0..1); {@link #evaluate} runs the model into {@link #outputTexture()}. Needs NVIDIA's {@code nvngx_dlssnr.dll} next to
 * the Streamline runtime, an RTX 50 GPU and a driver that supports DLSS-NR ({@link #capability()} says which is missing).
 * Render thread only.
 */
public final class NeuralRendering implements AutoCloseable {
    /**
     * @param available    the driver and GPU run DLSS-NR
     * @param needsDriver  the driver is too old ({@code minMajor}.{@code minMinor} is the oldest that works; 0.0 when the
     *                     driver predates DLSS-NR altogether and does not say)
     * @param initResult   NGX's feature initialisation result when it is not available (0 when unknown)
     */
    public record Capability(boolean available, boolean needsDriver, int minMajor, int minMinor, int initResult) {
    }

    /**
     * What a user adjusts (RTX Remix's DLSS-NR options).
     *
     * @param intensity  overall effect, 0..1
     * @param tone       adjustments to lightness, darkness and colour, 0..1
     * @param structure  adjustments to details, shadows and materials, 0..1
     * @param model      appearance model: 0 A, 1 B, 2 C
     * @param autoMask   combine NVIDIA's automatic character mask with the control mask
     * @param skin       structure adjustments on characters (with the automatic mask), 0..1
     */
    public record Settings(float intensity, float tone, float structure, int model, boolean autoMask, float skin) {
    }

    private static final int GL_RGBA16F = 0x881A, GL_RG16F = 0x822F, GL_R32F = 0x822E, GL_RGBA8 = 0x8058;
    private static final int USAGE = Vk.USAGE_SAMPLED | Vk.USAGE_STORAGE | Vk.USAGE_COLOR_ATTACHMENT | Vk.USAGE_TRANSFER_SRC | Vk.USAGE_TRANSFER_DST;
    private static final int RESOURCE_SIZE = 56;
    /** DLSSNR render presets: 0 the SDK's default network, 1..7 = A..G (RTX Remix runs B, the lightweight one). */
    public static final int PRESET_DEFAULT = 0, PRESET_B = 2;
    private static final int MASK_BLEND_NONE = 0, MASK_BLEND_MIN = 2;
    /** The oldest driver RTX Remix's DLSS-NR release asks for (older drivers do not report a minimum). */
    public static final String MIN_DRIVER = "616.92";

    private final SlRuntime rt;
    private final Ngx ngx;
    private final Ngx.Params params;
    private final long commandPool, commandBuffer, fence;
    private final SlRuntime.Handoff handoff;
    private SlRuntime.SharedImage color, output, depth, motion, mask;
    private long feature;
    private int preset = PRESET_B;
    private boolean closed;

    private NeuralRendering(SlRuntime rt) {
        this.rt = rt;
        this.ngx = rt.ngx();
        Capability capability = capability();
        if (!capability.available()) {
            throw capability.needsDriver()
                    ? new DlssException("blockland.graphics.status.nr.driver", capability.minMajor() > 0
                    ? capability.minMajor() + "." + capability.minMinor() : MIN_DRIVER)
                    : new DlssException("blockland.graphics.status.nr.unsupported", "NGX " + Ngx.result(capability.initResult()));
        }
        params = ngx.allocate();
        commandPool = rt.commandPool();
        commandBuffer = rt.commandBuffer(commandPool);
        fence = rt.fence();
        handoff = rt.handoff();
    }

    /**
     * DLSS-NR on a runtime (NGX is brought up on its device on first use).
     *
     * @throws DlssException why it cannot run (no model file, an older driver, a GPU without it)
     */
    public static NeuralRendering on(SlRuntime runtime) {
        return new NeuralRendering(runtime);
    }

    /** What the driver says about DLSS-NR on this GPU (NGX initialised on the runtime). */
    public Capability capability() {
        return capability(ngx);
    }

    /** The NGX parameter block's vtable layout that was recognised (for logs and checks). */
    public static String parameterLayout(SlRuntime runtime) {
        return runtime.ngx().layout();
    }

    /** As {@link #capability()}, without making the bridge (for the 画质 tab's status). */
    public static Capability capability(SlRuntime runtime) {
        return capability(runtime.ngx());
    }

    private static Capability capability(Ngx ngx) {
        Ngx.Params caps = ngx.capabilities();
        try {
            int needsDriver = caps.getI("DLSSNR.NeedsUpdatedDriver", 0);
            int major = caps.getUI("DLSSNR.MinDriverVersionMajor", 0), minor = caps.getUI("DLSSNR.MinDriverVersionMinor", 0);
            int available = caps.getI("DLSSNR.Available", -1);
            int init = caps.getI("DLSSNR.FeatureInitResult", 0);
            // a driver from before DLSS-NR does not know its keys at all
            boolean unknown = available < 0;
            return new Capability(available > 0 && needsDriver == 0, needsDriver != 0 || unknown, major, minor, init);
        } finally {
            ngx.destroy(caps);
        }
    }

    // ---- textures ---------------------------------------------------------------------------------------------------

    /** The shared textures exist at exactly these sizes ({@link #resize} would keep them). */
    public boolean matches(int outW, int outH, int renderW, int renderH) {
        return color != null && color.width == outW && color.height == outH && depth.width == renderW && depth.height == renderH;
    }

    /**
     * Makes sure the shared textures and the feature fit these sizes ({@code true} when the textures were re-created: their
     * GL ids change — whatever wraps the old ones must let go before this, since new textures may get the same names).
     */
    public boolean resize(int outW, int outH, int renderW, int renderH) {
        if (matches(outW, outH, renderW, renderH)) return false;
        rt.waitIdle();
        ngx.release(feature);
        feature = 0;
        destroyImages();
        color = rt.image("NR colour", outW, outH, Vk.FORMAT_R16G16B16A16_SFLOAT, GL_RGBA16F, USAGE);
        output = rt.image("NR output", outW, outH, Vk.FORMAT_R16G16B16A16_SFLOAT, GL_RGBA16F, USAGE);
        depth = rt.image("NR depth", renderW, renderH, Vk.FORMAT_R32_SFLOAT, GL_R32F, USAGE);
        motion = rt.image("NR motion", renderW, renderH, Vk.FORMAT_R16G16_SFLOAT, GL_RG16F, USAGE);
        mask = rt.image("NR control mask", renderW, renderH, Vk.FORMAT_R8G8B8A8_UNORM, GL_RGBA8, USAGE);
        rt.initialLayouts(commandBuffer, fence, handoff, images());
        createFeature();
        return true;
    }

    /** The network (a render preset, {@link #PRESET_DEFAULT} or a lettered one); a change re-creates the feature. */
    public void preset(int preset) {
        if (preset == this.preset) return;
        this.preset = preset;
        if (feature != 0) {
            rt.waitIdle();
            ngx.release(feature);
            feature = 0;
            createFeature();
        }
    }

    /** The feature at the output size; its setup is recorded into a command buffer of its own submit. */
    private void createFeature() {
        params.setUI("DLSSNR.Width", color.width);
        params.setUI("DLSSNR.Height", color.height);
        params.setI("DLSSNR.Hint.Render.Preset", preset);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            rt.await(fence);
            rt.begin(stack, commandBuffer);
            feature = ngx.create(rt.device, commandBuffer, Ngx.FEATURE_DLSSNR, params);
            Vk.check(rt.vk.endCommandBuffer.callInt(commandBuffer), "vkEndCommandBuffer");
            rt.submit(stack, commandBuffer, new long[0], new int[0], new long[0], fence);
        }
    }

    /** GL texture for the picture in SDR (RGBA16F, linear 0..1, output size, rows flipped). */
    public int colorTexture() {
        return color.glTexture;
    }

    /** GL texture the model writes the relit picture into (RGBA16F, SDR, output size, rows flipped). */
    public int outputTexture() {
        return output.glTexture;
    }

    /** GL texture for hardware depth (R32F, render size, rows flipped; the hand and the weapon at their own depth). */
    public int depthTexture() {
        return depth.glTexture;
    }

    /** GL texture for motion vectors (RG16F, render pixels, render size, rows flipped; as {@link DlssBridge#motionTexture()}). */
    public int motionTexture() {
        return motion.glTexture;
    }

    /** GL texture for the control mask (RGBA8: R overall, G tone, B structure strength; render size, rows flipped). */
    public int maskTexture() {
        return mask.glTexture;
    }

    // ---- per frame --------------------------------------------------------------------------------------------------

    /**
     * Runs the model on this frame: the inputs must be drawn; afterwards GL commands may read the output.
     *
     * @param jitterX the frame's jitter in render pixels, texel space (as {@link DlssBridge.Frame#jitterX()})
     * @param reset   the history is unusable (camera cut, new level)
     */
    public void evaluate(Settings settings, float jitterX, float jitterY, boolean reset) {
        if (feature == 0) throw new IllegalStateException("evaluate before resize");
        SlRuntime.SharedImage[] all = images();
        handoff.glSignal(all);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            rt.await(fence);
            rt.begin(stack, commandBuffer);
            rt.handBarriers(stack, commandBuffer, true, all);

            long resources = MemoryUtil.memAddress(stack.calloc(8, RESOURCE_SIZE * 5));
            SlRuntime.SharedImage[] inputs = {color, output, motion, depth, mask};
            String[] keys = {"DLSSNR.Color", "DLSSNR.Output", "DLSSNR.MVec", "DLSSNR.Depth", "DLSSNR.ControlMask"};
            String[] subrects = {"Color", "Output", "MVec", "Depth", "ControlMask"};
            for (int i = 0; i < inputs.length; i++) {
                long resource = resources + (long) i * RESOURCE_SIZE;
                resource(resource, inputs[i], inputs[i] == output);
                params.setPointer(keys[i], resource);
                params.setUI("DLSSNR." + subrects[i] + "SubrectBaseX", 0);
                params.setUI("DLSSNR." + subrects[i] + "SubrectBaseY", 0);
                params.setUI("DLSSNR." + subrects[i] + "SubrectWidth", inputs[i].width);
                params.setUI("DLSSNR." + subrects[i] + "SubrectHeight", inputs[i].height);
            }
            for (String unused : new String[]{"DLSSNR.Backbuffer", "DLSSNR.BidirectionalDistortionField", "DLSSNR.UI", "DLSSNR.UIAlpha"}) {
                params.setPointer(unused, 0);
            }
            params.setF("DLSSNR.LocalToneStrength", settings.tone());
            params.setF("DLSSNR.LocalStructureStrength", settings.structure());
            params.setUI("DLSSNR.Style", settings.model());
            params.setI("DLSSNR.UseAutoMask", settings.autoMask() ? 1 : 0);
            params.setI("DLSSNR.MaskBlendMode", settings.autoMask() ? MASK_BLEND_MIN : MASK_BLEND_NONE);
            params.setF("DLSSNR.SkinStructureStrength", settings.autoMask() ? settings.skin() : 0f);
            params.setI("DLSSNR.Reset", reset ? 1 : 0);
            params.setI("DLSSNR.DepthInverted", 0);
            params.setI("DLSSNR.Enabled", 1);
            params.setI("DLSSNR.UICorrection", 0);
            params.setF("DLSSNR.Intensity", settings.intensity());
            // motion vectors are in render pixels already
            params.setF("DLSSNR.MVecScaleX", 1f);
            params.setF("DLSSNR.MVecScaleY", 1f);
            params.setF("Jitter.Offset.X", jitterX);
            params.setF("Jitter.Offset.Y", jitterY);
            ngx.evaluate(commandBuffer, feature, params);

            rt.handBarriers(stack, commandBuffer, false, all);
            Vk.check(rt.vk.endCommandBuffer.callInt(commandBuffer), "vkEndCommandBuffer");
            rt.submit(stack, commandBuffer, new long[]{handoff.vkReady}, new int[]{Vk.STAGE_ALL_COMMANDS}, new long[]{handoff.vkDone}, fence);
        }
        // Vulkan → GL: the model is done (the GL side waits on the GPU, not here)
        handoff.glWait(all);
    }

    /** An NVSDK_NGX_Resource_VK (image view, colour, one mip and layer) at {@code at}. */
    private static void resource(long at, SlRuntime.SharedImage image, boolean readWrite) {
        MemoryUtil.memSet(at, 0, RESOURCE_SIZE);
        MemoryUtil.memPutLong(at, image.view);
        MemoryUtil.memPutLong(at + 8, image.image);
        MemoryUtil.memPutInt(at + 16, 1);            // aspect colour
        MemoryUtil.memPutInt(at + 24, 1);            // level count
        MemoryUtil.memPutInt(at + 32, 1);            // layer count
        MemoryUtil.memPutInt(at + 36, image.vkFormat);
        MemoryUtil.memPutInt(at + 40, image.width);
        MemoryUtil.memPutInt(at + 44, image.height);
        MemoryUtil.memPutInt(at + 48, 0);            // image view
        MemoryUtil.memPutByte(at + 52, (byte) (readWrite ? 1 : 0));
    }

    private SlRuntime.SharedImage[] images() {
        return new SlRuntime.SharedImage[]{color, output, depth, motion, mask};
    }

    private void destroyImages() {
        for (SlRuntime.SharedImage image : images()) if (image != null) image.destroy();
        color = output = depth = motion = mask = null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        rt.waitIdle();
        ngx.release(feature);
        feature = 0;
        destroyImages();
        ngx.destroy(params);
        handoff.destroy();
        rt.vk.destroyFence.call(rt.device, fence, 0);
        rt.vk.destroyCommandPool.call(rt.device, commandPool, 0);
    }
}
