package com.blockland.client.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A quick measurement for development (rendering spec §20; {@code DevAutomation}'s {@code timings}): every
 * {@link FrameTimings} span recorded from {@link #start} until {@link #report}, averaged per frame, with the frame's own
 * CPU and GPU time. Unlike the benchmark it changes nothing — no camera path, no optimization switches — so a scene can
 * be measured as it is (say with and without some objects in view).
 */
public final class TimingProbe {
    private static boolean active;

    private TimingProbe() {
    }

    /** Starts recording afresh. */
    public static void start() {
        active = true;
        FrameTimings.setRecording(true);
        FrameTimings.window().reset();
    }

    static boolean active() {
        return active;
    }

    /** Stops and returns the averages: one line for the frame, one per span (sorted by key). */
    public static List<String> report() {
        active = false;
        FrameTimings.Window w = FrameTimings.window();
        List<String> out = new ArrayList<>();
        long frames = Math.max(1, w.frames()), gpuFrames = Math.max(1, w.gpuFrames());
        double interval = w.intervals().stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double gpu = w.gpuFrameTimes().stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        out.add(String.format(Locale.ROOT, "frames=%d interval=%.3fms (%.1f fps) cpu=%.3fms gpu=%.3fms", w.frames(), interval,
                interval > 0 ? 1000 / interval : 0, w.cpuFrameSum() / 1e6 / frames, gpu));
        Map<String, long[]> sorted = new TreeMap<>();
        for (Map.Entry<Integer, long[]> e : w.keys().entrySet()) sorted.put(FrameTimings.name(e.getKey()), e.getValue());
        for (Map.Entry<String, long[]> e : sorted.entrySet()) {
            out.add(String.format(Locale.ROOT, "  %-40s cpu=%.4fms gpu=%.4fms", e.getKey(), e.getValue()[0] / 1e6 / frames,
                    e.getValue()[1] / 1e6 / gpuFrames));
        }
        return out;
    }
}
