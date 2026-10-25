package com.blockland.client.perf;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a frame's time goes (rendering spec §20): any code on the render thread wraps a stretch of its work in a
 * {@link #span}, which measures it on the CPU ({@code System.nanoTime}) and on the GPU (a timestamp query on each side,
 * read back {@value #RING} frames later, so nothing ever waits for the GPU). Spans may nest; a key is a short path such as
 * {@code "bl/shadow/terrain"}. While nothing records (no benchmark, timings not shown), {@link #span} returns a shared
 * no-op and costs one field read.
 * <p>
 * GPU numbers are the time between the two timestamps on the GPU's timeline: the work of the span plus any work queued
 * before it that the GPU was still finishing. Spans that follow each other add up to the frame; a single span is a good
 * measure of its own work when the GPU is the bottleneck, as in a benchmark.
 */
public final class FrameTimings {
    /** A measured stretch of work: close it when done (try-with-resources). */
    public interface Span extends AutoCloseable {
        @Override
        void close();
    }

    private static final Span NONE = () -> {
    };
    /** Frames between issuing a query and reading it (results are ready long before; never blocks). */
    static final int RING = 4;
    private static final int MAX_SPANS = 1024;

    private static boolean recording;
    private static Thread renderThread;
    private static boolean gpuSupported;

    private static final Map<String, Integer> keyIds = new HashMap<>();
    private static final List<String> keyNames = new ArrayList<>();
    private static final Slot[] slots = new Slot[RING];
    private static long frameIndex;
    private static long lastFrameStart;
    private static Slot current;
    /** Spans open right now, innermost last (indices into the current slot). */
    private static final int[] stack = new int[64];
    private static int depth;

    private static final Window window = new Window();
    /** Smoothed per key (F3 lines): CPU and GPU milliseconds per frame. */
    private static float[] smoothCpu = new float[64], smoothGpu = new float[64];
    private static float smoothFrame, smoothGpuFrame;

    private FrameTimings() {
    }

    /** Measures from now until the returned span is closed (render thread; elsewhere it measures nothing). */
    public static Span span(String key) {
        if (!recording || current == null || Thread.currentThread() != renderThread) return NONE;
        return current.open(id(key));
    }

    /** Spans are being measured this frame (to skip work that only feeds them). */
    public static boolean recording() {
        return recording;
    }

    // ---- perf module --------------------------------------------------------------------------------------------------

    static void setRecording(boolean value) {
        if (value == recording) return;
        recording = value;
        // a new stretch of recording starts its totals afresh (a benchmark resets them again once it settled)
        if (value) window.reset();
        if (!value) {
            current = null;
            depth = 0;
            lastFrameStart = 0;
        }
    }

    /** Frame start (the client loop's iteration, {@link VanillaSections}): the {@value #RING}th last frame is collected. */
    static void beginFrame() {
        if (!recording) return;
        if (renderThread == null) {
            renderThread = Thread.currentThread();
            gpuSupported = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
        }
        long now = System.nanoTime();
        int index = (int) (frameIndex++ % RING);
        Slot slot = slots[index];
        if (slot == null) slot = slots[index] = new Slot();
        slot.collect();
        long interval = lastFrameStart == 0 ? 0 : now - lastFrameStart;
        lastFrameStart = now;
        depth = 0;
        current = slot;
        slot.begin(interval, now);
    }

    /** Frame end (after the swap). */
    static void endFrame() {
        if (current == null) return;
        while (depth > 0) current.close(stack[--depth]);
        current.end(System.nanoTime());
        // nothing between two frames is measured (its queries would come after the frame's last one)
        current = null;
    }

    static Window window() {
        return window;
    }

    static String name(int key) {
        return keyNames.get(key);
    }

    static int keyCount() {
        return keyNames.size();
    }

    static float smoothCpu(int key) {
        return key < smoothCpu.length ? smoothCpu[key] : 0;
    }

    static float smoothGpu(int key) {
        return key < smoothGpu.length ? smoothGpu[key] : 0;
    }

    static float smoothFrameMillis() {
        return smoothFrame;
    }

    static float smoothGpuFrameMillis() {
        return smoothGpuFrame;
    }

    static boolean gpuSupported() {
        return gpuSupported;
    }

    private static int id(String key) {
        Integer id = keyIds.get(key);
        if (id == null) {
            id = keyNames.size();
            keyIds.put(key, id);
            keyNames.add(key);
            if (id >= smoothCpu.length) {
                smoothCpu = java.util.Arrays.copyOf(smoothCpu, id * 2);
                smoothGpu = java.util.Arrays.copyOf(smoothGpu, id * 2);
            }
        }
        return id;
    }

    /** One frame's spans and their queries, reused every {@value #RING} frames. */
    private static final class Slot {
        private final int[] key = new int[MAX_SPANS];
        private final long[] cpuStart = new long[MAX_SPANS], cpuEnd = new long[MAX_SPANS];
        /** Two queries per span, then the frame's own two. */
        private final int[] queries = new int[MAX_SPANS * 2 + 2];
        private final Handle[] handles = new Handle[MAX_SPANS];
        private int count;
        private boolean pending, gpuIssued;
        private long interval, frameCpuStart, frameCpuEnd;
        /** Per key, summed over this frame (keys can repeat). */
        private final Map<Integer, long[]> scratch = new HashMap<>();

        void begin(long interval, long cpuNow) {
            this.interval = interval;
            frameCpuStart = cpuNow;
            frameCpuEnd = 0;
            count = 0;
            pending = true;
            gpuIssued = gpuSupported;
            if (gpuIssued) {
                if (queries[0] == 0) GL15.glGenQueries(queries);
                GL33.glQueryCounter(queries[MAX_SPANS * 2], GL33.GL_TIMESTAMP);
            }
        }

        Span open(int keyId) {
            if (count >= MAX_SPANS || depth >= stack.length) return NONE;
            int i = count++;
            key[i] = keyId;
            cpuEnd[i] = 0;
            if (gpuIssued) GL33.glQueryCounter(queries[2 * i], GL33.GL_TIMESTAMP);
            cpuStart[i] = System.nanoTime();
            stack[depth++] = i;
            Handle handle = handles[i];
            if (handle == null) handle = handles[i] = new Handle(i);
            handle.open = true;
            return handle;
        }

        void close(int i) {
            if (cpuEnd[i] != 0) return;
            cpuEnd[i] = System.nanoTime();
            if (gpuIssued) GL33.glQueryCounter(queries[2 * i + 1], GL33.GL_TIMESTAMP);
            handles[i].open = false;
        }

        void end(long cpuNow) {
            frameCpuEnd = cpuNow;
            if (gpuIssued) GL33.glQueryCounter(queries[MAX_SPANS * 2 + 1], GL33.GL_TIMESTAMP);
        }

        /** Reads this slot's last frame (if it ended) into the window and the smoothed values. */
        void collect() {
            if (!pending || frameCpuEnd == 0) {
                pending = false;
                return;
            }
            pending = false;
            boolean gpu = gpuIssued && GL15.glGetQueryObjecti(queries[MAX_SPANS * 2 + 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
            long gpuFrame = -1;
            if (gpu) gpuFrame = timestamp(MAX_SPANS * 2 + 1) - timestamp(MAX_SPANS * 2);
            scratch.clear();
            for (int i = 0; i < count; i++) {
                if (cpuEnd[i] == 0) continue;
                long[] sums = scratch.computeIfAbsent(key[i], k -> new long[2]);
                sums[0] += cpuEnd[i] - cpuStart[i];
                if (gpu) sums[1] += Math.max(0, timestamp(2 * i + 1) - timestamp(2 * i));
            }
            window.addFrame(interval, frameCpuEnd - frameCpuStart, gpuFrame, scratch);
            Hitches.frameSpans(frameCpuEnd - frameCpuStart, gpuFrame, scratch);
            float k = 0.05f;
            for (Map.Entry<Integer, long[]> e : scratch.entrySet()) {
                int id = e.getKey();
                smoothCpu[id] += (e.getValue()[0] / 1e6f - smoothCpu[id]) * k;
                if (gpu) smoothGpu[id] += (e.getValue()[1] / 1e6f - smoothGpu[id]) * k;
            }
            if (interval > 0) smoothFrame += (interval / 1e6f - smoothFrame) * k;
            if (gpu) smoothGpuFrame += (gpuFrame / 1e6f - smoothGpuFrame) * k;
        }

        private long timestamp(int query) {
            return GL33.glGetQueryObjecti64(queries[query], GL15.GL_QUERY_RESULT);
        }

        private final class Handle implements Span {
            private final int index;
            boolean open;

            Handle(int index) {
                this.index = index;
            }

            @Override
            public void close() {
                if (!open || current != Slot.this) return;
                // close everything opened inside it that was left open, then itself
                while (depth > 0) {
                    int top = stack[--depth];
                    Slot.this.close(top);
                    if (top == index) break;
                }
            }
        }
    }

    /**
     * Totals since the last {@link #reset}: frame intervals and per-key sums, for the benchmark's passes. A key's
     * average is per frame of the window (frames without that key count as zero).
     */
    static final class Window {
        private long frames, gpuFrames;
        private final List<Long> intervals = new ArrayList<>();
        private final List<Long> gpuFrameTimes = new ArrayList<>();
        private long cpuFrameSum;
        private final Map<Integer, long[]> keys = new HashMap<>();

        void addFrame(long interval, long cpuFrame, long gpuFrame, Map<Integer, long[]> perKey) {
            frames++;
            if (interval > 0) intervals.add(interval);
            cpuFrameSum += cpuFrame;
            if (gpuFrame >= 0) {
                gpuFrames++;
                gpuFrameTimes.add(gpuFrame);
            }
            for (Map.Entry<Integer, long[]> e : perKey.entrySet()) {
                long[] sums = keys.computeIfAbsent(e.getKey(), k -> new long[2]);
                sums[0] += e.getValue()[0];
                if (gpuFrame >= 0) sums[1] += e.getValue()[1];
            }
        }

        void reset() {
            frames = gpuFrames = 0;
            intervals.clear();
            gpuFrameTimes.clear();
            cpuFrameSum = 0;
            keys.clear();
        }

        long frames() {
            return frames;
        }

        long gpuFrames() {
            return gpuFrames;
        }

        List<Long> intervals() {
            return intervals;
        }

        List<Long> gpuFrameTimes() {
            return gpuFrameTimes;
        }

        long cpuFrameSum() {
            return cpuFrameSum;
        }

        /** Key id → {CPU nanoseconds, GPU nanoseconds} summed over the window. */
        Map<Integer, long[]> keys() {
            return keys;
        }
    }
}
