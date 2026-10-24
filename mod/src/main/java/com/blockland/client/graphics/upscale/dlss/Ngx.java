package com.blockland.client.graphics.upscale.dlss;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.F32;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.F64;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.I32;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.I64;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.PTR;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.U32;
import static com.blockland.client.graphics.upscale.dlss.Ffi.Type.VOID;

/**
 * NVIDIA NGX called directly — for the features Streamline does not wrap (DLSS 5's 3D-guided neural rendering): the driver's
 * NGX core ({@code _nvngx.dll}, found through {@code HKLM\SOFTWARE\NVIDIA Corporation\Global\NGXCore}) exports the Vulkan
 * entry points as plain C functions (the NGX SDK's static library is only a loader in front of them). NGX's parameter
 * block is a C++ interface; its overloaded {@code Set} / {@code Get} methods are called through their vtable slots, whose
 * order is checked at start with typed round trips ({@link Params}) rather than assumed.
 */
final class Ngx {
    static final int VERSION_API = 0x15;
    static final int FEATURE_DLSSNR = 18;
    static final int SUCCESS = 1;
    private static final String KEY = "SOFTWARE\\NVIDIA Corporation\\Global\\NGXCore";

    private final Ffi.Fn initProjectId, getCapabilityParameters, allocateParameters, destroyParameters, createFeature1,
            evaluateFeature, releaseFeature, deviceExtensionRequirements;
    /** The parameter block's vtable layout, found by {@link Params#check}. */
    private int[] slots;
    /** Prepared vtable calls (preparing one allocates native memory: never per call). */
    private final java.util.Map<String, Ffi.Fn> methods = new java.util.HashMap<>();
    private final List<ByteBuffer> kept = new ArrayList<>();

    private Ngx(long core) {
        initProjectId = export(core, "NVSDK_NGX_VULKAN_Init_ProjectID", I32, PTR, U32, PTR, PTR, PTR, PTR, PTR, PTR, PTR, U32, PTR);
        getCapabilityParameters = export(core, "NVSDK_NGX_VULKAN_GetCapabilityParameters", I32, PTR);
        allocateParameters = export(core, "NVSDK_NGX_VULKAN_AllocateParameters", I32, PTR);
        destroyParameters = export(core, "NVSDK_NGX_VULKAN_DestroyParameters", I32, PTR);
        createFeature1 = export(core, "NVSDK_NGX_VULKAN_CreateFeature1", I32, PTR, PTR, U32, PTR, PTR);
        evaluateFeature = export(core, "NVSDK_NGX_VULKAN_EvaluateFeature", I32, PTR, PTR, PTR, PTR);
        releaseFeature = export(core, "NVSDK_NGX_VULKAN_ReleaseFeature", I32, PTR);
        deviceExtensionRequirements = export(core, "NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements", I32, PTR, PTR, PTR, PTR, PTR);
    }

    private static Ffi.Fn export(long module, String name, Ffi.Type ret, Ffi.Type... args) {
        return Ffi.fn(name, Win32.symbol(module, name), ret, args);
    }

    /** The driver's NGX core (already loaded by Streamline in a running game). */
    static Ngx load() {
        long core = Win32.loaded("_nvngx.dll");
        if (core == 0) {
            String folder = Win32.machineString(KEY, "FullPath");
            Path dll = folder == null ? null : Path.of(folder, "_nvngx.dll");
            if (dll == null || !Files.isRegularFile(dll)) throw new DlssException("blockland.graphics.status.nr.no_ngx", String.valueOf(folder));
            core = Win32.load(dll);
        }
        return new Ngx(core);
    }

    static String result(int code) {
        return code == SUCCESS ? "success" : String.format("0x%08X", code);
    }

    static void check(int code, String call) {
        if (code != SUCCESS) throw new DlssException("blockland.graphics.status.nr.ngx", call + " = " + result(code));
    }

    /** One wide-string list for NGX's feature search path, kept for the process (NGX may hold on to it). */
    private long featureInfo(Path featureFolder) {
        ByteBuffer path = keep(MemoryUtil.memUTF16(featureFolder.toAbsolutePath().toString()));
        ByteBuffer list = keep(MemoryUtil.memAlloc(8));
        MemoryUtil.memPutAddress(MemoryUtil.memAddress(list), MemoryUtil.memAddress(path));
        ByteBuffer info = keep(MemoryUtil.memCalloc(40));           // NVSDK_NGX_FeatureCommonInfo
        long at = MemoryUtil.memAddress(info);
        MemoryUtil.memPutAddress(at, MemoryUtil.memAddress(list));  // PathListInfo.Path
        MemoryUtil.memPutInt(at + 8, 1);                            // PathListInfo.Length
        return at;
    }

    /**
     * Device extensions a feature needs ({@code VkExtensionProperties} names), asked before the device exists; empty when
     * NGX cannot say (an older driver).
     */
    List<String> deviceExtensions(long instance, long physicalDevice, int feature, String projectId, String engineVersion, Path featureFolder) {
        List<String> names = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long info = MemoryUtil.memAddress(stack.calloc(8, 56));     // NVSDK_NGX_FeatureDiscoveryInfo
            MemoryUtil.memPutInt(info, VERSION_API);
            MemoryUtil.memPutInt(info + 4, feature);
            MemoryUtil.memPutInt(info + 8, 1);                          // identified by project id
            MemoryUtil.memPutAddress(info + 16, MemoryUtil.memAddress(stack.ASCII(projectId)));
            MemoryUtil.memPutInt(info + 24, 0);                         // custom engine
            MemoryUtil.memPutAddress(info + 32, MemoryUtil.memAddress(stack.ASCII(engineVersion)));
            MemoryUtil.memPutAddress(info + 40, MemoryUtil.memAddress(stack.UTF16(featureFolder.toAbsolutePath().toString())));
            MemoryUtil.memPutAddress(info + 48, featureInfo(featureFolder));
            long count = MemoryUtil.memAddress(stack.ints(0));
            long props = MemoryUtil.memAddress(stack.callocPointer(1));
            if (deviceExtensionRequirements.callInt(instance, physicalDevice, info, count, props) != SUCCESS) return names;
            long list = MemoryUtil.memGetAddress(props);
            for (int i = 0; i < MemoryUtil.memGetInt(count) && list != 0; i++) names.add(MemoryUtil.memUTF8(list + 260L * i));
        }
        return names;
    }

    /** NGX on a Vulkan device (Streamline's: NGX keeps one context per device), features searched in {@code featureFolder}. */
    void init(String projectId, String engineVersion, Path dataFolder, Path featureFolder, long instance, long physicalDevice, long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = keep(MemoryUtil.memUTF16(dataFolder.toAbsolutePath().toString()));
            check(initProjectId.callInt(MemoryUtil.memAddress(stack.ASCII(projectId)), 0, MemoryUtil.memAddress(stack.ASCII(engineVersion)),
                    MemoryUtil.memAddress(data), instance, physicalDevice, device, 0, 0, VERSION_API, featureInfo(featureFolder)),
                    "NVSDK_NGX_VULKAN_Init_ProjectID");
        }
    }

    /** A capability block (destroy with {@link #destroy}). */
    Params capabilities() {
        return params(getCapabilityParameters, "NVSDK_NGX_VULKAN_GetCapabilityParameters");
    }

    /** Which vtable layout the parameter block has ("MSVC" or "declared"), once a block was made. */
    String layout() {
        return slots == Params.MSVC ? "MSVC" : slots == Params.DECLARED ? "declared" : "unknown";
    }

    /** A fresh parameter block (destroy with {@link #destroy}). */
    Params allocate() {
        return params(allocateParameters, "NVSDK_NGX_VULKAN_AllocateParameters");
    }

    private Params params(Ffi.Fn fn, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = MemoryUtil.memAddress(stack.callocPointer(1));
            check(fn.callInt(out), name);
            Params params = new Params(MemoryUtil.memGetAddress(out));
            if (slots == null) slots = params.check();
            return params;
        }
    }

    void destroy(Params params) {
        destroyParameters.callInt(params.address);
    }

    /** Creates a feature, recording its setup into {@code commandBuffer}; returns the handle. */
    long create(long device, long commandBuffer, int feature, Params params) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = MemoryUtil.memAddress(stack.callocPointer(1));
            check(createFeature1.callInt(device, commandBuffer, feature, params.address, out), "NVSDK_NGX_VULKAN_CreateFeature1");
            return MemoryUtil.memGetAddress(out);
        }
    }

    void evaluate(long commandBuffer, long handle, Params params) {
        check(evaluateFeature.callInt(commandBuffer, handle, params.address, 0), "NVSDK_NGX_VULKAN_EvaluateFeature");
    }

    void release(long handle) {
        if (handle != 0) releaseFeature.callInt(handle);
    }

    private ByteBuffer keep(ByteBuffer buffer) {
        kept.add(buffer);
        return buffer;
    }

    /**
     * An NGX parameter block (C++ {@code NVSDK_NGX_Parameter}): eight {@code Set} overloads, eight {@code Get} overloads,
     * {@code Reset}. MSVC groups overloads and lays each group out in reverse declaration order; the plain declaration order
     * is the other candidate. {@link #check} tells them apart with typed round trips on scratch keys.
     */
    final class Params {
        // indices into a layout: set ull, f, d, ui, i, ptr; get ull, f, d, ui, i, ptr
        private static final int SET_ULL = 0, SET_F = 1, SET_D = 2, SET_UI = 3, SET_I = 4, SET_PTR = 5, GET_ULL = 6, GET_F = 7,
                GET_D = 8, GET_UI = 9, GET_I = 10, GET_PTR = 11;
        /** MSVC: Set(void*)=0, Set(D3D12)=1, Set(D3D11)=2, Set(int)=3, Set(uint)=4, Set(double)=5, Set(float)=6, Set(ull)=7, Get likewise from 8. */
        private static final int[] MSVC = {7, 6, 5, 4, 3, 0, 15, 14, 13, 12, 11, 8};
        /** Declaration order: Set(ull)=0, float, double, uint, int, D3D11, D3D12, void*=7, Get likewise from 8. */
        private static final int[] DECLARED = {0, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 15};

        final long address;

        private Params(long address) {
            this.address = address;
        }

        /** The layout under which values come back exactly as set (and as the type they were set with). */
        private int[] check() {
            for (int[] layout : new int[][]{MSVC, DECLARED}) {
                slots = layout;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    long name = MemoryUtil.memAddress(stack.ASCII("BlockLand.Probe"));
                    call(SET_ULL, I64).call(address, name, 0x1234_5678_9ABCL);
                    long ull = MemoryUtil.memAddress(stack.callocLong(1));
                    if (call(GET_ULL).callInt(address, name, ull) != SUCCESS || MemoryUtil.memGetLong(ull) != 0x1234_5678_9ABCL) continue;
                    call(SET_F, F32).call(address, name, Ffi.f32(0.375f));
                    long f = MemoryUtil.memAddress(stack.callocInt(1));
                    if (call(GET_F).callInt(address, name, f) != SUCCESS || MemoryUtil.memGetFloat(f) != 0.375f) continue;
                    call(SET_I, I32).call(address, name, -7);
                    long i = MemoryUtil.memAddress(stack.callocInt(1));
                    if (call(GET_I).callInt(address, name, i) != SUCCESS || MemoryUtil.memGetInt(i) != -7) continue;
                    call(SET_PTR, PTR).call(address, name, 0x7FF0_1234_5000L);
                    long p = MemoryUtil.memAddress(stack.callocPointer(1));
                    if (call(GET_PTR).callInt(address, name, p) != SUCCESS || MemoryUtil.memGetAddress(p) != 0x7FF0_1234_5000L) continue;
                    return layout;
                }
            }
            slots = null;
            throw new DlssException("blockland.graphics.status.nr.ngx", "parameter block layout not recognised");
        }

        /** Set(this, name, value) or Get(this, name, out); prepared once per slot and signature (every block shares the vtable). */
        private Ffi.Fn call(int which, Ffi.Type... value) {
            boolean get = which >= GET_ULL;
            Ffi.Type[] args = get ? new Ffi.Type[]{PTR, PTR, PTR} : new Ffi.Type[]{PTR, PTR, value[0]};
            String key = slots[which] + ":" + java.util.Arrays.toString(args);
            return methods.computeIfAbsent(key, k -> Ffi.virt("NVSDK_NGX_Parameter", address, slots[which], get ? I32 : VOID, args));
        }

        void setF(String key, float value) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                call(SET_F, F32).call(address, MemoryUtil.memAddress(stack.ASCII(key)), Ffi.f32(value));
            }
        }

        void setD(String key, double value) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                call(SET_D, F64).call(address, MemoryUtil.memAddress(stack.ASCII(key)), Ffi.f64(value));
            }
        }

        void setI(String key, int value) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                call(SET_I, I32).call(address, MemoryUtil.memAddress(stack.ASCII(key)), value);
            }
        }

        void setUI(String key, int value) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                call(SET_UI, U32).call(address, MemoryUtil.memAddress(stack.ASCII(key)), value & 0xFFFF_FFFFL);
            }
        }

        void setPointer(String key, long value) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                call(SET_PTR, PTR).call(address, MemoryUtil.memAddress(stack.ASCII(key)), value);
            }
        }

        /** An int value, or {@code fallback} when the key is not set. */
        int getI(String key, int fallback) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long out = MemoryUtil.memAddress(stack.callocInt(1));
                return call(GET_I).callInt(address, MemoryUtil.memAddress(stack.ASCII(key)), out) == SUCCESS
                        ? MemoryUtil.memGetInt(out) : fallback;
            }
        }

        int getUI(String key, int fallback) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long out = MemoryUtil.memAddress(stack.callocInt(1));
                return call(GET_UI).callInt(address, MemoryUtil.memAddress(stack.ASCII(key)), out) == SUCCESS
                        ? MemoryUtil.memGetInt(out) : fallback;
            }
        }
    }
}
