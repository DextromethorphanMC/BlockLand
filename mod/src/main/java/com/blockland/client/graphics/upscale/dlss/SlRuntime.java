package com.blockland.client.graphics.upscale.dlss;

import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NVIDIA's Streamline on a Vulkan device of its own for an OpenGL renderer: the device runs on the same GPU as the current
 * GL context (matched by device UUID), its images live in memory OpenGL imports ({@code GL_EXT_memory_object_win32}) and
 * frames are handed across with shared semaphores ({@code GL_EXT_semaphore_win32}). One runtime per GL context and
 * process, brought up once: DLSS Super Resolution ({@link DlssBridge}) and DLSS Frame Generation ({@link FrameGeneration})
 * both run on it, since Streamline knows one device and numbers one sequence of frames — and Streamline does not survive
 * being brought up again after a shutdown once Frame Generation was loaded (vkCreateDevice crashes in its proxy).
 * <p>
 * The frame: {@link #frameToken()} is the token of the frame being drawn (its constants are set once, by whichever
 * feature comes first, see {@link #constants}); the frame ends either through the swapchain (frame generation presents,
 * {@link #nextFrame(boolean)} with {@code true}) or, when OpenGL presents, with Streamline's common present hook called
 * directly. Depends only on LWJGL, so it can be tested outside the game ({@code tools/dlss}). Render thread only.
 */
public final class SlRuntime implements AutoCloseable {
    /** The runtime files Super Resolution needs. */
    public static final List<String> DLSS_FILES = List.of("sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "nvngx_dlss.dll");
    /** The files DLSS Ray Reconstruction needs (its Streamline plugin and model). */
    public static final List<String> RAY_RECONSTRUCTION_FILES = List.of("sl.dlss_d.dll", "nvngx_dlssd.dll");
    /** DLSS 5's neural rendering model (from NVIDIA's DLSS-NR SDK, run through NGX directly). */
    public static final String NEURAL_RENDERING_FILE = "nvngx_dlssnr.dll";
    /** The further files Frame Generation needs (its plugin and model, Reflex with its Vulkan layer, PC latency markers). */
    public static final List<String> FRAME_GENERATION_FILES = List.of("sl.dlss_g.dll", "nvngx_dlssg.dll", "sl.reflex.dll", "sl.pcl.dll",
            "NvLowLatencyVk.dll");

    static final long FENCE_TIMEOUT = 2_000_000_000L;

    final Streamline sl;
    final Vk vk;
    long instance, physicalDevice, device, queue;
    int queueFamily;
    long viewport;
    private final ByteBuffer memoryProperties = MemoryUtil.memCalloc(520);
    private boolean frameGeneration;
    /** Why frame generation did not come up although it was asked for (the runtime runs without it), or null. */
    private DlssException frameGenerationFailure;
    private boolean rayReconstruction;
    /** Why Ray Reconstruction did not come up (its files missing, unsupported), or null. */
    private DlssException rayReconstructionFailure;
    private String deviceName = "";
    private String version = "";
    private int frameIndex;
    private long token;
    private int constantsFrame = -1;
    private boolean presenting, reflex;
    private boolean closed;
    private final Path runtimeFolder, logFolder;
    private final String engineVersion;
    /** NGX called directly (DLSS 5 neural rendering), loaded before the device when its model is there; null otherwise. */
    private Ngx ngx;
    private boolean ngxInitialised;

    private SlRuntime(Path runtime, Path logs, String engineVersion, boolean frameGeneration) {
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.GL_EXT_memory_object || !caps.GL_EXT_memory_object_win32 || !caps.GL_EXT_semaphore || !caps.GL_EXT_semaphore_win32) {
            throw new DlssException("blockland.graphics.status.dlss.no_interop", GL11.glGetString(GL11.GL_RENDERER));
        }
        List<byte[]> glDevices = glDeviceUuids();
        this.frameGeneration = frameGeneration;
        this.runtimeFolder = runtime;
        this.logFolder = logs;
        this.engineVersion = engineVersion;
        sl = new Streamline(runtime);
        try {
            boolean reconstructionFiles = missing(runtime, RAY_RECONSTRUCTION_FILES).isEmpty();
            List<Integer> features = new ArrayList<>(List.of(Streamline.FEATURE_DLSS));
            if (frameGeneration) features.addAll(List.of(Streamline.FEATURE_DLSS_G, Streamline.FEATURE_REFLEX, Streamline.FEATURE_PCL));
            if (reconstructionFiles) features.add(Streamline.FEATURE_DLSS_RR);
            sl.init(runtime, logs, engineVersion, features.stream().mapToInt(Integer::intValue).toArray());
            vk = new Vk(sl.proc("vkGetInstanceProcAddr"), sl.proc("vkGetDeviceProcAddr"));
            createInstance();
            pickPhysicalDevice(glDevices);
            createDevice();
            sl.enableDlss(physicalDevice);
            if (reconstructionFiles) {
                try {
                    sl.enableRayReconstruction(physicalDevice);
                    rayReconstruction = true;
                } catch (DlssException e) {
                    rayReconstructionFailure = e;
                }
            } else {
                rayReconstructionFailure = new DlssException("blockland.graphics.status.rr.no_runtime", String.join(", ", RAY_RECONSTRUCTION_FILES));
            }
            if (frameGeneration) {
                // unsupported here (no GPU scheduling, an older GPU): Super Resolution keeps running on this runtime, since
                // Streamline cannot be brought up a second time in the same process once Frame Generation was loaded
                try {
                    sl.enableFrameGeneration(physicalDevice);
                } catch (DlssException e) {
                    this.frameGeneration = false;
                    frameGenerationFailure = e;
                }
            }
            viewport = sl.viewport(0);
            version = sl.dlssVersion();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /**
     * Brings up Streamline and its Vulkan device for the current GL context.
     *
     * @param runtime         folder with NVIDIA's Streamline binaries ({@link #DLSS_FILES}, and {@link #FRAME_GENERATION_FILES}
     *                        for frame generation)
     * @param logs            folder for Streamline's log
     * @param engineVersion   the host's version string (NGX identifies custom engines by it and a project id)
     * @param frameGeneration also load DLSS Frame Generation, Reflex and PCL, and make the device able to present (when
     *                        that does not work here the runtime comes up without it: see {@link #frameGenerationFailure()})
     * @throws DlssException with the reason when anything is missing or refuses
     */
    public static SlRuntime create(Path runtime, Path logs, String engineVersion, boolean frameGeneration) {
        return new SlRuntime(runtime, logs, engineVersion, frameGeneration);
    }

    /** The files of {@code names} missing from {@code folder}. */
    public static List<String> missing(Path folder, List<String> names) {
        List<String> missing = new ArrayList<>();
        for (String name : names) if (!Files.isRegularFile(folder.resolve(name))) missing.add(name);
        return missing;
    }

    /** Frame generation was loaded and is supported (the device can present). */
    public boolean frameGeneration() {
        return frameGeneration;
    }

    /** DLSS Ray Reconstruction was loaded and is supported on this device. */
    public boolean rayReconstruction() {
        return rayReconstruction;
    }

    /** Why Ray Reconstruction is not on this runtime, or null. */
    public DlssException rayReconstructionFailure() {
        return rayReconstructionFailure;
    }

    /** Why frame generation is not on this runtime although it was asked for, or null. */
    public DlssException frameGenerationFailure() {
        return frameGenerationFailure;
    }

    /** "GPU name · SL x / DLSS y" for the status line. */
    public String description() {
        return deviceName + (version.isEmpty() ? "" : " · " + version);
    }

    // ---- the frame --------------------------------------------------------------------------------------------------

    /** The token of the frame being drawn (owned by Streamline). */
    long frameToken() {
        if (token == 0) token = sl.frameToken(frameIndex);
        return token;
    }

    /** This frame's common constants, unless a feature already set them (Streamline wants them once per frame). */
    void constants(DlssBridge.Frame frame) {
        if (constantsFrame == frameIndex) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            sl.setConstants(Streamline.constants(stack, frame), frameToken(), viewport);
        }
        constantsFrame = frameIndex;
    }

    /** A swapchain presents the frames ({@link FrameGeneration}): the frame ends at its present, not with Super Resolution. */
    boolean presenting() {
        return presenting;
    }

    void presenting(boolean presenting, boolean reflex) {
        this.presenting = presenting;
        this.reflex = presenting && reflex;
    }

    /**
     * The frame is done. Presented by OpenGL ({@code swapchain} false): Streamline's common present hook is called directly.
     * With Reflex on, the next frame starts with Reflex's sleep and its simulation marker.
     */
    void nextFrame(boolean swapchain) {
        if (!swapchain) sl.present();
        frameIndex++;
        token = 0;
        if (reflex) {
            long next = frameToken();
            sl.reflexSleep(next);
            sl.marker(Streamline.SIMULATION_START, next);
        }
    }

    // ---- setup ------------------------------------------------------------------------------------------------------

    private static List<byte[]> glDeviceUuids() {
        List<byte[]> uuids = new ArrayList<>();
        int count = GL11.glGetInteger(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uuid = stack.calloc(16);
            for (int i = 0; i < count; i++) {
                EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, i, uuid);
                byte[] bytes = new byte[16];
                uuid.get(0, bytes);
                uuids.add(bytes);
            }
        }
        if (uuids.isEmpty()) throw new DlssException("blockland.graphics.status.dlss.no_interop", "no GL device UUID");
        return uuids;
    }

    private void createInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long app = Vk.struct(stack, 48, Vk.ST_APPLICATION_INFO, 0);
            MemoryUtil.memPutAddress(app + 16, MemoryUtil.memAddress(stack.UTF8("BlockLand")));
            MemoryUtil.memPutAddress(app + 32, MemoryUtil.memAddress(stack.UTF8("BlockLand")));
            MemoryUtil.memPutInt(app + 44, Vk.API_VERSION_1_3);
            long info = Vk.struct(stack, 64, Vk.ST_INSTANCE_CREATE_INFO, 0);
            MemoryUtil.memPutAddress(info + 24, app);
            if (frameGeneration) {
                long names = strings(stack, "VK_KHR_surface", "VK_KHR_win32_surface");
                MemoryUtil.memPutInt(info + 48, 2);
                MemoryUtil.memPutAddress(info + 56, names);
            }
            long out = MemoryUtil.memAddress(stack.callocPointer(1));
            Vk.check(vk.createInstance.callInt(info, 0, out), "vkCreateInstance");
            instance = MemoryUtil.memGetAddress(out);
            vk.loadInstance(instance);
            if (frameGeneration) vk.loadSurface();
        }
    }

    /** The physical device the GL context runs on (same device UUID). */
    private void pickPhysicalDevice(List<byte[]> glDevices) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.ints(0);
            Vk.check(vk.enumeratePhysicalDevices.callInt(instance, MemoryUtil.memAddress(count), 0), "vkEnumeratePhysicalDevices");
            long devices = MemoryUtil.memAddress(stack.callocPointer(Math.max(1, count.get(0))));
            Vk.check(vk.enumeratePhysicalDevices.callInt(instance, MemoryUtil.memAddress(count), devices), "vkEnumeratePhysicalDevices");
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < count.get(0); i++) {
                long candidate = MemoryUtil.memGetAddress(devices + 8L * i);
                long id = Vk.struct(stack, 64, Vk.ST_PHYSICAL_DEVICE_ID_PROPERTIES, 0);
                long props = Vk.struct(stack, 840, Vk.ST_PHYSICAL_DEVICE_PROPERTIES_2, id);
                vk.getPhysicalDeviceProperties2.call(candidate, props);
                String name = MemoryUtil.memUTF8(props + 16 + 20);
                byte[] uuid = new byte[16];
                MemoryUtil.memByteBuffer(id + 16, 16).get(0, uuid);
                seen.add(name);
                for (byte[] gl : glDevices) {
                    if (Arrays.equals(gl, uuid)) {
                        physicalDevice = candidate;
                        deviceName = name;
                    }
                }
                if (physicalDevice != 0) break;
            }
            if (physicalDevice == 0) {
                // e.g. a laptop running the game on its integrated GPU while the NVIDIA GPU idles
                throw new DlssException("blockland.graphics.status.dlss.other_gpu", String.join(", ", seen));
            }
            vk.getPhysicalDeviceMemoryProperties.call(physicalDevice, MemoryUtil.memAddress(memoryProperties));

            IntBuffer families = stack.ints(0);
            vk.getPhysicalDeviceQueueFamilyProperties.call(physicalDevice, MemoryUtil.memAddress(families), 0);
            long list = MemoryUtil.memAddress(stack.calloc(8, 24 * Math.max(1, families.get(0))));
            vk.getPhysicalDeviceQueueFamilyProperties.call(physicalDevice, MemoryUtil.memAddress(families), list);
            queueFamily = -1;
            for (int i = 0; i < families.get(0); i++) {
                int flags = MemoryUtil.memGetInt(list + 24L * i);
                if ((flags & (Vk.QUEUE_GRAPHICS | Vk.QUEUE_COMPUTE)) == (Vk.QUEUE_GRAPHICS | Vk.QUEUE_COMPUTE)) {
                    queueFamily = i;
                    break;
                }
            }
            if (queueFamily < 0) throw new DlssException("blockland.graphics.status.dlss.vulkan", "no graphics queue");
        }
    }

    /** Through Streamline's proxy, which adds the queues, extensions and features its plugins need. */
    private void createDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long queueInfo = Vk.struct(stack, 40, Vk.ST_DEVICE_QUEUE_CREATE_INFO, 0);
            MemoryUtil.memPutInt(queueInfo + 20, queueFamily);
            MemoryUtil.memPutInt(queueInfo + 24, 1);
            MemoryUtil.memPutAddress(queueInfo + 32, MemoryUtil.memAddress(stack.floats(1f)));
            List<String> wanted = new ArrayList<>(List.of("VK_KHR_external_memory_win32", "VK_KHR_external_semaphore_win32"));
            if (frameGeneration) wanted.add("VK_KHR_swapchain");
            // DLSS 5 runs through NGX directly: whatever extensions its model needs must be on the device from the start
            for (String name : neuralRenderingExtensions()) if (!wanted.contains(name)) wanted.add(name);
            String[] extensions = wanted.toArray(String[]::new);
            long info = Vk.struct(stack, 72, Vk.ST_DEVICE_CREATE_INFO, 0);
            MemoryUtil.memPutInt(info + 20, 1);
            MemoryUtil.memPutAddress(info + 24, queueInfo);
            MemoryUtil.memPutInt(info + 48, extensions.length);
            MemoryUtil.memPutAddress(info + 56, strings(stack, extensions));
            long out = MemoryUtil.memAddress(stack.callocPointer(1));
            Vk.check(vk.createDevice.callInt(physicalDevice, info, 0, out), "vkCreateDevice");
            device = MemoryUtil.memGetAddress(out);
            vk.loadDevice(device);
            if (frameGeneration) vk.loadSwapchain();
            vk.getDeviceQueue.call(device, queueFamily, 0, out);
            queue = MemoryUtil.memGetAddress(out);
        }
    }

    /** The device extensions DLSS 5's model asks NGX for that this GPU has (none without the model or the driver's support). */
    private List<String> neuralRenderingExtensions() {
        List<String> names = new ArrayList<>();
        if (!Files.isRegularFile(runtimeFolder.resolve(NEURAL_RENDERING_FILE))) return names;
        try {
            ngx = Ngx.load();
            List<String> required = ngx.deviceExtensions(instance, physicalDevice, Ngx.FEATURE_DLSSNR, Streamline.PROJECT_ID,
                    engineVersion, runtimeFolder);
            List<String> available = deviceExtensionNames();
            for (String name : required) if (available.contains(name)) names.add(name);
        } catch (RuntimeException e) {
            ngx = null;
        }
        return names;
    }

    private List<String> deviceExtensionNames() {
        List<String> names = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.ints(0);
            vk.enumerateDeviceExtensionProperties.callInt(physicalDevice, 0, MemoryUtil.memAddress(count), 0);
            // hundreds of 260-byte entries: more than the thread's stack allocator holds
            ByteBuffer buffer = MemoryUtil.memCalloc(260 * Math.max(1, count.get(0)));
            try {
                long list = MemoryUtil.memAddress(buffer);
                vk.enumerateDeviceExtensionProperties.callInt(physicalDevice, 0, MemoryUtil.memAddress(count), list);
                for (int i = 0; i < count.get(0); i++) names.add(MemoryUtil.memUTF8(list + 260L * i));
            } finally {
                MemoryUtil.memFree(buffer);
            }
        }
        return names;
    }

    /**
     * NGX on this runtime's device, initialised on first use (DLSS 5); throws why it cannot run. NGX keeps one context per
     * device, shared with the one Streamline made for Super Resolution.
     */
    Ngx ngx() {
        if (ngx == null) {
            if (!Files.isRegularFile(runtimeFolder.resolve(NEURAL_RENDERING_FILE))) {
                throw new DlssException("blockland.graphics.status.nr.no_runtime", NEURAL_RENDERING_FILE);
            }
            ngx = Ngx.load();
        }
        if (!ngxInitialised) {
            ngx.init(Streamline.PROJECT_ID, engineVersion, logFolder, runtimeFolder, instance, physicalDevice, device);
            ngxInitialised = true;
        }
        return ngx;
    }

    private static long strings(MemoryStack stack, String... values) {
        long names = MemoryUtil.memAddress(stack.callocPointer(values.length));
        for (int i = 0; i < values.length; i++) MemoryUtil.memPutAddress(names + 8L * i, MemoryUtil.memAddress(stack.ASCII(values[i])));
        return names;
    }

    // ---- shared objects ---------------------------------------------------------------------------------------------

    /** A command pool on the queue's family (command buffers individually resettable). */
    long commandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = MemoryUtil.memAddress(stack.callocLong(1));
            long pool = Vk.struct(stack, 24, Vk.ST_COMMAND_POOL_CREATE_INFO, 0);
            MemoryUtil.memPutInt(pool + 16, 2);
            MemoryUtil.memPutInt(pool + 20, queueFamily);
            Vk.check(vk.createCommandPool.callInt(device, pool, 0, out), "vkCreateCommandPool");
            return MemoryUtil.memGetLong(out);
        }
    }

    long commandBuffer(long pool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = MemoryUtil.memAddress(stack.callocPointer(1));
            long alloc = Vk.struct(stack, 32, Vk.ST_COMMAND_BUFFER_ALLOCATE_INFO, 0);
            MemoryUtil.memPutLong(alloc + 16, pool);
            MemoryUtil.memPutInt(alloc + 24, 0);
            MemoryUtil.memPutInt(alloc + 28, 1);
            Vk.check(vk.allocateCommandBuffers.callInt(device, alloc, out), "vkAllocateCommandBuffers");
            return MemoryUtil.memGetAddress(out);
        }
    }

    /** A fence, created signalled. */
    long fence() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = MemoryUtil.memAddress(stack.callocLong(1));
            long info = Vk.struct(stack, 24, Vk.ST_FENCE_CREATE_INFO, 0);
            MemoryUtil.memPutInt(info + 16, 1);
            Vk.check(vk.createFence.callInt(device, info, 0, out), "vkCreateFence");
            return MemoryUtil.memGetLong(out);
        }
    }

    /** Waits for a fence (a frame that never finishes is an error, not a hang) and resets it. */
    void await(long fence) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long fences = MemoryUtil.memAddress(stack.longs(fence));
            int waited = vk.waitForFences.callInt(device, 1, fences, 1, FENCE_TIMEOUT);
            if (waited == Vk.TIMEOUT) throw new DlssException("blockland.graphics.status.dlss.vulkan", "previous frame did not finish");
            Vk.check(waited, "vkWaitForFences");
            Vk.check(vk.resetFences.callInt(device, 1, fences), "vkResetFences");
        }
    }

    /** A plain binary semaphore (not shared). */
    long semaphore() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long info = Vk.struct(stack, 24, Vk.ST_SEMAPHORE_CREATE_INFO, 0);
            long out = MemoryUtil.memAddress(stack.callocLong(1));
            Vk.check(vk.createSemaphore.callInt(device, info, 0, out), "vkCreateSemaphore");
            return MemoryUtil.memGetLong(out);
        }
    }

    /** A pair of shared semaphores that hand a set of images from OpenGL to Vulkan and back. */
    Handoff handoff() {
        return new Handoff();
    }

    /** One image in exportable Vulkan memory, imported as a GL texture (layout GENERAL once {@link #initialLayouts} ran). */
    SharedImage image(String label, int width, int height, int vkFormat, int glFormat, int usage) {
        SharedImage image = new SharedImage(label, width, height, vkFormat, usage);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long external = Vk.struct(stack, 24, Vk.ST_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, 0);
            MemoryUtil.memPutInt(external + 16, Vk.HANDLE_OPAQUE_WIN32);
            long info = Vk.struct(stack, 88, Vk.ST_IMAGE_CREATE_INFO, external);
            MemoryUtil.memPutInt(info + 20, 1);          // 2D
            MemoryUtil.memPutInt(info + 24, vkFormat);
            MemoryUtil.memPutInt(info + 28, width);
            MemoryUtil.memPutInt(info + 32, height);
            MemoryUtil.memPutInt(info + 36, 1);
            MemoryUtil.memPutInt(info + 40, 1);          // mip levels
            MemoryUtil.memPutInt(info + 44, 1);          // layers
            MemoryUtil.memPutInt(info + 48, 1);          // samples
            MemoryUtil.memPutInt(info + 52, 0);          // optimal tiling
            MemoryUtil.memPutInt(info + 56, usage);
            MemoryUtil.memPutInt(info + 60, 0);          // exclusive
            MemoryUtil.memPutInt(info + 80, Vk.LAYOUT_UNDEFINED);
            long out = MemoryUtil.memAddress(stack.callocLong(1));
            Vk.check(vk.createImage.callInt(device, info, 0, out), "vkCreateImage(" + label + ")");
            image.image = MemoryUtil.memGetLong(out);

            long requirements = MemoryUtil.memAddress(stack.calloc(8, 24));
            vk.getImageMemoryRequirements.call(device, image.image, requirements);
            long size = MemoryUtil.memGetLong(requirements);
            int typeBits = MemoryUtil.memGetInt(requirements + 16);
            long export = Vk.struct(stack, 24, Vk.ST_EXPORT_MEMORY_ALLOCATE_INFO, 0);
            MemoryUtil.memPutInt(export + 16, Vk.HANDLE_OPAQUE_WIN32);
            long dedicated = Vk.struct(stack, 32, Vk.ST_MEMORY_DEDICATED_ALLOCATE_INFO, export);
            MemoryUtil.memPutLong(dedicated + 16, image.image);
            long alloc = Vk.struct(stack, 32, Vk.ST_MEMORY_ALLOCATE_INFO, dedicated);
            MemoryUtil.memPutLong(alloc + 16, size);
            MemoryUtil.memPutInt(alloc + 24, memoryType(typeBits));
            Vk.check(vk.allocateMemory.callInt(device, alloc, 0, out), "vkAllocateMemory(" + label + ")");
            image.memory = MemoryUtil.memGetLong(out);
            Vk.check(vk.bindImageMemory.callInt(device, image.image, image.memory, 0), "vkBindImageMemory(" + label + ")");

            long view = Vk.struct(stack, 80, Vk.ST_IMAGE_VIEW_CREATE_INFO, 0);
            MemoryUtil.memPutLong(view + 24, image.image);
            MemoryUtil.memPutInt(view + 32, 1);          // 2D
            MemoryUtil.memPutInt(view + 36, vkFormat);
            MemoryUtil.memPutInt(view + 56, 1);          // aspect colour
            MemoryUtil.memPutInt(view + 64, 1);          // level count
            MemoryUtil.memPutInt(view + 72, 1);          // layer count
            Vk.check(vk.createImageView.callInt(device, view, 0, out), "vkCreateImageView(" + label + ")");
            image.view = MemoryUtil.memGetLong(out);

            long handleInfo = Vk.struct(stack, 32, Vk.ST_MEMORY_GET_WIN32_HANDLE_INFO_KHR, 0);
            MemoryUtil.memPutLong(handleInfo + 16, image.memory);
            MemoryUtil.memPutInt(handleInfo + 24, Vk.HANDLE_OPAQUE_WIN32);
            Vk.check(vk.getMemoryWin32Handle.callInt(device, handleInfo, out), "vkGetMemoryWin32HandleKHR(" + label + ")");
            long handle = MemoryUtil.memGetLong(out);

            image.glMemory = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObject.glMemoryObjectParameteriEXT(image.glMemory, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL11.GL_TRUE);
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(image.glMemory, size, EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
            Win32.closeHandle(handle);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            image.glTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, image.glTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT, EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
            EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, glFormat, width, height, image.glMemory, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                image.destroy();
                throw new DlssException("blockland.graphics.status.dlss.no_interop", "GL error " + error + " importing " + label);
            }
        } catch (RuntimeException e) {
            image.destroy();
            throw e;
        }
        return image;
    }

    /**
     * New images start undefined: makes them GENERAL and hands them to OpenGL once (with {@code commandBuffer}, guarded by
     * {@code fence}; the images' handoff semaphore is waited on by OpenGL).
     */
    void initialLayouts(long commandBuffer, long fence, Handoff handoff, SharedImage... images) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            await(fence);
            begin(stack, commandBuffer);
            long barriers = MemoryUtil.memAddress(stack.calloc(8, 72 * images.length));
            for (int i = 0; i < images.length; i++) {
                Vk.imageBarrier(barriers + 72L * i, 0, 0, Vk.LAYOUT_UNDEFINED, Vk.LAYOUT_GENERAL, queueFamily, Vk.QUEUE_FAMILY_EXTERNAL,
                        images[i].image);
            }
            vk.cmdPipelineBarrier.call(commandBuffer, Vk.STAGE_TOP_OF_PIPE, Vk.STAGE_BOTTOM_OF_PIPE, 0, 0, 0, 0, 0, images.length, barriers);
            Vk.check(vk.endCommandBuffer.callInt(commandBuffer), "vkEndCommandBuffer");
            submit(stack, commandBuffer, new long[0], new int[0], new long[]{handoff.vkDone}, fence);
            handoff.glWait(images);
        }
    }

    /** Begins a one-time-submit command buffer. */
    void begin(MemoryStack stack, long commandBuffer) {
        long begin = Vk.struct(stack, 32, Vk.ST_COMMAND_BUFFER_BEGIN_INFO, 0);
        MemoryUtil.memPutInt(begin + 16, 1);
        Vk.check(vk.beginCommandBuffer.callInt(commandBuffer, begin), "vkBeginCommandBuffer");
    }

    /** Hands images from OpenGL to the queue ({@code acquire}) or back, all in layout GENERAL. */
    void handBarriers(MemoryStack stack, long commandBuffer, boolean acquire, SharedImage... images) {
        long barriers = MemoryUtil.memAddress(stack.calloc(8, 72 * images.length));
        int access = Vk.ACCESS_SHADER_READ | Vk.ACCESS_SHADER_WRITE | Vk.ACCESS_TRANSFER_READ;
        for (int i = 0; i < images.length; i++) {
            Vk.imageBarrier(barriers + 72L * i, acquire ? 0 : access, acquire ? access : 0, Vk.LAYOUT_GENERAL, Vk.LAYOUT_GENERAL,
                    acquire ? Vk.QUEUE_FAMILY_EXTERNAL : queueFamily, acquire ? queueFamily : Vk.QUEUE_FAMILY_EXTERNAL, images[i].image);
        }
        vk.cmdPipelineBarrier.call(commandBuffer, acquire ? Vk.STAGE_TOP_OF_PIPE : Vk.STAGE_ALL_COMMANDS,
                acquire ? Vk.STAGE_ALL_COMMANDS : Vk.STAGE_BOTTOM_OF_PIPE, 0, 0, 0, 0, 0, images.length, barriers);
    }

    /** Submits one command buffer waiting for {@code waits} (at {@code stages}) and signalling {@code signals}. */
    void submit(MemoryStack stack, long commandBuffer, long[] waits, int[] stages, long[] signals, long fence) {
        long info = Vk.struct(stack, 72, Vk.ST_SUBMIT_INFO, 0);
        if (waits.length > 0) {
            MemoryUtil.memPutInt(info + 16, waits.length);
            MemoryUtil.memPutAddress(info + 24, MemoryUtil.memAddress(stack.longs(waits)));
            MemoryUtil.memPutAddress(info + 32, MemoryUtil.memAddress(stack.ints(stages)));
        }
        MemoryUtil.memPutInt(info + 40, 1);
        MemoryUtil.memPutAddress(info + 48, MemoryUtil.memAddress(stack.pointers(commandBuffer)));
        if (signals.length > 0) {
            MemoryUtil.memPutInt(info + 56, signals.length);
            MemoryUtil.memPutAddress(info + 64, MemoryUtil.memAddress(stack.longs(signals)));
        }
        Vk.check(vk.queueSubmit.callInt(queue, 1, info, fence), "vkQueueSubmit");
    }

    private int memoryType(int typeBits) {
        long props = MemoryUtil.memAddress(memoryProperties);
        int count = MemoryUtil.memGetInt(props);
        for (int i = 0; i < count; i++) {
            int flags = MemoryUtil.memGetInt(props + 4 + 8L * i);
            if ((typeBits & (1 << i)) != 0 && (flags & Vk.MEMORY_DEVICE_LOCAL) != 0) return i;
        }
        for (int i = 0; i < count; i++) if ((typeBits & (1 << i)) != 0) return i;
        throw new DlssException("blockland.graphics.status.dlss.vulkan", "no memory type");
    }

    void waitIdle() {
        if (device != 0) Vk.check(vk.deviceWaitIdle.callInt(device), "vkDeviceWaitIdle");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (vk != null && device != 0) vk.deviceWaitIdle.callInt(device);
            if (viewport != 0) sl.freeResources(viewport);
        } finally {
            // Streamline goes before the device and instance it was given
            sl.shutdown();
            if (vk != null && device != 0) vk.destroyDevice.call(device, 0);
            if (vk != null && instance != 0 && vk.destroyInstance != null) vk.destroyInstance.call(instance, 0);
            device = instance = 0;
            MemoryUtil.memFree(memoryProperties);
        }
    }

    /** A texture both sides use: its Vulkan image, memory and view, and the GL texture over the same memory. */
    final class SharedImage {
        final String label;
        final int width, height, vkFormat, usage;
        long image, memory, view;
        int glMemory, glTexture;

        SharedImage(String label, int width, int height, int vkFormat, int usage) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.vkFormat = vkFormat;
            this.usage = usage;
        }

        /** This image as Streamline's sl::Resource at {@code at} (layout GENERAL). */
        void resource(long at) {
            Streamline.resource(at, image, memory, view, width, height, vkFormat, usage);
        }

        void destroy() {
            if (glTexture != 0) GL11.glDeleteTextures(glTexture);
            if (glMemory != 0) EXTMemoryObject.glDeleteMemoryObjectsEXT(glMemory);
            if (view != 0) vk.destroyImageView.call(device, view, 0);
            if (image != 0) vk.destroyImage.call(device, image, 0);
            if (memory != 0) vk.freeMemory.call(device, memory, 0);
            glTexture = glMemory = 0;
            view = image = memory = 0;
        }
    }

    /**
     * Two shared binary semaphores: OpenGL signals {@code ready} when it has written a set of images, Vulkan signals
     * {@code done} when it is finished with them, and OpenGL waits on that (on the GPU) before touching them again. Each
     * signal is matched by exactly one wait.
     */
    final class Handoff {
        final long vkReady, vkDone;
        private final int glReady, glDone;

        private Handoff() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkReady = exported(stack);
                glReady = imported(stack, vkReady);
                vkDone = exported(stack);
                glDone = imported(stack, vkDone);
            }
        }

        /** OpenGL is done writing {@code images}. */
        void glSignal(SharedImage... images) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                EXTSemaphore.glSignalSemaphoreEXT(glReady, stack.mallocInt(0), textures(stack, images), layouts(stack, images.length));
                GL11.glFlush();
            }
        }

        /** OpenGL's next commands wait (on the GPU) for Vulkan to be done with {@code images}. */
        void glWait(SharedImage... images) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                EXTSemaphore.glWaitSemaphoreEXT(glDone, stack.mallocInt(0), textures(stack, images), layouts(stack, images.length));
            }
        }

        private IntBuffer textures(MemoryStack stack, SharedImage[] images) {
            IntBuffer textures = stack.mallocInt(images.length);
            for (int i = 0; i < images.length; i++) textures.put(i, images[i].glTexture);
            return textures;
        }

        private IntBuffer layouts(MemoryStack stack, int count) {
            IntBuffer layouts = stack.mallocInt(count);
            for (int i = 0; i < count; i++) layouts.put(i, EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
            return layouts;
        }

        private long exported(MemoryStack stack) {
            long export = Vk.struct(stack, 24, Vk.ST_EXPORT_SEMAPHORE_CREATE_INFO, 0);
            MemoryUtil.memPutInt(export + 16, Vk.HANDLE_OPAQUE_WIN32);
            long info = Vk.struct(stack, 24, Vk.ST_SEMAPHORE_CREATE_INFO, export);
            long out = MemoryUtil.memAddress(stack.callocLong(1));
            Vk.check(vk.createSemaphore.callInt(device, info, 0, out), "vkCreateSemaphore");
            return MemoryUtil.memGetLong(out);
        }

        private int imported(MemoryStack stack, long semaphore) {
            long info = Vk.struct(stack, 32, Vk.ST_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR, 0);
            MemoryUtil.memPutLong(info + 16, semaphore);
            MemoryUtil.memPutInt(info + 24, Vk.HANDLE_OPAQUE_WIN32);
            long out = MemoryUtil.memAddress(stack.callocPointer(1));
            Vk.check(vk.getSemaphoreWin32Handle.callInt(device, info, out), "vkGetSemaphoreWin32HandleKHR");
            long handle = MemoryUtil.memGetAddress(out);
            int gl = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(gl, EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
            Win32.closeHandle(handle);
            return gl;
        }

        void destroy() {
            EXTSemaphore.glDeleteSemaphoresEXT(glReady);
            EXTSemaphore.glDeleteSemaphoresEXT(glDone);
            vk.destroySemaphore.call(device, vkReady, 0);
            vk.destroySemaphore.call(device, vkDone, 0);
        }
    }
}
