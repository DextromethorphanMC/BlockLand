package com.blockland.client.graphics.upscale.dlss;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.I32;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.I64;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.PTR;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.U32;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.VOID;

/**
 * The slice of Vulkan the DLSS bridge uses, called through Streamline's {@code vkGetInstanceProcAddr} /
 * {@code vkGetDeviceProcAddr} proxies (they return the loader's functions except the few Streamline intercepts, such as
 * instance and device creation, where it adds what DLSS needs). Structures are written by hand at their x64 offsets
 * (checked against LWJGL's Vulkan bindings: see {@code tools/dlss/README.md}).
 */
final class Vk {
    // structure types
    static final int ST_APPLICATION_INFO = 0, ST_INSTANCE_CREATE_INFO = 1, ST_DEVICE_QUEUE_CREATE_INFO = 2, ST_DEVICE_CREATE_INFO = 3,
            ST_SUBMIT_INFO = 4, ST_MEMORY_ALLOCATE_INFO = 5, ST_FENCE_CREATE_INFO = 8, ST_SEMAPHORE_CREATE_INFO = 9,
            ST_IMAGE_CREATE_INFO = 14, ST_IMAGE_VIEW_CREATE_INFO = 15, ST_COMMAND_POOL_CREATE_INFO = 39,
            ST_COMMAND_BUFFER_ALLOCATE_INFO = 40, ST_COMMAND_BUFFER_BEGIN_INFO = 42, ST_IMAGE_MEMORY_BARRIER = 45,
            ST_PRESENT_INFO_KHR = 1000001001, ST_PHYSICAL_DEVICE_PROPERTIES_2 = 1000059001, ST_PHYSICAL_DEVICE_ID_PROPERTIES = 1000071004,
            ST_EXTERNAL_MEMORY_IMAGE_CREATE_INFO = 1000072001, ST_EXPORT_MEMORY_ALLOCATE_INFO = 1000072002,
            ST_MEMORY_GET_WIN32_HANDLE_INFO_KHR = 1000073003, ST_EXPORT_SEMAPHORE_CREATE_INFO = 1000077000,
            ST_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR = 1000078003, ST_MEMORY_DEDICATED_ALLOCATE_INFO = 1000127001,
            ST_SWAPCHAIN_CREATE_INFO_KHR = 1000001000, ST_WIN32_SURFACE_CREATE_INFO_KHR = 1000009000;
    // formats
    static final int FORMAT_R16G16B16A16_SFLOAT = 97, FORMAT_R16G16_SFLOAT = 83, FORMAT_R32_SFLOAT = 100, FORMAT_R8G8B8A8_UNORM = 37,
            FORMAT_B8G8R8A8_UNORM = 44, FORMAT_R8_UNORM = 9;
    // image usage
    static final int USAGE_TRANSFER_SRC = 1, USAGE_TRANSFER_DST = 2, USAGE_SAMPLED = 4, USAGE_STORAGE = 8, USAGE_COLOR_ATTACHMENT = 0x10;
    static final int LAYOUT_UNDEFINED = 0, LAYOUT_GENERAL = 1, LAYOUT_TRANSFER_DST = 7, LAYOUT_PRESENT_SRC = 1000001002;
    static final int PRESENT_IMMEDIATE = 0, PRESENT_MAILBOX = 1, PRESENT_FIFO = 2;
    static final int HANDLE_OPAQUE_WIN32 = 0x2;
    static final int MEMORY_DEVICE_LOCAL = 0x1;
    static final int QUEUE_GRAPHICS = 0x1, QUEUE_COMPUTE = 0x2;
    static final int QUEUE_FAMILY_EXTERNAL = -2;
    static final int STAGE_TOP_OF_PIPE = 0x1, STAGE_TRANSFER = 0x1000, STAGE_BOTTOM_OF_PIPE = 0x2000, STAGE_ALL_COMMANDS = 0x10000;
    static final int ACCESS_SHADER_READ = 0x20, ACCESS_SHADER_WRITE = 0x40, ACCESS_TRANSFER_READ = 0x800, ACCESS_TRANSFER_WRITE = 0x1000,
            ACCESS_MEMORY_READ = 0x8000, ACCESS_MEMORY_WRITE = 0x10000;
    static final int API_VERSION_1_3 = (1 << 22) | (3 << 12);
    static final int SUCCESS = 0, TIMEOUT = 2, SUBOPTIMAL = 1000001003, ERROR_OUT_OF_DATE = -1000001004;

    private final Ffi.Fn getInstanceProcAddr, getDeviceProcAddr;
    long instance, device;

    // instance level
    Ffi.Fn createInstance, destroyInstance, enumeratePhysicalDevices, getPhysicalDeviceProperties2,
            getPhysicalDeviceQueueFamilyProperties, getPhysicalDeviceMemoryProperties, createDevice, enumerateDeviceExtensionProperties;
    // instance level, presentation (only with the surface extensions)
    Ffi.Fn createWin32Surface, destroySurface, getSurfaceSupport, getSurfaceCapabilities, getSurfaceFormats, getSurfacePresentModes;
    // device level
    Ffi.Fn destroyDevice, getDeviceQueue, deviceWaitIdle, createCommandPool, destroyCommandPool, allocateCommandBuffers,
            beginCommandBuffer, endCommandBuffer, cmdPipelineBarrier, queueSubmit, queueWaitIdle, createFence, destroyFence,
            waitForFences, resetFences, createSemaphore, destroySemaphore, getSemaphoreWin32Handle, createImage, destroyImage,
            getImageMemoryRequirements, allocateMemory, freeMemory, bindImageMemory, getMemoryWin32Handle, createImageView,
            destroyImageView;
    // device level, presentation (only with the swapchain extension)
    Ffi.Fn createSwapchain, destroySwapchain, getSwapchainImages, acquireNextImage, queuePresent, cmdBlitImage, cmdClearColorImage;

    Vk(long getInstanceProcAddr, long getDeviceProcAddr) {
        this.getInstanceProcAddr = Ffi.fn("vkGetInstanceProcAddr", getInstanceProcAddr, PTR, PTR, PTR);
        this.getDeviceProcAddr = Ffi.fn("vkGetDeviceProcAddr", getDeviceProcAddr, PTR, PTR, PTR);
        createInstance = instanceFn("vkCreateInstance", I32, PTR, PTR, PTR);
    }

    private Ffi.Fn instanceFn(String name, Ffi.Type ret, Ffi.Type... args) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return Ffi.fn(name, getInstanceProcAddr.call(instance, MemoryUtil.memAddress(stack.ASCII(name))), ret, args);
        }
    }

    private Ffi.Fn deviceFn(String name, Ffi.Type ret, Ffi.Type... args) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return Ffi.fn(name, getDeviceProcAddr.call(device, MemoryUtil.memAddress(stack.ASCII(name))), ret, args);
        }
    }

    /** After {@code vkCreateInstance}: the instance-level functions. */
    void loadInstance(long instance) {
        this.instance = instance;
        destroyInstance = instanceFn("vkDestroyInstance", VOID, PTR, PTR);
        enumeratePhysicalDevices = instanceFn("vkEnumeratePhysicalDevices", I32, PTR, PTR, PTR);
        enumerateDeviceExtensionProperties = instanceFn("vkEnumerateDeviceExtensionProperties", I32, PTR, PTR, PTR, PTR);
        getPhysicalDeviceProperties2 = instanceFn("vkGetPhysicalDeviceProperties2", VOID, PTR, PTR);
        getPhysicalDeviceQueueFamilyProperties = instanceFn("vkGetPhysicalDeviceQueueFamilyProperties", VOID, PTR, PTR, PTR);
        getPhysicalDeviceMemoryProperties = instanceFn("vkGetPhysicalDeviceMemoryProperties", VOID, PTR, PTR);
        createDevice = instanceFn("vkCreateDevice", I32, PTR, PTR, PTR, PTR);
    }

    /** The instance-level surface functions (the instance was created with VK_KHR_surface and VK_KHR_win32_surface). */
    void loadSurface() {
        createWin32Surface = instanceFn("vkCreateWin32SurfaceKHR", I32, PTR, PTR, PTR, PTR);
        destroySurface = instanceFn("vkDestroySurfaceKHR", VOID, PTR, I64, PTR);
        getSurfaceSupport = instanceFn("vkGetPhysicalDeviceSurfaceSupportKHR", I32, PTR, U32, I64, PTR);
        getSurfaceCapabilities = instanceFn("vkGetPhysicalDeviceSurfaceCapabilitiesKHR", I32, PTR, I64, PTR);
        getSurfaceFormats = instanceFn("vkGetPhysicalDeviceSurfaceFormatsKHR", I32, PTR, I64, PTR, PTR);
        getSurfacePresentModes = instanceFn("vkGetPhysicalDeviceSurfacePresentModesKHR", I32, PTR, I64, PTR, PTR);
    }

    /**
     * The swapchain functions (the device was created with VK_KHR_swapchain). Through Streamline's proxy these are its
     * hooks: with DLSS Frame Generation loaded it owns the real swapchain and inserts the generated frames at present.
     */
    void loadSwapchain() {
        createSwapchain = deviceFn("vkCreateSwapchainKHR", I32, PTR, PTR, PTR, PTR);
        destroySwapchain = deviceFn("vkDestroySwapchainKHR", VOID, PTR, I64, PTR);
        getSwapchainImages = deviceFn("vkGetSwapchainImagesKHR", I32, PTR, I64, PTR, PTR);
        acquireNextImage = deviceFn("vkAcquireNextImageKHR", I32, PTR, I64, I64, I64, I64, PTR);
        queuePresent = deviceFn("vkQueuePresentKHR", I32, PTR, PTR);
        cmdBlitImage = deviceFn("vkCmdBlitImage", VOID, PTR, I64, U32, I64, U32, U32, PTR, U32);
        // the black bars around a picture of another shape than the window (rendering spec §21)
        cmdClearColorImage = deviceFn("vkCmdClearColorImage", VOID, PTR, I64, U32, PTR, U32, PTR);
    }

    /** After {@code vkCreateDevice}: the device-level functions. */
    void loadDevice(long device) {
        this.device = device;
        destroyDevice = deviceFn("vkDestroyDevice", VOID, PTR, PTR);
        getDeviceQueue = deviceFn("vkGetDeviceQueue", VOID, PTR, U32, U32, PTR);
        deviceWaitIdle = deviceFn("vkDeviceWaitIdle", I32, PTR);
        createCommandPool = deviceFn("vkCreateCommandPool", I32, PTR, PTR, PTR, PTR);
        destroyCommandPool = deviceFn("vkDestroyCommandPool", VOID, PTR, I64, PTR);
        allocateCommandBuffers = deviceFn("vkAllocateCommandBuffers", I32, PTR, PTR, PTR);
        beginCommandBuffer = deviceFn("vkBeginCommandBuffer", I32, PTR, PTR);
        endCommandBuffer = deviceFn("vkEndCommandBuffer", I32, PTR);
        cmdPipelineBarrier = deviceFn("vkCmdPipelineBarrier", VOID, PTR, U32, U32, U32, U32, PTR, U32, PTR, U32, PTR);
        queueSubmit = deviceFn("vkQueueSubmit", I32, PTR, U32, PTR, I64);
        queueWaitIdle = deviceFn("vkQueueWaitIdle", I32, PTR);
        createFence = deviceFn("vkCreateFence", I32, PTR, PTR, PTR, PTR);
        destroyFence = deviceFn("vkDestroyFence", VOID, PTR, I64, PTR);
        waitForFences = deviceFn("vkWaitForFences", I32, PTR, U32, PTR, U32, I64);
        resetFences = deviceFn("vkResetFences", I32, PTR, U32, PTR);
        createSemaphore = deviceFn("vkCreateSemaphore", I32, PTR, PTR, PTR, PTR);
        destroySemaphore = deviceFn("vkDestroySemaphore", VOID, PTR, I64, PTR);
        getSemaphoreWin32Handle = deviceFn("vkGetSemaphoreWin32HandleKHR", I32, PTR, PTR, PTR);
        createImage = deviceFn("vkCreateImage", I32, PTR, PTR, PTR, PTR);
        destroyImage = deviceFn("vkDestroyImage", VOID, PTR, I64, PTR);
        getImageMemoryRequirements = deviceFn("vkGetImageMemoryRequirements", VOID, PTR, I64, PTR);
        allocateMemory = deviceFn("vkAllocateMemory", I32, PTR, PTR, PTR, PTR);
        freeMemory = deviceFn("vkFreeMemory", VOID, PTR, I64, PTR);
        bindImageMemory = deviceFn("vkBindImageMemory", I32, PTR, I64, I64, I64);
        getMemoryWin32Handle = deviceFn("vkGetMemoryWin32HandleKHR", I32, PTR, PTR, PTR);
        createImageView = deviceFn("vkCreateImageView", I32, PTR, PTR, PTR, PTR);
        destroyImageView = deviceFn("vkDestroyImageView", VOID, PTR, I64, PTR);
    }

    /** Throws when a Vulkan call did not succeed. */
    static void check(int result, String call) {
        if (result != SUCCESS) throw new DlssException("blockland.graphics.status.dlss.vulkan", call + " = " + result);
    }

    // ---- structure helpers ------------------------------------------------------------------------------------------

    /** A zeroed structure of {@code size} bytes with its sType and pNext. */
    static long struct(MemoryStack stack, int size, int sType, long next) {
        ByteBuffer buffer = stack.calloc(8, size);
        long address = MemoryUtil.memAddress(buffer);
        MemoryUtil.memPutInt(address, sType);
        MemoryUtil.memPutAddress(address + 8, next);
        return address;
    }

    /** One VkImageMemoryBarrier (72 bytes) at {@code at}: colour, one mip, one layer. */
    static void imageBarrier(long at, int srcAccess, int dstAccess, int oldLayout, int newLayout, int srcFamily, int dstFamily, long image) {
        MemoryUtil.memSet(at, 0, 72);
        MemoryUtil.memPutInt(at, ST_IMAGE_MEMORY_BARRIER);
        MemoryUtil.memPutInt(at + 16, srcAccess);
        MemoryUtil.memPutInt(at + 20, dstAccess);
        MemoryUtil.memPutInt(at + 24, oldLayout);
        MemoryUtil.memPutInt(at + 28, newLayout);
        MemoryUtil.memPutInt(at + 32, srcFamily);
        MemoryUtil.memPutInt(at + 36, dstFamily);
        MemoryUtil.memPutLong(at + 40, image);
        MemoryUtil.memPutInt(at + 48, 1); // aspect colour
        MemoryUtil.memPutInt(at + 52, 0);
        MemoryUtil.memPutInt(at + 56, 1);
        MemoryUtil.memPutInt(at + 60, 0);
        MemoryUtil.memPutInt(at + 64, 1);
    }
}
