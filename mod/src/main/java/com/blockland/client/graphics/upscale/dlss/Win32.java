package com.blockland.client.graphics.upscale.dlss;

import org.lwjgl.system.windows.WinBase;

import java.nio.file.Path;

/**
 * The few Windows calls the bridge needs: loading DLLs by path, finding exports, closing shared handles, and the child window
 * frame generation presents into.
 */
final class Win32 {
    private static final int LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR = 0x100, LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x1000;
    private static final long WS_CHILD = 0x40000000L, WS_VISIBLE = 0x10000000L, WS_CLIPSIBLINGS = 0x04000000L;
    private static final long WS_EX_NOPARENTNOTIFY = 0x4L;
    private static final int SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10, SWP_NOCOPYBITS = 0x100;
    private static Ffi.Fn closeHandle, loadLibraryEx, createWindowEx, destroyWindow, setWindowPos, moduleHandle;

    private Win32() {
    }

    /**
     * Loads a DLL by absolute path; its own dependencies are searched next to it first (Streamline loads its plugins and
     * NVIDIA's DLSS runtime from its own folder).
     */
    static long load(Path dll) {
        long kernel32 = WinBase.GetModuleHandle("kernel32.dll");
        if (loadLibraryEx == null) {
            loadLibraryEx = Ffi.fn("LoadLibraryExW", WinBase.GetProcAddress(kernel32, "LoadLibraryExW"), Ffi.Type.PTR,
                    Ffi.Type.PTR, Ffi.Type.PTR, Ffi.Type.U32);
        }
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long path = org.lwjgl.system.MemoryUtil.memAddress(stack.UTF16(dll.toAbsolutePath().toString()));
            long module = loadLibraryEx.call(path, 0, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
            if (module == 0) {
                throw new DlssException("blockland.graphics.status.dlss.load_failed", dll.getFileName() + " (error " + WinBase.getLastError() + ")");
            }
            return module;
        }
    }

    /** An export of a loaded module, 0 when it has none. */
    static long symbol(long module, String name) {
        return WinBase.GetProcAddress(module, name);
    }

    /** A module already loaded into the process (by name), 0 when it is not. */
    static long loaded(String name) {
        return WinBase.GetModuleHandle(name);
    }

    /**
     * A child window covering {@code parent}'s client area for a Vulkan swapchain. It is a plain STATIC control: static
     * controls answer hit tests with HTTRANSPARENT and never take the focus, so the mouse, the keyboard and the cursor all
     * stay with the parent (GLFW's window) as if the child were not there. Created on the parent's thread.
     */
    static long createChild(long parent, int width, int height) {
        long user32 = WinBase.GetModuleHandle("user32.dll");
        if (createWindowEx == null) {
            createWindowEx = Ffi.fn("CreateWindowExW", WinBase.GetProcAddress(user32, "CreateWindowExW"), Ffi.Type.PTR,
                    Ffi.Type.U32, Ffi.Type.PTR, Ffi.Type.PTR, Ffi.Type.U32, Ffi.Type.I32, Ffi.Type.I32, Ffi.Type.I32, Ffi.Type.I32,
                    Ffi.Type.PTR, Ffi.Type.PTR, Ffi.Type.PTR, Ffi.Type.PTR);
            destroyWindow = Ffi.fn("DestroyWindow", WinBase.GetProcAddress(user32, "DestroyWindow"), Ffi.Type.I32, Ffi.Type.PTR);
            setWindowPos = Ffi.fn("SetWindowPos", WinBase.GetProcAddress(user32, "SetWindowPos"), Ffi.Type.I32,
                    Ffi.Type.PTR, Ffi.Type.PTR, Ffi.Type.I32, Ffi.Type.I32, Ffi.Type.I32, Ffi.Type.I32, Ffi.Type.U32);
            moduleHandle = Ffi.fn("GetModuleHandleW", WinBase.GetProcAddress(WinBase.GetModuleHandle("kernel32.dll"), "GetModuleHandleW"),
                    Ffi.Type.PTR, Ffi.Type.PTR);
        }
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long className = org.lwjgl.system.MemoryUtil.memAddress(stack.UTF16("STATIC"));
            long title = org.lwjgl.system.MemoryUtil.memAddress(stack.UTF16("BlockLand frame generation"));
            long window = createWindowEx.call(WS_EX_NOPARENTNOTIFY, className, title, WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS,
                    0, 0, Math.max(1, width), Math.max(1, height), parent, 0, moduleHandle.call(0), 0);
            if (window == 0) {
                throw new DlssException("blockland.graphics.status.dlss.vulkan", "CreateWindowExW (error " + WinBase.getLastError() + ")");
            }
            return window;
        }
    }

    /** Moves the child window back over the whole client area at this size. */
    static void resizeChild(long window, int width, int height) {
        setWindowPos.call(window, 0, 0, 0, Math.max(1, width), Math.max(1, height), SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOCOPYBITS);
    }

    static void destroyChild(long window) {
        if (window != 0 && destroyWindow != null) destroyWindow.call(window);
    }

    /**
     * A string value under HKEY_LOCAL_MACHINE (e.g. where the driver keeps NGX), or null when it is not there.
     */
    static String machineString(String subKey, String value) {
        long advapi = WinBase.GetModuleHandle("advapi32.dll");
        if (advapi == 0) advapi = load(java.nio.file.Path.of(System.getenv("SystemRoot"), "System32", "advapi32.dll"));
        Ffi.Fn regGetValue = Ffi.fn("RegGetValueW", WinBase.GetProcAddress(advapi, "RegGetValueW"), Ffi.Type.I32,
                Ffi.Type.PTR, Ffi.Type.PTR, Ffi.Type.PTR, Ffi.Type.U32, Ffi.Type.PTR, Ffi.Type.PTR, Ffi.Type.PTR);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long data = org.lwjgl.system.MemoryUtil.memAddress(stack.calloc(2, 2048));
            java.nio.IntBuffer size = stack.ints(4096);
            int result = regGetValue.callInt(0x80000002L, org.lwjgl.system.MemoryUtil.memAddress(stack.UTF16(subKey)),
                    org.lwjgl.system.MemoryUtil.memAddress(stack.UTF16(value)), 0x2 /* RRF_RT_REG_SZ */, 0, data,
                    org.lwjgl.system.MemoryUtil.memAddress(size));
            return result == 0 ? org.lwjgl.system.MemoryUtil.memUTF16(data) : null;
        }
    }

    /** The module handle of the process (for surfaces). */
    static long instance() {
        return WinBase.GetModuleHandle((CharSequence) null);
    }

    static void closeHandle(long handle) {
        if (handle == 0) return;
        if (closeHandle == null) {
            closeHandle = Ffi.fn("CloseHandle", WinBase.GetProcAddress(WinBase.GetModuleHandle("kernel32.dll"), "CloseHandle"),
                    Ffi.Type.I32, Ffi.Type.PTR);
        }
        closeHandle.call(handle);
    }
}
