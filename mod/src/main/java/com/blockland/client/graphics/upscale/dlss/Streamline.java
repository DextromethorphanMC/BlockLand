package com.blockland.client.graphics.upscale.dlss;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.I32;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.I64;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.PTR;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.U32;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.U8;

/**
 * NVIDIA Streamline 2.x through its {@code sl.interposer.dll} exports, integrated the "manual hooking" way: BlockLand creates
 * its own Vulkan instance and device through Streamline's creation proxies and drives everything else itself. Streamline's
 * structures are C++ structs deriving from {@code sl::BaseStructure} (next pointer, 16-byte struct GUID, version); they are
 * written here at their x64 offsets, which match the offsets Streamline's own sources assert (Preferences.renderAPI 136,
 * ResourceTag.extent 48, Resource.internalFlags 104, Constants.motionVectorsJittered 450).
 */
final class Streamline {
    /** The SDK these structures follow (2.14.16); Streamline accepts older-or-equal host versions. */
    static final long SDK_VERSION = (2L << 48) | (14L << 32) | (16L << 16) | 0xfedcL;
    static final int FEATURE_DLSS = 0, FEATURE_REFLEX = 3, FEATURE_PCL = 4, FEATURE_DLSS_G = 1000, FEATURE_DLSS_RR = 1001;
    static final int BUFFER_DEPTH = 0, BUFFER_MOTION_VECTORS = 1, BUFFER_HUDLESS = 2, BUFFER_SCALING_INPUT = 3, BUFFER_SCALING_OUTPUT = 4,
            BUFFER_UI_COLOR_ALPHA = 23, BUFFER_ALBEDO = 7, BUFFER_SPECULAR_ALBEDO = 8, BUFFER_NORMAL_ROUGHNESS = 14;
    static final int ONLY_VALID_NOW = 0, VALID_UNTIL_EVALUATE = 2;
    /** PCL latency markers. */
    static final int SIMULATION_START = 0, SIMULATION_END = 1, RENDER_SUBMIT_START = 2, RENDER_SUBMIT_END = 3, PRESENT_START = 4,
            PRESENT_END = 5;
    /** DLSSGMode, DLSSGFlags.eRetainResourcesWhenOff. */
    static final int DLSSG_OFF = 0, DLSSG_ON = 1, DLSSG_RETAIN_RESOURCES = 1 << 3;
    static final int RESOURCE_TEX2D = 0;
    static final byte FALSE = 0, TRUE = 1;
    private static final long FLAG_DISABLE_CL_STATE_TRACKING = 1, FLAG_DISABLE_DEBUG_TEXT = 2, FLAG_MANUAL_HOOKING = 4,
            FLAG_FRAME_BASED_TAGGING = 1 << 7;
    private static final int RENDER_API_VULKAN = 2;
    private static final float INVALID_FLOAT = Float.MAX_VALUE;
    /** BlockLand's project id for NGX (custom engines identify by project GUID + engine version). */
    static final String PROJECT_ID = "6f1c2a8e-4b7d-4e93-a5c1-9d2e7b40f318";
    private static final String[] RESULTS = {"eOk", "eErrorIO", "eErrorDriverOutOfDate", "eErrorOSOutOfDate", "eErrorOSDisabledHWS",
            "eErrorDeviceNotCreated", "eErrorNoSupportedAdapterFound", "eErrorAdapterNotSupported", "eErrorNoPlugins", "eErrorVulkanAPI",
            "eErrorDXGIAPI", "eErrorD3DAPI", "eErrorNRDAPI", "eErrorNVAPI", "eErrorReflexAPI", "eErrorNGXFailed", "eErrorJSONParsing",
            "eErrorMissingProxy", "eErrorMissingResourceState", "eErrorInvalidIntegration", "eErrorMissingInputParameter",
            "eErrorNotInitialized", "eErrorComputeFailed", "eErrorInitNotCalled", "eErrorExceptionHandler", "eErrorInvalidParameter",
            "eErrorMissingConstants", "eErrorDuplicatedConstants", "eErrorMissingOrInvalidAPI", "eErrorCommonConstantsMissing",
            "eErrorUnsupportedInterface", "eErrorFeatureMissing", "eErrorFeatureNotSupported", "eErrorFeatureMissingHooks",
            "eErrorFeatureFailedToLoad", "eErrorFeatureWrongPriority", "eErrorFeatureMissingDependency", "eErrorFeatureManagerInvalidState",
            "eErrorInvalidState", "eWarnOutOfVRAM"};

    // struct GUIDs: data1, data2, data3, data4[8]
    private static final long[] PREFERENCES = guid(0x1ca10965, 0xbf8e, 0x432b, 0x8d, 0xa1, 0x67, 0x16, 0xd8, 0x79, 0xfb, 0x14);
    private static final long[] VIEWPORT = guid(0x171b6435, 0x9b3c, 0x4fc8, 0x99, 0x94, 0xfb, 0xe5, 0x25, 0x69, 0xaa, 0xa4);
    private static final long[] RESOURCE = guid(0x3a9d70cf, 0x2418, 0x4b72, 0x83, 0x91, 0x13, 0xf8, 0x72, 0x1c, 0x72, 0x61);
    private static final long[] RESOURCE_TAG = guid(0x4c6a5aad, 0xb445, 0x496c, 0x87, 0xff, 0x1a, 0xf3, 0x84, 0x5b, 0xe6, 0x53);
    private static final long[] CONSTANTS = guid(0xdcd35ad7, 0x4e4a, 0x4bad, 0xa9, 0x0c, 0xe0, 0xc4, 0x9e, 0xb2, 0x3a, 0xfe);
    private static final long[] DLSS_OPTIONS = guid(0x6ac826e4, 0x4c61, 0x4101, 0xa9, 0x2d, 0x63, 0x8d, 0x42, 0x10, 0x57, 0xb8);
    private static final long[] DLSS_OPTIMAL = guid(0xef1d0957, 0xfd58, 0x4df7, 0xb5, 0x04, 0x8b, 0x69, 0xd8, 0xaa, 0x6b, 0x76);
    private static final long[] ADAPTER_INFO = guid(0x0677315f, 0xa746, 0x4492, 0x9f, 0x42, 0xcb, 0x61, 0x42, 0xc9, 0xc3, 0xd4);
    private static final long[] FEATURE_VERSION = guid(0x6d5b51f0, 0x076b, 0x486d, 0x99, 0x95, 0x5a, 0x56, 0x10, 0x43, 0xf5, 0xc1);
    private static final long[] DLSSG_OPTIONS = guid(0xfac5f1cb, 0x2dfd, 0x4f36, 0xa1, 0xe6, 0x3a, 0x9e, 0x86, 0x52, 0x56, 0xc5);
    private static final long[] DLSSG_STATE = guid(0xcc8ac8e1, 0xa179, 0x44f5, 0x97, 0xfa, 0xe7, 0x41, 0x12, 0xf9, 0xbc, 0x61);
    private static final long[] REFLEX_OPTIONS = guid(0xf03af81a, 0x6d0b, 0x4902, 0xa6, 0x51, 0xc4, 0x96, 0x5e, 0x21, 0x54, 0x34);
    private static final long[] DLSSD_OPTIONS = guid(0x0ad87504, 0x774e, 0x4bf3, 0x96, 0x33, 0xa4, 0x4d, 0x1f, 0x7f, 0x9c, 0xb8);

    static final int VIEWPORT_SIZE = 40, RESOURCE_SIZE = 112, TAG_SIZE = 64, CONSTANTS_SIZE = 456, DLSS_OPTIONS_SIZE = 88;

    final long module;
    private final Ffi.Fn init, shutdown, isFeatureSupported, getFeatureVersion, getFeatureFunction, getNewFrameToken, setConstants,
            setTagForFrame, evaluateFeature, freeResources;
    private final Ffi.Fn setFeatureLoaded;
    private Ffi.Fn dlssGetOptimalSettings, dlssSetOptions;
    private Ffi.Fn dlssgSetOptions, dlssgGetState, reflexSetOptions, reflexSleep, pclSetMarker, dlssdSetOptions;
    /** sl.common's present hooks (see {@link #present()}), or null when they could not be found. */
    private Ffi.Fn presentHook, afterPresentHook;
    /** Allocations that must outlive one call (handed to Streamline at init). */
    private final List<ByteBuffer> kept = new ArrayList<>();
    private boolean initialised;

    Streamline(Path folder) {
        module = Win32.load(folder.resolve("sl.interposer.dll"));
        init = export("slInit", I32, PTR, I64);
        shutdown = export("slShutdown", I32);
        isFeatureSupported = export("slIsFeatureSupported", I32, U32, PTR);
        getFeatureVersion = export("slGetFeatureVersion", I32, U32, PTR);
        getFeatureFunction = export("slGetFeatureFunction", I32, U32, PTR, PTR);
        getNewFrameToken = export("slGetNewFrameToken", I32, PTR, PTR);
        setConstants = export("slSetConstants", I32, PTR, PTR, PTR);
        setTagForFrame = export("slSetTagForFrame", I32, PTR, PTR, PTR, U32, PTR);
        evaluateFeature = export("slEvaluateFeature", I32, U32, PTR, PTR, U32, PTR);
        freeResources = export("slFreeResources", I32, U32, PTR);
        setFeatureLoaded = export("slSetFeatureLoaded", I32, U32, U8);
    }

    private Ffi.Fn export(String name, Ffi.Type ret, Ffi.Type... args) {
        return Ffi.fn(name, Win32.symbol(module, name), ret, args);
    }

    long proc(String name) {
        return Win32.symbol(module, name);
    }

    static String result(int code) {
        return code >= 0 && code < RESULTS.length ? RESULTS[code] : "result " + code;
    }

    static void check(int code, String call) {
        // out of VRAM is a warning: DLSS still ran
        if (code != 0 && code != RESULTS.length - 1) {
            throw new DlssException("blockland.graphics.status.dlss.streamline", call + " = " + result(code));
        }
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------------

    /**
     * slInit with manual hooking, Vulkan, no over-the-air updates; plugins from {@code pluginFolder}.
     *
     * @param features the features to load (DLSS always; DLSS Frame Generation with Reflex and PCL when presenting)
     */
    void init(Path pluginFolder, Path logFolder, String engineVersion, int... features) {
        ByteBuffer pluginPath = keep(MemoryUtil.memUTF16(pluginFolder.toAbsolutePath().toString()));
        ByteBuffer pluginPaths = keep(MemoryUtil.memAlloc(8));
        MemoryUtil.memPutAddress(MemoryUtil.memAddress(pluginPaths), MemoryUtil.memAddress(pluginPath));
        ByteBuffer logPath = keep(MemoryUtil.memUTF16(logFolder.toAbsolutePath().toString()));
        ByteBuffer featureList = keep(MemoryUtil.memAlloc(4 * features.length));
        for (int i = 0; i < features.length; i++) featureList.putInt(4 * i, features[i]);
        ByteBuffer engine = keep(MemoryUtil.memUTF8(engineVersion));
        ByteBuffer project = keep(MemoryUtil.memUTF8(PROJECT_ID));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long p = base(stack, 144, PREFERENCES, 1);
            MemoryUtil.memPutByte(p + 32, FALSE);                       // showConsole
            MemoryUtil.memPutInt(p + 36, 1);                            // logLevel: default
            MemoryUtil.memPutAddress(p + 40, MemoryUtil.memAddress(pluginPaths));
            MemoryUtil.memPutInt(p + 48, 1);                            // numPathsToPlugins
            MemoryUtil.memPutAddress(p + 56, MemoryUtil.memAddress(logPath));
            MemoryUtil.memPutLong(p + 88, FLAG_DISABLE_CL_STATE_TRACKING | FLAG_DISABLE_DEBUG_TEXT | FLAG_MANUAL_HOOKING
                    | FLAG_FRAME_BASED_TAGGING);
            MemoryUtil.memPutAddress(p + 96, MemoryUtil.memAddress(featureList));
            MemoryUtil.memPutInt(p + 104, features.length);             // numFeaturesToLoad
            MemoryUtil.memPutInt(p + 108, 0);                           // applicationId
            MemoryUtil.memPutInt(p + 112, 0);                           // engine: custom
            MemoryUtil.memPutAddress(p + 120, MemoryUtil.memAddress(engine));
            MemoryUtil.memPutAddress(p + 128, MemoryUtil.memAddress(project));
            MemoryUtil.memPutInt(p + 136, RENDER_API_VULKAN);
            check(init.callInt(p, SDK_VERSION), "slInit");
        }
        initialised = true;
        // sl.common is loaded by now: its Vulkan present hooks (see present())
        long common = Win32.loaded("sl.common.dll");
        long getPluginFunction = common == 0 ? 0 : Win32.symbol(common, "slGetPluginFunction");
        if (getPluginFunction != 0) {
            Ffi.Fn lookup = Ffi.fn("slGetPluginFunction", getPluginFunction, PTR, PTR);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long before = lookup.call(MemoryUtil.memAddress(stack.ASCII("slHookVkPresent")));
                long after = lookup.call(MemoryUtil.memAddress(stack.ASCII("slHookVkAfterPresent")));
                if (before != 0) presentHook = Ffi.fn("slHookVkPresent", before, I32, PTR, PTR, PTR);
                if (after != 0) afterPresentHook = Ffi.fn("slHookVkAfterPresent", after, I32);
            }
        }
    }

    /** Whether a feature runs on this physical device: 0 (eOk) or Streamline's result code. */
    int supported(int feature, long physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long adapter = base(stack, 56, ADAPTER_INFO, 1);
            MemoryUtil.memPutAddress(adapter + 48, physicalDevice);
            return isFeatureSupported.callInt(feature, adapter);
        }
    }

    /** After the Vulkan device exists: DLSS is supported on it and its functions are available. */
    void enableDlss(long physicalDevice) {
        int supported = supported(FEATURE_DLSS, physicalDevice);
        if (supported != 0) throw new DlssException("blockland.graphics.status.dlss.unsupported", result(supported));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            dlssGetOptimalSettings = featureFunction(stack, FEATURE_DLSS, "slDLSSGetOptimalSettings", I32, PTR, PTR);
            dlssSetOptions = featureFunction(stack, FEATURE_DLSS, "slDLSSSetOptions", I32, PTR, PTR);
        }
    }

    /**
     * After the Vulkan device exists: DLSS Frame Generation, Reflex and PCL are supported on it and their functions are
     * available (Frame Generation needs all three).
     */
    void enableFrameGeneration(long physicalDevice) {
        for (int feature : new int[]{FEATURE_DLSS_G, FEATURE_REFLEX, FEATURE_PCL}) {
            int supported = supported(feature, physicalDevice);
            if (supported != 0) {
                // the usual reasons: hardware-accelerated GPU scheduling off (eErrorOSDisabledHWS), a GPU before RTX 40, an old driver
                throw new DlssException(supported == 4 ? "blockland.graphics.status.fg.hags" : "blockland.graphics.status.fg.unsupported",
                        (feature == FEATURE_DLSS_G ? "DLSS-G" : feature == FEATURE_REFLEX ? "Reflex" : "PCL") + ": " + result(supported));
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            dlssgSetOptions = featureFunction(stack, FEATURE_DLSS_G, "slDLSSGSetOptions", I32, PTR, PTR);
            dlssgGetState = featureFunction(stack, FEATURE_DLSS_G, "slDLSSGGetState", I32, PTR, PTR, PTR);
            reflexSetOptions = featureFunction(stack, FEATURE_REFLEX, "slReflexSetOptions", I32, PTR);
            reflexSleep = featureFunction(stack, FEATURE_REFLEX, "slReflexSleep", I32, PTR);
            pclSetMarker = featureFunction(stack, FEATURE_PCL, "slPCLSetMarker", I32, U32, PTR);
        }
    }

    /** After the Vulkan device exists: DLSS Ray Reconstruction is supported on it and its options can be set. */
    void enableRayReconstruction(long physicalDevice) {
        int supported = supported(FEATURE_DLSS_RR, physicalDevice);
        if (supported != 0) throw new DlssException("blockland.graphics.status.rr.unsupported", result(supported));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            dlssdSetOptions = featureFunction(stack, FEATURE_DLSS_RR, "slDLSSDSetOptions", I32, PTR, PTR);
        }
    }

    /**
     * DLSS Ray Reconstruction's options (sl::DLSSDOptions, version 3): the quality mode and output size as for Super
     * Resolution, HDR colour, normals with roughness packed in alpha, the camera's world ↔ view matrices (row-major).
     */
    void rayReconstructionOptions(long viewport, int mode, int outputWidth, int outputHeight, float[] worldToView, float[] viewToWorld) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long o = base(stack, 224, DLSSD_OPTIONS, 3);
            MemoryUtil.memPutInt(o + 32, mode);
            MemoryUtil.memPutInt(o + 36, outputWidth);
            MemoryUtil.memPutInt(o + 40, outputHeight);
            MemoryUtil.memPutFloat(o + 44, 0f);     // sharpness: BlockLand's own pass sharpens
            MemoryUtil.memPutFloat(o + 48, 1f);     // preExposure
            MemoryUtil.memPutFloat(o + 52, 1f);     // exposureScale
            MemoryUtil.memPutByte(o + 56, TRUE);    // colour is linear HDR
            MemoryUtil.memPutByte(o + 57, FALSE);
            MemoryUtil.memPutByte(o + 58, FALSE);
            MemoryUtil.memPutInt(o + 60, 1);        // roughness packed into the normals' alpha
            matrix(o + 64, worldToView);
            matrix(o + 128, viewToWorld);
            MemoryUtil.memPutByte(o + 192, FALSE);  // no alpha upscaling
            for (int i = 0; i < 6; i++) MemoryUtil.memPutInt(o + 196 + 4L * i, 0); // NVIDIA's model per mode
            check(dlssdSetOptions.callInt(viewport, o), "slDLSSDSetOptions");
        }
    }

    void evaluate(int feature, long frameToken, long viewport, long commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long inputs = MemoryUtil.memAddress(stack.pointers(viewport));
            check(evaluateFeature.callInt(feature, frameToken, inputs, 1, commandBuffer), "slEvaluateFeature");
        }
    }

    private Ffi.Fn featureFunction(MemoryStack stack, int feature, String name, Ffi.Type ret, Ffi.Type... args) {
        long out = MemoryUtil.memAddress(stack.callocPointer(1));
        check(getFeatureFunction.callInt(feature, MemoryUtil.memAddress(stack.ASCII(name)), out), "slGetFeatureFunction(" + name + ")");
        return Ffi.fn(name, MemoryUtil.memGetAddress(out), ret, args);
    }

    void setFeatureLoaded(int feature, boolean loaded) {
        check(setFeatureLoaded.callInt(feature, loaded ? 1 : 0), "slSetFeatureLoaded");
    }

    /** "SL a.b.c / NGX a.b.c" of the DLSS feature, or "" when unknown. */
    String dlssVersion() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long v = base(stack, 56, FEATURE_VERSION, 1);
            if (getFeatureVersion.callInt(FEATURE_DLSS, v) != 0) return "";
            return String.format("SL %d.%d.%d / DLSS %d.%d.%d", MemoryUtil.memGetInt(v + 32), MemoryUtil.memGetInt(v + 36),
                    MemoryUtil.memGetInt(v + 40), MemoryUtil.memGetInt(v + 44), MemoryUtil.memGetInt(v + 48), MemoryUtil.memGetInt(v + 52));
        }
    }

    void shutdown() {
        if (initialised) shutdown.callInt();
        initialised = false;
        for (ByteBuffer buffer : kept) MemoryUtil.memFree(buffer);
        kept.clear();
    }

    // ---- per frame --------------------------------------------------------------------------------------------------

    /** A new frame token for this frame index (owned by Streamline). */
    long frameToken(int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = MemoryUtil.memAddress(stack.callocPointer(1));
            long frameIndex = MemoryUtil.memAddress(stack.ints(index));
            check(getNewFrameToken.callInt(out, frameIndex), "slGetNewFrameToken");
            return MemoryUtil.memGetAddress(out);
        }
    }

    /** A viewport handle (kept for the bridge's lifetime). */
    long viewport(int id) {
        ByteBuffer buffer = keep(MemoryUtil.memCalloc(VIEWPORT_SIZE));
        long address = MemoryUtil.memAddress(buffer);
        writeBase(address, VIEWPORT, 1);
        MemoryUtil.memPutInt(address + 32, id);
        return address;
    }

    /**
     * DLSS options for a viewport.
     *
     * @param mode   DLSSMode: 1 max performance, 2 balanced, 3 max quality, 4 ultra performance, 6 DLAA
     * @param preset DLSSPreset for every mode: 0 default, 10..13 = J, K, L, M
     */
    void setOptions(long viewport, int mode, int preset, int outputWidth, int outputHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(dlssSetOptions.callInt(viewport, options(stack, mode, preset, outputWidth, outputHeight)), "slDLSSSetOptions");
        }
    }

    /** The render size DLSS wants for a mode and output size: {width, height}. */
    int[] optimalSize(int mode, int outputWidth, int outputHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long settings = base(stack, 64, DLSS_OPTIMAL, 1);
            check(dlssGetOptimalSettings.callInt(options(stack, mode, 0, outputWidth, outputHeight), settings), "slDLSSGetOptimalSettings");
            return new int[]{MemoryUtil.memGetInt(settings + 32), MemoryUtil.memGetInt(settings + 36)};
        }
    }

    private static long options(MemoryStack stack, int mode, int preset, int outputWidth, int outputHeight) {
        long o = base(stack, DLSS_OPTIONS_SIZE, DLSS_OPTIONS, 3);
        MemoryUtil.memPutInt(o + 32, mode);
        MemoryUtil.memPutInt(o + 36, outputWidth);
        MemoryUtil.memPutInt(o + 40, outputHeight);
        MemoryUtil.memPutFloat(o + 44, 0f);   // sharpness (deprecated)
        MemoryUtil.memPutFloat(o + 48, 1f);   // preExposure
        MemoryUtil.memPutFloat(o + 52, 1f);   // exposureScale
        MemoryUtil.memPutByte(o + 56, TRUE);  // colour buffers are HDR (linear, before tone mapping)
        MemoryUtil.memPutByte(o + 57, FALSE);
        MemoryUtil.memPutByte(o + 58, FALSE);
        // presets 60..80 (DLAA, quality, balanced, performance, ultra performance, ultra quality): 0 = NVIDIA's model for the
        // mode (and the driver's override, if any), else one forced model for all of them
        for (int i = 0; i < 6; i++) MemoryUtil.memPutInt(o + 60 + 4L * i, preset);
        MemoryUtil.memPutByte(o + 84, TRUE);  // auto exposure: the pipeline applies its own exposure after DLSS
        MemoryUtil.memPutByte(o + 85, FALSE); // alpha upscaling
        return o;
    }

    void setConstants(long constants, long frameToken, long viewport) {
        check(setConstants.callInt(constants, frameToken, viewport), "slSetConstants");
    }

    void setTags(long frameToken, long viewport, long tags, int count, long commandBuffer) {
        check(setTagForFrame.callInt(frameToken, viewport, tags, count, commandBuffer), "slSetTagForFrame");
    }


    void freeResources(long viewport) {
        freeResources(FEATURE_DLSS, viewport);
    }

    void freeResources(int feature, long viewport) {
        if (initialised) freeResources.callInt(feature, viewport);
    }

    // ---- frame generation, Reflex, PCL ------------------------------------------------------------------------------

    /**
     * DLSS Frame Generation's options for a viewport (sl::DLSSGOptions, version 5).
     *
     * @param mode   DLSSGMode: 0 off, 1 on (a fixed multiplier)
     * @param frames generated frames per rendered one (1 = 2x, 3 = 4x)
     */
    void frameGenerationOptions(long viewport, int mode, int frames, int flags, int backBuffers, int renderWidth, int renderHeight,
                                int outputWidth, int outputHeight, int colorFormat) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long o = base(stack, 120, DLSSG_OPTIONS, 5);
            MemoryUtil.memPutInt(o + 32, mode);
            MemoryUtil.memPutInt(o + 36, Math.max(1, frames));
            MemoryUtil.memPutInt(o + 40, flags);
            MemoryUtil.memPutInt(o + 52, backBuffers);
            MemoryUtil.memPutInt(o + 56, renderWidth);   // depth and motion vectors
            MemoryUtil.memPutInt(o + 60, renderHeight);
            MemoryUtil.memPutInt(o + 64, outputWidth);   // back buffers, HUD-less colour, UI
            MemoryUtil.memPutInt(o + 68, outputHeight);
            MemoryUtil.memPutInt(o + 72, colorFormat);
            MemoryUtil.memPutInt(o + 76, Vk.FORMAT_R16G16_SFLOAT);
            MemoryUtil.memPutInt(o + 80, Vk.FORMAT_R32_SFLOAT);
            MemoryUtil.memPutInt(o + 84, Vk.FORMAT_R8G8B8A8_UNORM);
            MemoryUtil.memPutInt(o + 88, Vk.FORMAT_R8G8B8A8_UNORM);
            MemoryUtil.memPutByte(o + 104, (byte) 2);    // bReserved15: eInvalid
            MemoryUtil.memPutInt(o + 108, 0);            // queue parallelism: block the presenting queue (the default)
            MemoryUtil.memPutByte(o + 112, FALSE);       // no separate UI interpolation
            MemoryUtil.memPutFloat(o + 116, 0f);
            check(dlssgSetOptions.callInt(viewport, o), "slDLSSGSetOptions");
        }
    }

    /**
     * Frame Generation's state: {status bits, minimum width or height, frames presented since the last call, most generated
     * frames per rendered one}.
     */
    int[] frameGenerationState(long viewport) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long state = base(stack, 88, DLSSG_STATE, 4);
            check(dlssgGetState.callInt(viewport, state, 0), "slDLSSGGetState");
            return new int[]{MemoryUtil.memGetInt(state + 40), MemoryUtil.memGetInt(state + 44), MemoryUtil.memGetInt(state + 48),
                    MemoryUtil.memGetInt(state + 52)};
        }
    }

    /** Reflex's mode (sl::ReflexOptions): 1 low latency, 2 low latency with boost; no frame limit. */
    void reflexOptions(int mode) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long o = base(stack, 48, REFLEX_OPTIONS, 1);
            MemoryUtil.memPutInt(o + 32, mode);
            check(reflexSetOptions.callInt(o), "slReflexSetOptions");
        }
    }

    /** Reflex's sleep before the frame's simulation starts (it times the frame's start for the lowest latency). */
    void reflexSleep(long frameToken) {
        check(reflexSleep.callInt(frameToken), "slReflexSleep");
    }

    void marker(int marker, long frameToken) {
        check(pclSetMarker.callInt(marker, frameToken), "slPCLSetMarker");
    }

    /**
     * Streamline does its per-frame bookkeeping (garbage collection of deferred resources, frame counting) in the common
     * plugin's present hook. A frame presented by OpenGL calls the hook directly, with no swapchain (a frame presented
     * through the Vulkan swapchain gets it from Streamline's own present hook).
     */
    void present() {
        if (presentHook == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long info = Vk.struct(stack, 64, Vk.ST_PRESENT_INFO_KHR, 0);
            long skip = MemoryUtil.memAddress(stack.calloc(1));
            presentHook.call(0, info, skip);
            if (afterPresentHook != null) afterPresentHook.call();
        }
    }

    // ---- structures -------------------------------------------------------------------------------------------------

    /** A Vulkan texture as Streamline's sl::Resource (layout GENERAL). */
    static void resource(long at, long image, long memory, long view, int width, int height, int format, int usage) {
        MemoryUtil.memSet(at, 0, RESOURCE_SIZE);
        writeBase(at, RESOURCE, 1);
        MemoryUtil.memPutByte(at + 32, (byte) RESOURCE_TEX2D);
        MemoryUtil.memPutAddress(at + 40, image);
        MemoryUtil.memPutAddress(at + 48, memory);
        MemoryUtil.memPutAddress(at + 56, view);
        MemoryUtil.memPutInt(at + 64, Vk.LAYOUT_GENERAL);
        MemoryUtil.memPutInt(at + 68, width);
        MemoryUtil.memPutInt(at + 72, height);
        MemoryUtil.memPutInt(at + 76, format);
        MemoryUtil.memPutInt(at + 80, 1);    // mip levels
        MemoryUtil.memPutInt(at + 84, 1);    // array layers
        MemoryUtil.memPutLong(at + 88, 0);   // gpu virtual address
        MemoryUtil.memPutInt(at + 96, 0);    // VkImageCreateFlags
        MemoryUtil.memPutInt(at + 100, usage);
    }

    /** One sl::ResourceTag (whole resource, valid until the evaluate call returns). */
    static void tag(long at, long resource, int bufferType) {
        tag(at, resource, bufferType, VALID_UNTIL_EVALUATE);
    }

    /** One sl::ResourceTag over the whole resource with this lifecycle (ONLY_VALID_NOW: Streamline copies it right away). */
    static void tag(long at, long resource, int bufferType, int lifecycle) {
        MemoryUtil.memSet(at, 0, TAG_SIZE);
        writeBase(at, RESOURCE_TAG, 1);
        MemoryUtil.memPutAddress(at + 32, resource);
        MemoryUtil.memPutInt(at + 40, bufferType);
        MemoryUtil.memPutInt(at + 44, lifecycle);
    }

    /** The sl::Constants of a frame (see {@link DlssBridge.Frame} for the meaning of each value). */
    static long constants(MemoryStack stack, DlssBridge.Frame f) {
        long c = base(stack, CONSTANTS_SIZE, CONSTANTS, 2);
        matrix(c + 32, f.viewToClip());
        matrix(c + 96, f.clipToView());
        matrix(c + 160, new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}); // clipToLensClip
        matrix(c + 224, f.clipToPrevClip());
        matrix(c + 288, f.prevClipToClip());
        MemoryUtil.memPutFloat(c + 352, f.jitterX());
        MemoryUtil.memPutFloat(c + 356, f.jitterY());
        MemoryUtil.memPutFloat(c + 360, f.motionScaleX());
        MemoryUtil.memPutFloat(c + 364, f.motionScaleY());
        MemoryUtil.memPutFloat(c + 368, 0);
        MemoryUtil.memPutFloat(c + 372, 0);
        vector(c + 376, f.cameraPos());
        vector(c + 388, f.cameraUp());
        vector(c + 400, f.cameraRight());
        vector(c + 412, f.cameraForward());
        MemoryUtil.memPutFloat(c + 424, f.near());
        MemoryUtil.memPutFloat(c + 428, f.far());
        MemoryUtil.memPutFloat(c + 432, f.fovRadians());
        MemoryUtil.memPutFloat(c + 436, f.aspect());
        MemoryUtil.memPutFloat(c + 440, INVALID_FLOAT);
        MemoryUtil.memPutByte(c + 444, FALSE);                    // depth not inverted
        MemoryUtil.memPutByte(c + 445, TRUE);                     // camera motion is in the motion vectors
        MemoryUtil.memPutByte(c + 446, FALSE);                    // 2D motion vectors
        MemoryUtil.memPutByte(c + 447, f.reset() ? TRUE : FALSE);
        MemoryUtil.memPutByte(c + 448, FALSE);                    // perspective
        MemoryUtil.memPutByte(c + 449, FALSE);                    // not dilated
        MemoryUtil.memPutByte(c + 450, FALSE);                    // not jittered
        MemoryUtil.memPutFloat(c + 452, 40f);
        return c;
    }

    private static void matrix(long at, float[] rowMajor) {
        for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(at + i * 4L, rowMajor[i]);
    }

    private static void vector(long at, float[] v) {
        for (int i = 0; i < 3; i++) MemoryUtil.memPutFloat(at + i * 4L, v[i]);
    }

    private static long base(MemoryStack stack, int size, long[] guid, int version) {
        long address = MemoryUtil.memAddress(stack.calloc(8, size));
        writeBase(address, guid, version);
        return address;
    }

    /** next = null, structType = the GUID, structVersion. */
    private static void writeBase(long at, long[] guid, int version) {
        MemoryUtil.memPutAddress(at, 0);
        MemoryUtil.memPutInt(at + 8, (int) guid[0]);
        MemoryUtil.memPutShort(at + 12, (short) guid[1]);
        MemoryUtil.memPutShort(at + 14, (short) guid[2]);
        for (int i = 0; i < 8; i++) MemoryUtil.memPutByte(at + 16 + i, (byte) guid[3 + i]);
        MemoryUtil.memPutLong(at + 24, version);
    }

    private static long[] guid(long data1, long data2, long data3, long... data4) {
        long[] g = new long[11];
        g[0] = data1;
        g[1] = data2;
        g[2] = data3;
        System.arraycopy(data4, 0, g, 3, 8);
        return g;
    }

    private ByteBuffer keep(ByteBuffer buffer) {
        kept.add(buffer);
        return buffer;
    }
}
