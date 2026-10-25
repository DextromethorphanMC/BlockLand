package com.blockland.client.perf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The 性能 (performance) settings, saved with the other client preferences ({@code ClientSettings.Data.performance}):
 * which {@link Optimization}s run, whether the frame's timings are measured and shown on the F3 screen, and the frame
 * rate while the game window is in the background ({@link BackgroundLimit}).
 */
public final class PerfSettings {
    /** Optimization id → on; entries missing from the file are on. */
    public Map<String, Boolean> optimizations = new LinkedHashMap<>();
    /** Measure every frame (CPU and GPU time per stage) and list the most expensive stages on the F3 screen. */
    public boolean showTimings = false;
    /** Frames per second at most while another window is in front ({@link BackgroundLimit#UNLIMITED} = no cap). */
    public int backgroundFrameLimit = 60;

    public boolean enabled(Optimization optimization) {
        Boolean value = optimizations.get(optimization.id);
        return value == null || value;
    }

    public void set(Optimization optimization, boolean on) {
        optimizations.put(optimization.id, on);
    }

    public void sanitize() {
        if (optimizations == null) optimizations = new LinkedHashMap<>();
        optimizations.values().removeIf(v -> v == null);
        optimizations.keySet().removeIf(id -> Optimization.byId(id) == null);
        for (Optimization o : Optimization.values()) optimizations.putIfAbsent(o.id, true);
        backgroundFrameLimit = Math.max(BackgroundLimit.MIN, Math.min(BackgroundLimit.UNLIMITED, backgroundFrameLimit));
    }
}
