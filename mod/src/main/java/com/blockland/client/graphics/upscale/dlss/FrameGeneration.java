package com.blockland.client.graphics.upscale.dlss;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/**
 * DLSS Frame Generation (and Multi Frame Generation) for an OpenGL renderer (rendering spec §4): Streamline inserts the
 * generated frames at present, so the frames must reach the screen through a Vulkan swapchain Streamline owns. A child
 * window covers the game window's client area (input and focus stay with the game window, see
 * {@link Win32#createChild}); its swapchain is created through Streamline's hooks on the {@link SlRuntime}'s device.
 * <p>
 * Per frame the caller draws, upside down like every Vulkan-side image, into the GL side of {@link #colorTexture()} (the
 * finished frame with its HUD), {@link #hudlessTexture()} (the same frame before the HUD), {@link #uiTexture()} (the HUD
 * alone, premultiplied, with its coverage), {@link #depthTexture()} and {@link #motionTexture()} (render size, the same conventions as Super Resolution),
 * then calls {@link #present}: the frame is copied into the swapchain image, the four inputs are tagged for Frame
 * Generation (Streamline copies them right away), and the swapchain presents with the frame's PC-latency markers. Reflex
 * (which Frame Generation requires) sleeps at the start of the next frame. Render thread only.
 */
public final class FrameGeneration implements AutoCloseable {
    /**
     * @param status      DLSSGStatus bits (0 = fine; 1 resolution too low, 2 Reflex missing, 4 HDR format, 8 constants invalid)
     * @param maxFrames   most generated frames per rendered one this system allows (1 = 2× only; 0 = not known yet)
     * @param presented   frames reaching the screen per rendered frame, averaged
     * @param generating  frames were generated this frame
     */
    public record State(int status, int maxFrames, float presented, boolean generating) {
    }

    private static final int GL_RGBA8 = 0x8058, GL_RG16F = 0x822F, GL_R32F = 0x822E;
    private static final int USAGE = Vk.USAGE_SAMPLED | Vk.USAGE_STORAGE | Vk.USAGE_COLOR_ATTACHMENT | Vk.USAGE_TRANSFER_SRC | Vk.USAGE_TRANSFER_DST;
    private static final int SLOTS = 2;
    private static final int QUEUE_FAMILY_IGNORED = -1;

    private final SlRuntime rt;
    private final Vk vk;
    private final long child;
    private long surface, swapchain;
    private int swapFormat, swapWidth, swapHeight;
    private long[] swapImages = new long[0], rendered = new long[0];
    private final long pool;
    private final long[] commandBuffers = new long[SLOTS], fences = new long[SLOTS], acquired = new long[SLOTS];
    private int slot;
    private final SlRuntime.Handoff handoff;
    private SlRuntime.SharedImage color, hudless, ui, depth, motion;
    private int outputWidth, outputHeight, renderWidth, renderHeight;
    private boolean recreate = true;
    private int optionsMode = -1, optionsFrames = -1;
    private int status, maxFrames;
    private float presented = 1;
    private boolean generating, closed;

    private FrameGeneration(SlRuntime rt, long parentWindow, int reflexMode) {
        if (!rt.frameGeneration()) throw new IllegalStateException("the runtime was created without frame generation");
        this.rt = rt;
        this.vk = rt.vk;
        long window = 0;
        long commandPool = 0;
        SlRuntime.Handoff pair = null;
        try {
            window = Win32.createChild(parentWindow, 1, 1);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long info = Vk.struct(stack, 40, Vk.ST_WIN32_SURFACE_CREATE_INFO_KHR, 0);
                MemoryUtil.memPutAddress(info + 24, Win32.instance());
                MemoryUtil.memPutAddress(info + 32, window);
                long out = MemoryUtil.memAddress(stack.callocLong(1));
                Vk.check(vk.createWin32Surface.callInt(rt.instance, info, 0, out), "vkCreateWin32SurfaceKHR");
                surface = MemoryUtil.memGetLong(out);
                IntBuffer supported = stack.ints(0);
                Vk.check(vk.getSurfaceSupport.callInt(rt.physicalDevice, rt.queueFamily, surface, MemoryUtil.memAddress(supported)),
                        "vkGetPhysicalDeviceSurfaceSupportKHR");
                if (supported.get(0) == 0) throw new DlssException("blockland.graphics.status.fg.no_present", "queue family " + rt.queueFamily);
            }
            commandPool = rt.commandPool();
            for (int i = 0; i < SLOTS; i++) {
                commandBuffers[i] = rt.commandBuffer(commandPool);
                fences[i] = rt.fence();
                acquired[i] = rt.semaphore();
            }
            pair = rt.handoff();
        } catch (RuntimeException e) {
            child = window;
            pool = commandPool;
            handoff = pair;
            close();
            throw e;
        }
        child = window;
        pool = commandPool;
        handoff = pair;
        rt.sl.reflexOptions(reflexMode);
        rt.presenting(true, true);
    }

    /**
     * Starts presenting into a child window of {@code parentWindow} (the game window's HWND).
     *
     * @param reflexMode Reflex low latency: 1 on, 2 on with boost (Frame Generation does not run without it)
     */
    public static FrameGeneration create(SlRuntime runtime, long parentWindow, int reflexMode) {
        return new FrameGeneration(runtime, parentWindow, reflexMode);
    }

    public void reflex(int mode) {
        rt.sl.reflexOptions(mode);
    }

    // ---- textures ---------------------------------------------------------------------------------------------------

    /** The swapchain and the shared textures fit these sizes ({@link #resize} would keep them). */
    public boolean matches(int outW, int outH, int renderW, int renderH) {
        return outW == outputWidth && outH == outputHeight && renderW == renderWidth && renderH == renderHeight && color != null;
    }

    /**
     * Makes sure the swapchain and the shared textures fit these sizes ({@code true} when the textures were re-created: their
     * GL ids change — whatever wraps the old ones must let go before this, since new textures may get the same names). A new
     * output size turns generation off for the frame and recreates the swapchain.
     */
    public boolean resize(int outW, int outH, int renderW, int renderH) {
        if (matches(outW, outH, renderW, renderH)) return false;
        if (optionsMode == Streamline.DLSSG_ON) options(Streamline.DLSSG_OFF, optionsFrames);
        rt.waitIdle();
        destroyImages();
        if (outW != outputWidth || outH != outputHeight) {
            Win32.resizeChild(child, outW, outH);
            recreate = true;
        }
        outputWidth = outW;
        outputHeight = outH;
        renderWidth = renderW;
        renderHeight = renderH;
        color = rt.image("frame", outW, outH, Vk.FORMAT_R8G8B8A8_UNORM, GL_RGBA8, USAGE);
        hudless = rt.image("HUD-less", outW, outH, Vk.FORMAT_R8G8B8A8_UNORM, GL_RGBA8, USAGE);
        // UI colour and alpha rather than alpha alone: Streamline cannot copy single-channel 8-bit images
        ui = rt.image("UI", outW, outH, Vk.FORMAT_R8G8B8A8_UNORM, GL_RGBA8, USAGE);
        depth = rt.image("FG depth", renderW, renderH, Vk.FORMAT_R32_SFLOAT, GL_R32F, USAGE);
        motion = rt.image("FG motion", renderW, renderH, Vk.FORMAT_R16G16_SFLOAT, GL_RG16F, USAGE);
        rt.initialLayouts(commandBuffers[slot], fences[slot], handoff, images());
        return true;
    }

    /** GL texture for the finished frame with its HUD (RGBA8, output size, rows flipped). */
    public int colorTexture() {
        return color.glTexture;
    }

    /** GL texture for the frame before the HUD (RGBA8, output size, rows flipped; same colours as the frame). */
    public int hudlessTexture() {
        return hudless.glTexture;
    }

    /**
     * GL texture for the HUD alone: premultiplied colour and coverage, 0 where the scene shows through, so that frame =
     * UI + (1 - UI alpha) × HUD-less (RGBA8, output size, rows flipped).
     */
    public int uiTexture() {
        return ui.glTexture;
    }

    /** GL texture for hardware depth (R32F, render size, rows flipped; as {@link DlssBridge#depthTexture()}). */
    public int depthTexture() {
        return depth.glTexture;
    }

    /** GL texture for motion vectors (RG16F, render size, rows flipped; as {@link DlssBridge#motionTexture()}). */
    public int motionTexture() {
        return motion.glTexture;
    }

    // ---- per frame --------------------------------------------------------------------------------------------------

    /** The frame's rendering starts (after the game's simulation): PC-latency markers. */
    public void beginFrame() {
        long token = rt.frameToken();
        rt.sl.marker(Streamline.SIMULATION_END, token);
        rt.sl.marker(Streamline.RENDER_SUBMIT_START, token);
    }

    /**
     * Presents this frame; the textures must be drawn.
     *
     * @param multiplier frames on screen per rendered frame (1 = no generation, 2 = DLSS Frame Generation, 3+ = Multi Frame
     *                   Generation; capped at what the system allows)
     * @param frame      the frame's camera, or null when it is not a game frame (a menu, a loading screen): nothing is
     *                   generated around it
     * @return false when the window could not take a frame (minimised, being resized): nothing was presented
     */
    public boolean present(int multiplier, DlssBridge.Frame frame) {
        if (color == null) throw new IllegalStateException("present before resize");
        Streamline sl = rt.sl;
        long token = rt.frameToken();
        if (recreate && !createSwapchain()) {
            rt.nextFrame(false);
            return false;
        }
        int frames = Math.max(1, Math.min(multiplier - 1, maxFrames > 0 ? maxFrames : 1));
        generating = multiplier > 1 && frame != null;
        options(generating ? Streamline.DLSSG_ON : Streamline.DLSSG_OFF, frames);
        if (generating) rt.constants(frame);

        SlRuntime.SharedImage[] all = images();
        int index;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long fenceList = MemoryUtil.memAddress(stack.longs(fences[slot]));
            int waited = vk.waitForFences.callInt(rt.device, 1, fenceList, 1, SlRuntime.FENCE_TIMEOUT);
            if (waited == Vk.TIMEOUT) throw new DlssException("blockland.graphics.status.dlss.vulkan", "previous present did not finish");
            Vk.check(waited, "vkWaitForFences");
            // Streamline paces the frames here (and signals the semaphore once its own work for the image is queued)
            IntBuffer next = stack.ints(0);
            int acquire = vk.acquireNextImage.callInt(rt.device, swapchain, -1L, acquired[slot], 0, MemoryUtil.memAddress(next));
            if (acquire == Vk.ERROR_OUT_OF_DATE) {
                recreate = true;
                rt.nextFrame(false);
                return false;
            }
            if (acquire != Vk.SUBOPTIMAL) Vk.check(acquire, "vkAcquireNextImageKHR");
            index = next.get(0);
            Vk.check(vk.resetFences.callInt(rt.device, 1, fenceList), "vkResetFences");

            // GL → Vulkan: the frame and the inputs are written
            handoff.glSignal(all);
            long cb = commandBuffers[slot];
            rt.begin(stack, cb);
            rt.handBarriers(stack, cb, true, all);
            long barrier = MemoryUtil.memAddress(stack.calloc(8, 72));
            Vk.imageBarrier(barrier, 0, Vk.ACCESS_TRANSFER_WRITE, Vk.LAYOUT_UNDEFINED, Vk.LAYOUT_TRANSFER_DST, QUEUE_FAMILY_IGNORED,
                    QUEUE_FAMILY_IGNORED, swapImages[index]);
            vk.cmdPipelineBarrier.call(cb, Vk.STAGE_TRANSFER, Vk.STAGE_TRANSFER, 0, 0, 0, 0, 0, 1, barrier);
            long region = MemoryUtil.memAddress(stack.calloc(8, 80));
            MemoryUtil.memPutInt(region, 1);                  // colour aspect, mip 0, layer 0, 1 layer
            MemoryUtil.memPutInt(region + 12, 1);
            MemoryUtil.memPutInt(region + 28, color.width);   // srcOffsets[1]
            MemoryUtil.memPutInt(region + 32, color.height);
            MemoryUtil.memPutInt(region + 36, 1);
            MemoryUtil.memPutInt(region + 40, 1);
            MemoryUtil.memPutInt(region + 52, 1);
            MemoryUtil.memPutInt(region + 68, swapWidth);     // dstOffsets[1]
            MemoryUtil.memPutInt(region + 72, swapHeight);
            MemoryUtil.memPutInt(region + 76, 1);
            boolean scaled = swapWidth != color.width || swapHeight != color.height;
            vk.cmdBlitImage.call(cb, color.image, Vk.LAYOUT_GENERAL, swapImages[index], Vk.LAYOUT_TRANSFER_DST, 1, region, scaled ? 1 : 0);
            Vk.imageBarrier(barrier, Vk.ACCESS_TRANSFER_WRITE, 0, Vk.LAYOUT_TRANSFER_DST, Vk.LAYOUT_PRESENT_SRC, QUEUE_FAMILY_IGNORED,
                    QUEUE_FAMILY_IGNORED, swapImages[index]);
            vk.cmdPipelineBarrier.call(cb, Vk.STAGE_TRANSFER, Vk.STAGE_BOTTOM_OF_PIPE, 0, 0, 0, 0, 0, 1, barrier);
            if (generating) tag(stack, token, cb);
            rt.handBarriers(stack, cb, false, all);
            Vk.check(vk.endCommandBuffer.callInt(cb), "vkEndCommandBuffer");
            rt.submit(stack, cb, new long[]{acquired[slot], handoff.vkReady}, new int[]{Vk.STAGE_TRANSFER, Vk.STAGE_ALL_COMMANDS},
                    new long[]{rendered[index], handoff.vkDone}, fences[slot]);
            sl.marker(Streamline.RENDER_SUBMIT_END, token);

            sl.marker(Streamline.PRESENT_START, token);
            long info = Vk.struct(stack, 64, Vk.ST_PRESENT_INFO_KHR, 0);
            MemoryUtil.memPutInt(info + 16, 1);
            MemoryUtil.memPutAddress(info + 24, MemoryUtil.memAddress(stack.longs(rendered[index])));
            MemoryUtil.memPutInt(info + 32, 1);
            MemoryUtil.memPutAddress(info + 40, MemoryUtil.memAddress(stack.longs(swapchain)));
            MemoryUtil.memPutAddress(info + 48, MemoryUtil.memAddress(stack.ints(index)));
            int result = vk.queuePresent.callInt(rt.queue, info);
            sl.marker(Streamline.PRESENT_END, token);
            if (result == Vk.ERROR_OUT_OF_DATE || result == Vk.SUBOPTIMAL || acquire == Vk.SUBOPTIMAL) recreate = true;
            else Vk.check(result, "vkQueuePresentKHR");
        }
        slot = (slot + 1) % SLOTS;
        // Vulkan → GL: the copies are done (the GL side waits on the GPU, not here)
        handoff.glWait(all);
        int[] state = sl.frameGenerationState(rt.viewport);
        status = state[0];
        maxFrames = state[3];
        if (state[2] > 0) presented = presented * 0.9f + state[2] * 0.1f;
        rt.nextFrame(true);
        return true;
    }

    public State state() {
        return new State(status, maxFrames, presented, generating);
    }

    /** The four inputs for this frame's present; Streamline copies them now (OpenGL rewrites them next frame). */
    private void tag(MemoryStack stack, long token, long commandBuffer) {
        SlRuntime.SharedImage[] tagged = {depth, motion, hudless, ui};
        int[] types = {Streamline.BUFFER_DEPTH, Streamline.BUFFER_MOTION_VECTORS, Streamline.BUFFER_HUDLESS, Streamline.BUFFER_UI_COLOR_ALPHA};
        long resources = MemoryUtil.memAddress(stack.calloc(8, Streamline.RESOURCE_SIZE * 4));
        long tags = MemoryUtil.memAddress(stack.calloc(8, Streamline.TAG_SIZE * 4));
        for (int i = 0; i < 4; i++) {
            long resource = resources + (long) i * Streamline.RESOURCE_SIZE;
            tagged[i].resource(resource);
            Streamline.tag(tags + (long) i * Streamline.TAG_SIZE, resource, types[i], Streamline.ONLY_VALID_NOW);
        }
        rt.sl.setTags(token, rt.viewport, tags, 4, commandBuffer);
    }

    private void options(int mode, int frames) {
        if (mode == optionsMode && frames == optionsFrames) return;
        // resources are kept while generation pauses (menus, a resize): no stutter when it resumes
        rt.sl.frameGenerationOptions(rt.viewport, mode, frames, Streamline.DLSSG_RETAIN_RESOURCES, swapImages.length,
                renderWidth, renderHeight, outputWidth, outputHeight, swapFormat);
        optionsMode = mode;
        optionsFrames = frames;
    }

    private SlRuntime.SharedImage[] images() {
        return new SlRuntime.SharedImage[]{color, hudless, ui, depth, motion};
    }

    // ---- swapchain --------------------------------------------------------------------------------------------------

    /** (Re)creates the swapchain at the window's size; false while the window has no area (minimised). */
    private boolean createSwapchain() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long caps = MemoryUtil.memAddress(stack.calloc(8, 56));
            Vk.check(vk.getSurfaceCapabilities.callInt(rt.physicalDevice, surface, caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
            int width = MemoryUtil.memGetInt(caps + 8), height = MemoryUtil.memGetInt(caps + 12);
            if (width == -1) {
                width = outputWidth;
                height = outputHeight;
            }
            if (width <= 0 || height <= 0) return false;
            destroySwapchain();
            int minImages = MemoryUtil.memGetInt(caps), maxImages = MemoryUtil.memGetInt(caps + 4);
            int count = Math.max(minImages + 1, 3);
            if (maxImages > 0) count = Math.min(count, maxImages);
            int transform = MemoryUtil.memGetInt(caps + 40);
            swapFormat = surfaceFormat(stack);
            int presentMode = presentMode(stack);

            long info = Vk.struct(stack, 104, Vk.ST_SWAPCHAIN_CREATE_INFO_KHR, 0);
            MemoryUtil.memPutLong(info + 24, surface);
            MemoryUtil.memPutInt(info + 32, count);
            MemoryUtil.memPutInt(info + 36, swapFormat);
            MemoryUtil.memPutInt(info + 40, 0);                  // sRGB non-linear: the frame is already display-encoded
            MemoryUtil.memPutInt(info + 44, width);
            MemoryUtil.memPutInt(info + 48, height);
            MemoryUtil.memPutInt(info + 52, 1);
            MemoryUtil.memPutInt(info + 56, Vk.USAGE_TRANSFER_DST | Vk.USAGE_COLOR_ATTACHMENT);
            MemoryUtil.memPutInt(info + 60, 0);                  // exclusive
            MemoryUtil.memPutInt(info + 80, transform);
            MemoryUtil.memPutInt(info + 84, 1);                  // opaque
            MemoryUtil.memPutInt(info + 88, presentMode);
            MemoryUtil.memPutInt(info + 92, 1);                  // clipped
            long out = MemoryUtil.memAddress(stack.callocLong(1));
            Vk.check(vk.createSwapchain.callInt(rt.device, info, 0, out), "vkCreateSwapchainKHR");
            swapchain = MemoryUtil.memGetLong(out);
            swapWidth = width;
            swapHeight = height;

            IntBuffer n = stack.ints(0);
            Vk.check(vk.getSwapchainImages.callInt(rt.device, swapchain, MemoryUtil.memAddress(n), 0), "vkGetSwapchainImagesKHR");
            long images = MemoryUtil.memAddress(stack.callocLong(Math.max(1, n.get(0))));
            Vk.check(vk.getSwapchainImages.callInt(rt.device, swapchain, MemoryUtil.memAddress(n), images), "vkGetSwapchainImagesKHR");
            swapImages = new long[n.get(0)];
            rendered = new long[n.get(0)];
            for (int i = 0; i < swapImages.length; i++) {
                swapImages[i] = MemoryUtil.memGetLong(images + 8L * i);
                rendered[i] = rt.semaphore();
            }
        }
        recreate = false;
        optionsMode = -1;
        return true;
    }

    /** 8-bit UNORM (BGRA, else RGBA): the frame's bytes go over unchanged. */
    private int surfaceFormat(MemoryStack stack) {
        IntBuffer n = stack.ints(0);
        Vk.check(vk.getSurfaceFormats.callInt(rt.physicalDevice, surface, MemoryUtil.memAddress(n), 0), "vkGetPhysicalDeviceSurfaceFormatsKHR");
        long formats = MemoryUtil.memAddress(stack.calloc(8, 8 * Math.max(1, n.get(0))));
        Vk.check(vk.getSurfaceFormats.callInt(rt.physicalDevice, surface, MemoryUtil.memAddress(n), formats), "vkGetPhysicalDeviceSurfaceFormatsKHR");
        int fallback = -1;
        for (int i = 0; i < n.get(0); i++) {
            int format = MemoryUtil.memGetInt(formats + 8L * i), space = MemoryUtil.memGetInt(formats + 8L * i + 4);
            if (space != 0) continue;
            if (format == Vk.FORMAT_B8G8R8A8_UNORM) return format;
            if (format == Vk.FORMAT_R8G8B8A8_UNORM) fallback = format;
        }
        if (fallback < 0) throw new DlssException("blockland.graphics.status.fg.no_present", "no 8-bit UNORM surface format");
        return fallback;
    }

    /** No vertical sync (Frame Generation paces the frames itself; v-sync with it is Direct3D 12 only). */
    private int presentMode(MemoryStack stack) {
        IntBuffer n = stack.ints(0);
        Vk.check(vk.getSurfacePresentModes.callInt(rt.physicalDevice, surface, MemoryUtil.memAddress(n), 0), "vkGetPhysicalDeviceSurfacePresentModesKHR");
        IntBuffer modes = stack.mallocInt(Math.max(1, n.get(0)));
        Vk.check(vk.getSurfacePresentModes.callInt(rt.physicalDevice, surface, MemoryUtil.memAddress(n), MemoryUtil.memAddress(modes)),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        boolean mailbox = false;
        for (int i = 0; i < n.get(0); i++) {
            if (modes.get(i) == Vk.PRESENT_IMMEDIATE) return Vk.PRESENT_IMMEDIATE;
            if (modes.get(i) == Vk.PRESENT_MAILBOX) mailbox = true;
        }
        return mailbox ? Vk.PRESENT_MAILBOX : Vk.PRESENT_FIFO;
    }

    private void destroySwapchain() {
        if (swapchain == 0) return;
        rt.waitIdle();
        vk.destroySwapchain.call(rt.device, swapchain, 0);
        for (long semaphore : rendered) vk.destroySemaphore.call(rt.device, semaphore, 0);
        swapchain = 0;
        swapImages = new long[0];
        rendered = new long[0];
    }

    private void destroyImages() {
        for (SlRuntime.SharedImage image : images()) if (image != null) image.destroy();
        color = hudless = ui = depth = motion = null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            rt.presenting(false, false);
            if (optionsMode > 0) options(Streamline.DLSSG_OFF, optionsFrames);
            rt.waitIdle();
            rt.sl.freeResources(Streamline.FEATURE_DLSS_G, rt.viewport);
            destroySwapchain();
            destroyImages();
            if (handoff != null) handoff.destroy();
            for (int i = 0; i < SLOTS; i++) {
                if (fences[i] != 0) vk.destroyFence.call(rt.device, fences[i], 0);
                if (acquired[i] != 0) vk.destroySemaphore.call(rt.device, acquired[i], 0);
            }
            if (pool != 0) vk.destroyCommandPool.call(rt.device, pool, 0);
            if (surface != 0) vk.destroySurface.call(rt.instance, surface, 0);
            rt.sl.reflexOptions(0);
        } finally {
            Win32.destroyChild(child);
        }
    }
}
