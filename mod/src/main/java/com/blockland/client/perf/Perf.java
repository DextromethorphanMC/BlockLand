package com.blockland.client.perf;

import com.blockland.client.settings.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.Priority;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The performance module (rendering spec §20): the switchable {@link Optimization}s, the frame's {@link FrameTimings}
 * and the {@link Benchmark} that proves both what they gain and that they change nothing on screen. Other modules only
 * ask {@link Optimization#on()} and open {@link FrameTimings#span}s; the 性能 tab reads and writes {@link PerfSettings}
 * and starts the benchmark through this class.
 */
public final class Perf {
    private static boolean applied;

    private Perf() {
    }

    public static void init(BusGroup modBus) {
        // first of the frame: the benchmark places the camera and steps the render clock before anything reads them
        TickEvent.RenderTickEvent.Pre.BUS.addListener(Priority.HIGHEST, event -> {
            if (!applied) applySettings();
            FrameTimings.setRecording(settings().showTimings || Benchmark.running());
            Benchmark.beforeFrame();
        });
        Benchmark.init(modBus);
        // a camera without a finite position, angle or field of view hangs vanilla's culling (Frustum
        // .offsetToFullyIncludeCameraCube never finds the camera inside): say what it was before that happens
        net.minecraftforge.client.event.ViewportEvent.ComputeFov.BUS.addListener(Priority.MONITOR, event -> {
            double fov = event.getFOV();
            if (!(fov > 0 && fov < 180)) badCamera("fov " + fov, event.getCamera());
        });
        net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles.BUS.addListener(Priority.MONITOR, event -> {
            if (!Float.isFinite(event.getYaw()) || !Float.isFinite(event.getPitch()) || !Float.isFinite(event.getRoll())) {
                badCamera("angles " + event.getYaw() + " / " + event.getPitch() + " / " + event.getRoll(), event.getCamera());
            }
        });
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Right && settings().showTimings && FrameTimings.recording()) {
                event.getText().addAll(debugLines());
            }
            return false;
        });
    }

    private static long lastBadCamera;

    private static void badCamera(String what, net.minecraft.client.Camera camera) {
        long now = System.nanoTime();
        if (now - lastBadCamera < 1_000_000_000L) return;
        lastBadCamera = now;
        com.blockland.BlockLand.LOGGER.error("[BlockLand] Camera not finite ({}), position {}, benchmark {}", what, camera.getPosition(),
                Benchmark.running() ? "running" : "off", new Throwable("where"));
    }

    public static PerfSettings settings() {
        return ClientSettings.get().performance;
    }

    /**
     * Applies the saved choices (after loading, after a change on the 性能 tab). Sections are rebuilt when an entry
     * whose work lives in the section meshes changed, so every section uses the same path.
     */
    public static void applySettings() {
        applied = true;
        if (Benchmark.running()) return;
        PerfSettings settings = settings();
        boolean rebuild = false;
        for (Optimization o : Optimization.values()) {
            boolean on = settings.enabled(o);
            if (o.on() != on) {
                o.set(on);
                rebuild |= o.rebuildsSections;
            }
        }
        if (rebuild) rebuildSections();
    }

    /** Sets every entry at once (benchmark passes); true when section meshes have to be rebuilt for it. */
    /** Benchmark modes: every entry off or on, or with {@code only} just that one (all others on). */
    static boolean setMode(boolean on, @javax.annotation.Nullable Optimization only) {
        if (only == null) return setAll(on);
        boolean rebuild = setAll(true);
        if (only.on() != on) {
            only.set(on);
            rebuild |= only.rebuildsSections;
        }
        return rebuild;
    }

    static boolean setAll(boolean on) {
        boolean rebuild = false;
        for (Optimization o : Optimization.values()) {
            if (o.on() != on) {
                o.set(on);
                rebuild |= o.rebuildsSections;
            }
        }
        return rebuild;
    }

    static void rebuildSections() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.levelRenderer.allChanged();
    }

    public static boolean benchmarkRunning() {
        return Benchmark.running();
    }

    /** Starts the benchmark in the current world (closes the open screen); false when it cannot run now. */
    public static boolean startBenchmark() {
        return Benchmark.start(false, null);
    }

    /** The benchmark comparing one optimization alone (off against on, every other one on). */
    public static boolean startBenchmark(Optimization only) {
        return Benchmark.start(false, only);
    }

    /** Only the benchmark's frame comparison (off, off again, on), without the timed passes. */
    public static boolean startFrameComparison() {
        return Benchmark.start(true, null);
    }

    /** The last benchmark's summary lines (already translated), or an empty list. */
    public static List<String> lastResult() {
        return Benchmark.lastSummary();
    }

    /** Where the benchmark reports are written. */
    public static java.nio.file.Path reportFolder() {
        return Benchmark.folder();
    }

    /** F3 lines: frame and GPU time, then the most expensive spans. */
    private static List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(String.format(Locale.ROOT, "[BlockLand perf] frame %.2f ms, GPU %s", FrameTimings.smoothFrameMillis(),
                FrameTimings.gpuSupported() ? String.format(Locale.ROOT, "%.2f ms", FrameTimings.smoothGpuFrameMillis()) : "n/a"));
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < FrameTimings.keyCount(); i++) keys.add(i);
        keys.sort(Comparator.comparingDouble(k -> -Math.max(FrameTimings.smoothCpu(k), FrameTimings.smoothGpu(k))));
        for (int i = 0; i < Math.min(12, keys.size()); i++) {
            int k = keys.get(i);
            lines.add(String.format(Locale.ROOT, "%s  %.2f / %.2f", FrameTimings.name(k), FrameTimings.smoothCpu(k), FrameTimings.smoothGpu(k)));
        }
        lines.add("(CPU / GPU ms)");
        return lines;
    }
}
