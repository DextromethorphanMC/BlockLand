package com.blockland.client.perf;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.ui.Tr;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The benchmark's numbers (rendering spec §20): frame-time passes with every {@link Optimization} off and on, section
 * rebuild times, and the frame comparison; written as a readable report in the player's language ({@code .txt}) and as
 * data ({@code .json}), and summed up in a few lines for the chat and the 性能 tab.
 */
final class BenchmarkReport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** One pass over the camera path: frame times and the spans, per frame. */
    record Pass(boolean optimized, boolean moving, int frames, double meanMs, double p50Ms, double p95Ms, double p99Ms, double low1Fps,
                int gpuFrames, double gpuMeanMs, Map<String, double[]> spans, long[] intervals) {
        double fps() {
            return meanMs > 0 ? 1000 / meanMs : 0;
        }

        static Pass of(boolean optimized, boolean moving, FrameTimings.Window w) {
            List<Long> intervals = new ArrayList<>(w.intervals());
            Collections.sort(intervals);
            int n = intervals.size();
            double sum = 0;
            for (long v : intervals) sum += v;
            double mean = n == 0 ? 0 : sum / n / 1e6;
            // 1% low: the average frame rate of the slowest 1% of frames
            int worst = Math.max(1, n / 100);
            double worstSum = 0;
            for (int i = n - worst; i < n && i >= 0; i++) worstSum += intervals.get(i);
            double low1 = n == 0 || worstSum == 0 ? 0 : 1e9 / (worstSum / worst);
            double gpuSum = 0;
            for (long v : w.gpuFrameTimes()) gpuSum += v;
            int g = w.gpuFrameTimes().size();
            Map<String, double[]> spans = new TreeMap<>();
            long frames = Math.max(1, w.frames()), gpuFrames = Math.max(1, w.gpuFrames());
            for (Map.Entry<Integer, long[]> e : w.keys().entrySet()) {
                spans.put(FrameTimings.name(e.getKey()), new double[]{e.getValue()[0] / 1e6 / frames, e.getValue()[1] / 1e6 / gpuFrames});
            }
            return new Pass(optimized, moving, n, mean, pct(intervals, 0.5), pct(intervals, 0.95), pct(intervals, 0.99), low1,
                    g, g == 0 ? 0 : gpuSum / g / 1e6, spans, w.intervals().stream().mapToLong(Long::longValue).toArray());
        }

        private static double pct(List<Long> sorted, double q) {
            if (sorted.isEmpty()) return 0;
            return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * (sorted.size() - 1) + 0.5))) / 1e6;
        }
    }

    /** One full rebuild of the sections around the camera. */
    record Rebuild(boolean optimized, double millis, int sections, boolean timedOut) {
    }

    /** One view of the frame comparison: the control (off against off) and the result (off against on). */
    record Comparison(int view, float yaw, float pitch, Diff control, Diff result) {
    }

    /** Pixels that differ between two captures; {@code missing} when a capture failed. */
    record Diff(boolean missing, int width, int height, long differing, int maxDelta, String image) {
        boolean identical() {
            return !missing && differing == 0;
        }

        static Diff absent() {
            return new Diff(true, 0, 0, 0, 0, null);
        }
    }

    final List<Pass> passes = new ArrayList<>();
    /**
     * Every frame interval of the recorded passes, off and on, still and moving (the 1% low is taken over all of a mode's
     * frames): index = optimized + 2 × moving.
     */
    @SuppressWarnings("unchecked")
    private final List<Long>[] frames = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};

    void addPass(boolean optimized, boolean moving, FrameTimings.Window window) {
        passes.add(Pass.of(optimized, moving, window));
        frames[(optimized ? 1 : 0) + (moving ? 2 : 0)].addAll(window.intervals());
    }

    /** The average frame rate of the slowest 1% of all of a mode's frames. */
    private double low1(boolean optimized, boolean moving) {
        List<Long> frames = new ArrayList<>(this.frames[(optimized ? 1 : 0) + (moving ? 2 : 0)]);
        if (frames.isEmpty()) return 0;
        Collections.sort(frames);
        int worst = Math.max(1, frames.size() / 100);
        double sum = 0;
        for (int i = frames.size() - worst; i < frames.size(); i++) sum += frames.get(i);
        return 1e9 / (sum / worst);
    }
    final List<Rebuild> rebuilds = new ArrayList<>();
    final List<Comparison> comparisons = new ArrayList<>();
    String comparisonSkipped;
    /** The optimization compared alone (every other one on), or null when all of them were switched. */
    @javax.annotation.Nullable
    String only;
    /** The section meshes compared off against on, or null when it did not run. */
    @javax.annotation.Nullable
    com.blockland.client.perf.terrain.MeshCheck.Result mesh;
    boolean worldFrozen;
    String aborted;
    final long started = System.currentTimeMillis();

    // ---- summary ------------------------------------------------------------------------------------------------------

    private record Mean(double value, double spread) {
    }

    private Mean mean(boolean optimized, boolean moving, java.util.function.ToDoubleFunction<Pass> f) {
        List<Double> values = new ArrayList<>();
        for (Pass p : passes) if (p.optimized == optimized && p.moving == moving && p.frames > 0) values.add(f.applyAsDouble(p));
        return meanOf(values);
    }

    private static Mean meanOf(List<Double> values) {
        if (values.isEmpty()) return new Mean(0, 0);
        double sum = 0;
        for (double v : values) sum += v;
        double mean = sum / values.size();
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        return new Mean(mean, values.size() > 1 ? Math.sqrt(var / (values.size() - 1)) : 0);
    }

    private Mean rebuildMean(boolean optimized) {
        List<Double> values = new ArrayList<>();
        for (Rebuild r : rebuilds) if (r.optimized == optimized && !r.timedOut) values.add(r.millis);
        return meanOf(values);
    }

    /** Lines for the chat and the 性能 tab. */
    List<String> summary(Path file) {
        List<String> lines = new ArrayList<>();
        if (aborted != null) {
            lines.add(Tr.tr("blockland.perf.summary.aborted", Tr.tr(aborted)));
            return lines;
        }
        for (boolean moving : new boolean[]{false, true}) {
            Mean offMs = mean(false, moving, Pass::meanMs), onMs = mean(true, moving, Pass::meanMs);
            if (offMs.value == 0 && onMs.value == 0) continue;
            Mean offFps = mean(false, moving, Pass::fps), onFps = mean(true, moving, Pass::fps);
            String where = Tr.tr(moving ? "blockland.perf.summary.moving" : "blockland.perf.summary.still");
            lines.add(where + Tr.tr("blockland.perf.summary.frame", f2(offMs.value), f2(onMs.value), percent(offMs.value, onMs.value),
                    f1(offFps.value), f1(onFps.value)));
            lines.add(where + Tr.tr("blockland.perf.summary.low", f1(low1(false, moving)), f1(low1(true, moving))));
        }
        Mean offRebuild = rebuildMean(false), onRebuild = rebuildMean(true);
        if (offRebuild.value > 0) {
            lines.add(Tr.tr("blockland.perf.summary.rebuild", f0(offRebuild.value), f0(onRebuild.value), percent(offRebuild.value, onRebuild.value)));
        }
        if (mesh != null && mesh.sectionsChecked() > 0) {
            lines.add(Tr.tr(mesh.sectionsDiffering() == 0 ? "blockland.perf.summary.mesh_identical" : "blockland.perf.summary.mesh_differs",
                    mesh.sectionsChecked() - mesh.sectionsDiffering(), mesh.sectionsDiffering(), f1(mesh.offMillis()), f1(mesh.onMillis()),
                    percent(mesh.offMillis(), mesh.onMillis())));
        }
        lines.add(comparisonLine());
        if (file != null) lines.add(Tr.tr("blockland.perf.summary.file", file.getFileName().toString()));
        return lines;
    }

    private String comparisonLine() {
        if (comparisonSkipped != null) return Tr.tr("blockland.perf.summary.compare_skipped", Tr.tr(comparisonSkipped));
        int identical = 0, differ = 0, unstable = 0;
        for (Comparison c : comparisons) {
            if (!c.control.identical()) unstable++;
            else if (c.result.identical()) identical++;
            else differ++;
        }
        if (differ > 0) return Tr.tr("blockland.perf.summary.compare_differs", differ, comparisons.size());
        if (unstable > 0) return Tr.tr("blockland.perf.summary.compare_unstable", identical, unstable);
        return Tr.tr("blockland.perf.summary.compare_identical", identical);
    }

    // ---- files --------------------------------------------------------------------------------------------------------

    /** Writes {@code <name>.txt} and {@code <name>.json}; returns the text file. */
    Path write(Path folder, String name) throws java.io.IOException {
        Files.createDirectories(folder);
        Path txt = folder.resolve(name + ".txt");
        Files.writeString(txt, text(txt), StandardCharsets.UTF_8);
        Files.writeString(folder.resolve(name + ".json"), GSON.toJson(json()), StandardCharsets.UTF_8);
        return txt;
    }

    private String text(Path file) {
        StringBuilder b = new StringBuilder();
        b.append(Tr.tr("blockland.perf.report.title")).append('\n');
        b.append(Tr.tr("blockland.perf.report.environment", environment())).append('\n');
        if (only != null) b.append(Tr.tr("blockland.perf.report.only", only)).append('\n');
        b.append('\n');
        for (String line : summary(file)) b.append(line).append('\n');
        b.append('\n').append(Tr.tr("blockland.perf.report.passes")).append('\n');
        for (Pass p : passes) {
            b.append(String.format(Locale.ROOT, "  %-4s %-6s %5d  %7.3f  %7.3f  %7.3f  %7.3f  %7.1f  %7.1f%n",
                    p.optimized ? "ON" : "OFF", p.moving ? "MOVING" : "STILL", p.frames, p.meanMs, p.p50Ms, p.p95Ms, p.p99Ms, p.fps(), p.low1Fps));
        }
        b.append('\n').append(Tr.tr("blockland.perf.report.spans")).append('\n');
        Map<String, double[]> off = averageSpans(false), on = averageSpans(true);
        List<String> keys = new ArrayList<>(off.keySet());
        for (String k : on.keySet()) if (!off.containsKey(k)) keys.add(k);
        keys.sort((x, y) -> Double.compare(cost(off.get(y)), cost(off.get(x))));
        for (String k : keys) {
            double[] a = off.getOrDefault(k, new double[2]), c = on.getOrDefault(k, new double[2]);
            if (Math.max(cost(a), cost(c)) < 0.005) continue;
            b.append(String.format(Locale.ROOT, "  %-56s %8.3f %8.3f   %8.3f %8.3f%n", k, a[0], c[0], a[1], c[1]));
        }
        if (!rebuilds.isEmpty()) {
            b.append('\n').append(Tr.tr("blockland.perf.report.rebuilds")).append('\n');
            for (Rebuild r : rebuilds) {
                b.append(String.format(Locale.ROOT, "  %-4s %9.1f ms  %6d%s%n", r.optimized ? "ON" : "OFF", r.millis, r.sections,
                        r.timedOut ? "  (timeout)" : ""));
            }
        }
        if (mesh != null) {
            b.append('\n').append(Tr.tr("blockland.perf.report.mesh")).append('\n');
            b.append(String.format(Locale.ROOT, "  %d / %d   %.1f ms -> %.1f ms%n", mesh.sectionsChecked() - mesh.sectionsDiffering(),
                    mesh.sectionsChecked(), mesh.offMillis(), mesh.onMillis()));
            if (mesh.firstDifference() != null) b.append("  ").append(mesh.firstDifference()).append('\n');
        }
        b.append('\n').append(Tr.tr("blockland.perf.report.compare")).append('\n');
        if (comparisonSkipped != null) b.append("  ").append(Tr.tr(comparisonSkipped)).append('\n');
        if (!worldFrozen) b.append("  ").append(Tr.tr("blockland.perf.report.not_frozen")).append('\n');
        for (Comparison c : comparisons) {
            b.append(String.format(Locale.ROOT, "  #%d yaw %+.0f pitch %+.0f   %s   %s%n", c.view, c.yaw, c.pitch,
                    diffText("blockland.perf.report.control", c.control), diffText("blockland.perf.report.result", c.result)));
        }
        return b.toString();
    }

    private static double cost(double[] v) {
        return v == null ? 0 : Math.max(v[0], v[1]);
    }

    private static String diffText(String key, Diff d) {
        String state = d.missing ? Tr.tr("blockland.perf.report.missing")
                : d.differing == 0 ? Tr.tr("blockland.perf.report.identical")
                : Tr.tr("blockland.perf.report.differs", d.differing, d.maxDelta) + (d.image != null ? " (" + d.image + ")" : "");
        return Tr.tr(key) + ": " + state;
    }

    private Map<String, double[]> averageSpans(boolean optimized) {
        Map<String, double[]> sums = new HashMap<>();
        int n = 0;
        for (Pass p : passes) {
            if (p.optimized != optimized || p.frames == 0) continue;
            n++;
            for (Map.Entry<String, double[]> e : p.spans.entrySet()) {
                double[] s = sums.computeIfAbsent(e.getKey(), k -> new double[2]);
                s[0] += e.getValue()[0];
                s[1] += e.getValue()[1];
            }
        }
        if (n > 0) for (double[] s : sums.values()) {
            s[0] /= n;
            s[1] /= n;
        }
        return sums;
    }

    private static String environment() {
        Minecraft mc = Minecraft.getInstance();
        var status = Graphics.status();
        return String.format(Locale.ROOT, "%s | %dx%d -> %dx%d | %s | RD %d", RenderSystem.getDevice().getRenderer(),
                status.internalWidth, status.internalHeight, mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                status.activeAa, mc.options.getEffectiveRenderDistance());
    }

    private JsonObject json() {
        JsonObject root = new JsonObject();
        root.addProperty("started", started);
        root.addProperty("environment", environment());
        root.add("graphics", GSON.toJsonTree(Graphics.settings()));
        root.addProperty("worldFrozen", worldFrozen);
        if (only != null) root.addProperty("only", only);
        if (aborted != null) root.addProperty("aborted", aborted);
        JsonObject opts = new JsonObject();
        for (Optimization o : Optimization.values()) opts.addProperty(o.id, o.group.name());
        root.add("optimizations", opts);
        JsonArray ps = new JsonArray();
        for (Pass p : passes) {
            JsonObject o = new JsonObject();
            o.addProperty("optimized", p.optimized);
            o.addProperty("moving", p.moving);
            o.addProperty("frames", p.frames);
            o.addProperty("meanMs", p.meanMs);
            o.addProperty("p50Ms", p.p50Ms);
            o.addProperty("p95Ms", p.p95Ms);
            o.addProperty("p99Ms", p.p99Ms);
            o.addProperty("fps", p.fps());
            o.addProperty("low1Fps", p.low1Fps);
            o.addProperty("gpuFrames", p.gpuFrames);
            o.addProperty("gpuMeanMs", p.gpuMeanMs);
            JsonObject spans = new JsonObject();
            for (Map.Entry<String, double[]> e : p.spans.entrySet()) {
                JsonArray v = new JsonArray();
                v.add(e.getValue()[0]);
                v.add(e.getValue()[1]);
                spans.add(e.getKey(), v);
            }
            o.add("spans", spans);
            // every frame of the pass in order (microseconds): where the slow ones fall
            JsonArray frameTimes = new JsonArray();
            for (long interval : p.intervals) frameTimes.add(interval / 1000);
            o.add("frameMicros", frameTimes);
            ps.add(o);
        }
        root.add("passes", ps);
        root.add("rebuilds", GSON.toJsonTree(rebuilds));
        if (mesh != null) root.add("meshCheck", GSON.toJsonTree(mesh));
        Map<String, Object> cmp = new LinkedHashMap<>();
        cmp.put("skipped", comparisonSkipped);
        cmp.put("views", comparisons);
        root.add("comparison", GSON.toJsonTree(cmp));
        return root;
    }

    private static String percent(double before, double after) {
        if (before <= 0) return "—";
        return String.format(Locale.ROOT, "%+.1f%%", (after - before) / before * 100);
    }

    private static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
