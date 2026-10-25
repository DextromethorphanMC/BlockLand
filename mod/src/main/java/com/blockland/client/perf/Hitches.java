package com.blockland.client.perf;

import com.blockland.BlockLand;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds out why the picture freezes now and then: every frame's interval is measured (one clock read); a frame longer
 * than {@link #THRESHOLD_NANOS} in a running world is logged with the garbage collections that ran in it and the heap,
 * and the frame timings record for a minute afterwards, so the next freeze is also logged with where its CPU time went
 * ({@link FrameTimings} spans, slowest first). Vanilla's frame flip is split into its buffer swap and its window event
 * polls ({@link PerfHooks.DisplayStep}), logged with the window's state, since a freeze there is not the mod's CPU work.
 * Changes nothing but the log.
 */
final class Hitches {
    /** A frame this long is a visible freeze (several times a 60 fps frame). */
    static final long THRESHOLD_NANOS = 100_000_000L;
    /** How long the timings keep recording after a freeze. */
    private static final long ARMED_NANOS = 60_000_000_000L;
    private static final int SPANS_LOGGED = 8;

    private static final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private static long lastFrame;
    private static long lastGcCount, lastGcMillis;
    private static long armedUntil;
    /** The last frame's flip: time in the swap and in the event polls, and when the open step started. */
    private static long swapNanos, eventNanos, stepStart;

    private Hitches() {
    }

    /** Start or end of a step of vanilla's frame flip (render thread). */
    static void displayStep(PerfHooks.DisplayStep step, boolean start) {
        long now = System.nanoTime();
        if (start) {
            stepStart = now;
            return;
        }
        if (stepStart == 0) return;
        if (step == PerfHooks.DisplayStep.SWAP) swapNanos += now - stepStart;
        else eventNanos += now - stepStart;
        stepStart = 0;
    }

    /** Start of a frame (render thread). */
    static void frame() {
        long now = System.nanoTime();
        long count = 0, millis = 0;
        for (GarbageCollectorMXBean gc : collectors) {
            count += Math.max(0, gc.getCollectionCount());
            millis += Math.max(0, gc.getCollectionTime());
        }
        long gcCount = count - lastGcCount, gcMillis = millis - lastGcMillis;
        lastGcCount = count;
        lastGcMillis = millis;
        long interval = lastFrame == 0 ? 0 : now - lastFrame;
        lastFrame = now;
        long swap = swapNanos, events = eventNanos;
        swapNanos = eventNanos = 0;
        Minecraft mc = Minecraft.getInstance();
        // loading screens and world changes stall on purpose
        if (interval < THRESHOLD_NANOS || mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        Runtime runtime = Runtime.getRuntime();
        Window window = mc.getWindow();
        long handle = window.getWindow();
        BlockLand.LOGGER.warn("[BlockLand] Frame hitch: {} ms (swap {} ms, window events {} ms; GC: {} collections, {} ms; heap {}/{} MB; fps {}; "
                        + "window {}x{}{}{}{}, limit {}){}",
                interval / 1_000_000, swap / 1_000_000, events / 1_000_000, gcCount, gcMillis,
                (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20, mc.getFps(),
                window.getWidth(), window.getHeight(),
                GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) != 0 ? " maximized" : "",
                GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) != 0 ? " focused" : " unfocused",
                window.isFullscreen() ? " fullscreen" : "",
                mc.getFramerateLimitTracker().getThrottleReason(),
                FrameTimings.recording() ? "" : " — recording frame timings for a minute");
        armedUntil = now + ARMED_NANOS;
    }

    /** Whether the frame timings should record (a freeze happened within the last minute). */
    static boolean armed() {
        return armedUntil != 0 && System.nanoTime() < armedUntil;
    }

    /** A recorded frame's CPU time per span key (called when its timings are read back). */
    static void frameSpans(long cpuFrameNanos, long gpuFrameNanos, Map<Integer, long[]> perKey) {
        if (cpuFrameNanos < THRESHOLD_NANOS) return;
        List<Map.Entry<Integer, long[]>> spans = new ArrayList<>(perKey.entrySet());
        spans.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < spans.size() && i < SPANS_LOGGED; i++) {
            if (!text.isEmpty()) text.append(", ");
            text.append(FrameTimings.name(spans.get(i).getKey())).append(' ').append(spans.get(i).getValue()[0] / 1_000_000).append(" ms");
        }
        BlockLand.LOGGER.warn("[BlockLand] Frame hitch breakdown ({} ms of CPU, GPU {}): {}", cpuFrameNanos / 1_000_000,
                gpuFrameNanos < 0 ? "not measured" : gpuFrameNanos / 1_000_000 + " ms", text.isEmpty() ? "no span covered it" : text);
    }
}
