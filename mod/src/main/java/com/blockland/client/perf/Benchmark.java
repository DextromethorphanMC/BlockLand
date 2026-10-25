package com.blockland.client.perf;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.perf.terrain.MeshCheck;
import com.blockland.client.render.RenderClock;
import com.blockland.client.ui.Tr;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The benchmark (rendering spec §20), run from the 性能 tab where the player stands. It never moves the player, only
 * turns the camera through fixed views, and does three things with every {@link Optimization} off and on:
 * <ol>
 *   <li>frame times: passes over the views in the order off, on, on, off, off, on (so warming up or a hot laptop
 *   counts against both), each with every {@link FrameTimings} span, vanilla's sections included;</li>
 *   <li>section rebuilds: every section around the camera meshed again (off, on, on, off), timed until all are drawn;
 *   then {@link MeshCheck}: the nearest sections meshed off and on side by side and compared byte for byte;</li>
 *   <li>frame comparison: the client's ticks are held, no game time passes and the {@link RenderClock} steps by a fixed
 *   1/60 s, the pipeline starts over ({@link Graphics#restartTemporalState}) and the same frames are rendered three
 *   times — off, off again (the control: the scene is still), on — and the last frame of each is compared pixel for
 *   pixel. The world is frozen by {@code /tick freeze} when the player may do that.</li>
 * </ol>
 * Opening any screen, leaving the world or dying stops it; everything it changed is put back.
 */
final class Benchmark {
    /** Timing views: yaw offsets from where the player looked, and absolute pitches. */
    private static final float[] PATH_YAWS = {0, 45, 90, 135, 180, 225, 270, 315};
    private static final float[] PATH_PITCHES = {0, 30};
    /** Frame comparison views. */
    private static final float[] COMPARE_YAWS = {0, 90, 180, 270};
    private static final float COMPARE_PITCH = 12;
    private static final int HOLD_FRAMES = 30, SETTLE_FRAMES = 60;
    /** Frames rendered from a restarted pipeline before the compared frame (temporal passes fill their history). */
    private static final int COMPARE_FRAMES = 24;
    /** Before a capture: frames in a row with every section in view found and built (at most {@code SETTLE_MAX_FRAMES}). */
    private static final int SETTLE_BUILT_FRAMES = 8, SETTLE_MAX_FRAMES = 240;
    private static final long STEP_NANOS = 16_666_667L;
    private static final long DRAIN_TIMEOUT_MS = 60_000;
    private static final boolean[] TIMING_ORDER = {false, true, true, false, false, true};
    /** Moving passes: the camera circles this far around where the player stood, one round per pass, at the same height. */
    private static final double CIRCLE_RADIUS = 3;
    private static final boolean[] REBUILD_ORDER = {false, true, true, false};
    private static final int MESH_CHECK_SECTIONS = 96;

    @Nullable
    private static Run run;
    private static List<String> lastSummary = List.of();

    private Benchmark() {
    }

    static void init(BusGroup modBus) {
        // right after the pipeline's composite and before any HUD element is drawn: the frame comparison's capture
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(BlockLand.id("perf_capture"), BlockLand.id("graphics_composite"), (g, delta) -> captureLayer()));
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            if (run != null) abort("blockland.perf.abort.left");
        });
    }

    static boolean running() {
        return run != null;
    }

    static List<String> lastSummary() {
        return lastSummary;
    }

    static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("blockland").resolve("benchmarks");
    }

    /**
     * @param only with an optimization the run compares it alone (off and on, every other one on), null for all of them
     */
    static boolean start(boolean compareOnly, @Nullable Optimization only) {
        Minecraft mc = Minecraft.getInstance();
        if (run != null || mc.level == null || mc.player == null) return false;
        mc.setScreen(null);
        run = new Run(mc, compareOnly, only);
        return true;
    }

    // ---- per frame ----------------------------------------------------------------------------------------------------

    static void beforeFrame() {
        Run r = run;
        if (r == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != r.level || mc.player == null || !mc.player.isAlive()) {
            abort("blockland.perf.abort.left");
            return;
        }
        if (mc.screen != null) {
            BlockLand.LOGGER.info("[BlockLand] Benchmark stopped: {} opened", mc.screen.getClass().getName());
            abort("blockland.perf.abort.screen");
            return;
        }
        KeyMapping.releaseAll();
        r.frame++;
        r.baselineFree = Math.max(r.baselineFree, dispatcher(mc).getFreeBufferCount());
        try {
            while (!r.steps.isEmpty() && r.steps.peekFirst().frame(r)) r.steps.pollFirst();
        } catch (RuntimeException e) {
            BlockLand.LOGGER.error("[BlockLand] Benchmark failed", e);
            abort("blockland.perf.abort.error");
            return;
        }
        if (run == null) return;
        r.aim(mc.player);
        if (r.steps.isEmpty()) finish();
    }

    /** The HUD's first layer after the composite: the frame is final and nothing of the HUD is drawn yet. */
    private static void captureLayer() {
        Run r = run;
        if (r == null || r.captureInto == null) return;
        Capture into = r.captureInto;
        r.captureInto = null;
        Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(), image -> into.image = image);
    }

    // ---- the run ------------------------------------------------------------------------------------------------------

    private interface Step {
        /** Called once per frame before it renders; true when done (the next step starts in the same frame). */
        boolean frame(Run run);
    }

    /** A frame-comparison capture, filled when its read-back arrives. */
    private static final class Capture {
        @Nullable
        volatile NativeImage image;
    }

    private static final class Run {
        final ClientLevel level;
        final BenchmarkReport report = new BenchmarkReport();
        final Deque<Step> steps = new ArrayDeque<>();
        final float startYaw, startPitch;
        final int savedFramerate;
        final boolean savedVsync, savedDebug;
        /** The pinned clock's first frame, the same for every capture (set when the comparison starts). */
        long clockOrigin;
        /** Names the report and the saved comparison images. */
        final String stamp = stamp();
        boolean froze;
        long frame;
        /** Moving passes: where the player stood and whether it was flying. */
        boolean circling, wasFlying;
        double standX, standY, standZ;
        int baselineFree;
        float yaw, pitch;
        @Nullable
        Capture captureInto;
        /** Frame comparison: captures per view, off / off again / on. */
        final Capture[][] captures = new Capture[COMPARE_YAWS.length][3];

        /** The optimization compared alone, or null (all of them). */
        @Nullable
        final Optimization only;

        Run(Minecraft mc, boolean compareOnly, @Nullable Optimization only) {
            this.only = only;
            report.only = only == null ? null : only.id;
            level = mc.level;
            LocalPlayer player = mc.player;
            startYaw = player.getYRot();
            startPitch = player.getXRot();
            yaw = startYaw;
            pitch = startPitch;
            savedFramerate = mc.options.framerateLimit().get();
            savedVsync = mc.options.enableVsync().get();
            savedDebug = mc.getDebugOverlay().showDebugScreen();
            // as fast as it goes: the frame time is the work, not a cap
            mc.options.framerateLimit().set(260);
            mc.options.enableVsync().set(false);
            if (savedDebug) mc.getDebugOverlay().toggleOverlay();
            if (player.hasPermissions(3) && !level.tickRateManager().isFrozen()) {
                player.connection.sendCommand("tick freeze");
                froze = true;
            }
            message(Tr.tr("blockland.perf.started"));
            plan(compareOnly);
        }

        /** The whole benchmark, or only its frame comparison (a quick check of the exactness alone). */
        private void plan(boolean compareOnly) {
            steps.add(wait(20));
            steps.add(drain(null));
            if (compareOnly) {
                steps.add(compare());
                return;
            }
            // warm up (JIT, caches, the sections around): one pass, not counted
            steps.add(mode(true, false));
            steps.add(pass(true, false, false));
            for (boolean on : TIMING_ORDER) {
                steps.add(mode(on, false));
                steps.add(pass(on, true, false));
            }
            // the same with the camera moving (everything that waits for a still camera is redone every frame)
            for (boolean on : TIMING_ORDER) {
                steps.add(mode(on, false));
                steps.add(pass(on, true, true));
            }
            steps.add(r -> {
                r.standBack();
                return true;
            });
            for (boolean on : REBUILD_ORDER) steps.add(rebuild(on));
            // the sections around meshed off and on side by side, compared byte for byte (and timed)
            steps.add(r -> {
                progress("mesh check");
                r.report.mesh = MeshCheck.run(MESH_CHECK_SECTIONS);
                return true;
            });
            steps.add(compare());
        }

        void aim(LocalPlayer player) {
            player.setYRot(yaw);
            player.yRotO = yaw;
            player.setXRot(pitch);
            player.xRotO = pitch;
        }

        /** Moving passes: the player (and so the camera) at {@code turn} (0..1) of a round about where it stood. */
        void circle(double turn) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;
            if (!circling) {
                circling = true;
                wasFlying = player.getAbilities().flying;
                standX = player.getX();
                standY = player.getY();
                standZ = player.getZ();
            }
            player.getAbilities().flying = true;
            double angle = turn * Math.PI * 2;
            double x = standX + CIRCLE_RADIUS * (Math.cos(angle) - 1), z = standZ + CIRCLE_RADIUS * Math.sin(angle);
            player.setDeltaMovement(0, 0, 0);
            player.setPos(x, standY, z);
            player.xo = player.xOld = x;
            player.yo = player.yOld = standY;
            player.zo = player.zOld = z;
        }

        /** After the moving passes: the player back where it stood, flying as before. */
        void standBack() {
            LocalPlayer player = Minecraft.getInstance().player;
            if (!circling || player == null) return;
            circling = false;
            player.setDeltaMovement(0, 0, 0);
            player.setPos(standX, standY, standZ);
            player.xo = player.xOld = standX;
            player.yo = player.yOld = standY;
            player.zo = player.zOld = standZ;
            player.getAbilities().flying = wasFlying;
        }

        void look(float yawOffset, float absolutePitch) {
            yaw = startYaw + yawOffset;
            pitch = absolutePitch;
        }

        // ---- steps ----

        Step wait(int frames) {
            long[] left = {frames};
            return r -> --left[0] <= 0;
        }

        /** Until every section is meshed and uploaded; {@code done} gets the milliseconds (or -1 on a timeout). */
        Step drain(@Nullable java.util.function.LongConsumer done) {
            long[] state = {0, 0, 0, -1};   // start ms, frames waited, frames drained in a row, visible sections then
            int[] visible = {0};
            return r -> {
                Minecraft mc = Minecraft.getInstance();
                if (state[0] == 0) state[0] = Util.getMillis();
                state[1]++;
                SectionRenderDispatcher d = dispatcher(mc);
                boolean idle = PendingSections.visibleBuilt(mc.levelRenderer, visible) && PendingSections.count(d) == 0
                        && d.getToUpload() == 0 && d.getFreeBufferCount() >= r.baselineFree;
                // and the same sections in view for those frames (the graph does not grow any more)
                state[2] = idle && visible[0] == state[3] ? state[2] + 1 : 0;
                state[3] = visible[0];
                long elapsed = Util.getMillis() - state[0];
                if (state[1] >= 10 && state[2] >= 5) {
                    // includes the five idle frames that confirm it (the same few milliseconds for off and on)
                    if (done != null) done.accept(Math.max(0, elapsed));
                    return true;
                }
                if (elapsed > DRAIN_TIMEOUT_MS) {
                    progress("sections not drained after " + elapsed + " ms (pending " + PendingSections.count(d) + ", uploads "
                            + d.getToUpload() + ", free buffers " + d.getFreeBufferCount() + "/" + r.baselineFree + ", drawn "
                            + mc.levelRenderer.countRenderedSections() + ", visible " + visible[0] + ")");
                    if (done != null) done.accept(-1);
                    return true;
                }
                return false;
            };
        }

        /** Every optimization off or on; sections rebuilt (and waited for) when that changes their meshing, or always. */
        Step mode(boolean on, boolean alwaysRebuild) {
            Step[] drain = {null};
            return r -> {
                if (drain[0] == null) {
                    boolean rebuild = Perf.setMode(on, r.only) | alwaysRebuild;
                    if (!rebuild) return true;
                    Perf.rebuildSections();
                    drain[0] = drain(null);
                }
                return drain[0].frame(r);
            };
        }

        /**
         * One timed pass over the views ({@code record}: counted in the report); {@code moving}: the camera also circles
         * around the spot (the player moved with it, flying, without its own motion).
         */
        Step pass(boolean on, boolean record, boolean moving) {
            int[] state = {0};   // frames into the pass
            int views = PATH_YAWS.length * PATH_PITCHES.length;
            int total = SETTLE_FRAMES + views * HOLD_FRAMES + FrameTimings.RING + 1;
            return r -> {
                int f = state[0]++;
                if (moving) r.circle((double) f / total);
                if (f == 0) {
                    progress("pass " + (on ? "on" : "off") + (moving ? " moving" : "") + (record ? "" : " (warm-up)"));
                    // the garbage of the last switch (sections rebuilt) collected now, not in the middle of the pass
                    System.gc();
                }
                int view = f < SETTLE_FRAMES ? 0 : Math.min(views - 1, (f - SETTLE_FRAMES) / HOLD_FRAMES);
                r.look(PATH_YAWS[view % PATH_YAWS.length], PATH_PITCHES[view / PATH_YAWS.length]);
                if (f == SETTLE_FRAMES) FrameTimings.window().reset();
                if (f + 1 >= total) {
                    if (record) r.report.addPass(on, moving, FrameTimings.window());
                    return true;
                }
                return false;
            };
        }

        /** Every section rebuilt with every optimization off or on, timed until all are drawn again. */
        Step rebuild(boolean on) {
            Step[] inner = {null};
            return r -> {
                if (inner[0] == null) {
                    progress("rebuild " + (on ? "on" : "off"));
                    r.look(0, PATH_PITCHES[0]);
                    Perf.setMode(on, r.only);
                    Perf.rebuildSections();
                    inner[0] = drain(ms -> r.report.rebuilds.add(new BenchmarkReport.Rebuild(on, ms,
                            Minecraft.getInstance().levelRenderer.countRenderedSections(), ms < 0)));
                }
                return inner[0].frame(r);
            };
        }

        /** The frame comparison: holds the world, captures off / off / on for every view, compares, lets go. */
        Step compare() {
            Deque<Step> plan = new ArrayDeque<>();
            plan.add(r -> {
                PerfHooks.holdTicks(true);
                r.clockOrigin = System.nanoTime();
                r.report.worldFrozen = r.level.tickRateManager().isFrozen();
                return true;
            });
            for (int run = 0; run < 3; run++) {
                boolean on = run == 2;
                // always rebuilt, so the control also proves that meshing the same sections twice gives the same frame
                plan.add(mode(on, true));
                for (int view = 0; view < COMPARE_YAWS.length; view++) plan.add(capture(view, run));
            }
            plan.add(r -> {
                RenderClock.release();
                PerfHooks.holdTicks(false);
                r.compareAll();
                return true;
            });
            return r -> {
                while (!plan.isEmpty() && plan.peekFirst().frame(r)) plan.pollFirst();
                return plan.isEmpty();
            };
        }

        /**
         * One view's capture: the camera turns to it and waits until the sections in view are found and built (after a
         * turn vanilla's culling takes a few frames to add what came into view — a first frame without them would stay
         * in the temporal history), then the pipeline starts over and the frames are rendered on the pinned clock.
         */
        Step capture(int view, int slot) {
            int[] state = {0, 0, -1, 0};   // frames rendered since the pipeline restarted (-1 while settling), settle frames,
                                            // visible sections last frame, settled frames in a row
            int[] visible = {0};
            Capture capture = new Capture();
            captures[view][slot] = capture;
            return r -> {
                r.look(COMPARE_YAWS[view], COMPARE_PITCH);
                if (state[0] == 0 && state[1] == 0) progress("capture view " + view + " run " + slot);
                if (state[2] != -2) {
                    RenderClock.pin(r.clockOrigin);
                    state[1]++;
                    boolean built = PendingSections.visibleBuilt(Minecraft.getInstance().levelRenderer, visible);
                    state[3] = built && visible[0] == state[2] ? state[3] + 1 : 0;
                    state[2] = visible[0];
                    if (state[3] < SETTLE_BUILT_FRAMES && state[1] < SETTLE_MAX_FRAMES) return false;
                    state[2] = -2;
                }
                int f = state[0]++;
                if (f < COMPARE_FRAMES) {
                    if (f == 0) Graphics.restartTemporalState();
                    RenderClock.pin(r.clockOrigin + f * STEP_NANOS);
                    if (f == COMPARE_FRAMES - 1) r.captureInto = capture;
                    return false;
                }
                // the read-back arrives a few frames later; the clock stays on the compared frame meanwhile
                return capture.image != null || f > COMPARE_FRAMES + 120;
            };
        }

        void compareAll() {
            for (int view = 0; view < COMPARE_YAWS.length; view++) {
                NativeImage off = captures[view][0].image, again = captures[view][1].image, on = captures[view][2].image;
                BenchmarkReport.Diff control = diff(off, again, null, stamp, view);
                BenchmarkReport.Diff result = diff(off, on, again, stamp, view);
                report.comparisons.add(new BenchmarkReport.Comparison(view, COMPARE_YAWS[view], COMPARE_PITCH, control, result));
            }
            for (Capture[] row : captures) {
                for (Capture c : row) {
                    if (c != null && c.image != null) {
                        c.image.close();
                        c.image = null;
                    }
                }
            }
        }

        /**
         * Counts differing pixels (RGB) of {@code a} and {@code b}; when they differ the result's capture and a map of the
         * differences (red where they differ, dimmed elsewhere) are saved next to the report. {@code control} only marks
         * which comparison this is (null: the control itself, saved as {@code _control}).
         */
        BenchmarkReport.Diff diff(@Nullable NativeImage a, @Nullable NativeImage b, @Nullable NativeImage control, String stamp, int view) {
            if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return BenchmarkReport.Diff.absent();
            int w = a.getWidth(), h = a.getHeight();
            long differing = 0;
            int maxDelta = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pa = a.getPixel(x, y), pb = b.getPixel(x, y);
                    if ((pa & 0xFFFFFF) == (pb & 0xFFFFFF)) continue;
                    differing++;
                    for (int s = 0; s < 24; s += 8) maxDelta = Math.max(maxDelta, Math.abs(((pa >> s) & 0xFF) - ((pb >> s) & 0xFF)));
                }
            }
            String imageName = null;
            if (differing > 0) {
                imageName = stamp + "_view" + view + (control == null ? "_control" : "_result");
                NativeImage map = new NativeImage(w, h, false);
                NativeImage copy = new NativeImage(w, h, false);
                // the control's first frame too: both sides of a scene that did not hold still
                NativeImage first = control == null ? new NativeImage(w, h, false) : null;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int pa = a.getPixel(x, y), pb = b.getPixel(x, y);
                        copy.setPixel(x, y, pb | 0xFF000000);
                        if (first != null) first.setPixel(x, y, pa | 0xFF000000);
                        map.setPixel(x, y, (pa & 0xFFFFFF) == (pb & 0xFFFFFF) ? 0xFF000000 | ((pa >> 2) & 0x3F3F3F) : 0xFFFF2020);
                    }
                }
                Path folder = folder();
                String name = imageName;
                Util.ioPool().execute(() -> {
                    try {
                        java.nio.file.Files.createDirectories(folder);
                        map.writeToFile(folder.resolve(name + "_diff.png"));
                        copy.writeToFile(folder.resolve(name + ".png"));
                        if (first != null) first.writeToFile(folder.resolve(name + "_first.png"));
                    } catch (Exception e) {
                        BlockLand.LOGGER.warn("[BlockLand] Could not save the benchmark's frame comparison", e);
                    } finally {
                        map.close();
                        copy.close();
                        if (first != null) first.close();
                    }
                });
            }
            return new BenchmarkReport.Diff(false, w, h, differing, maxDelta, imageName);
        }
    }

    // ---- end ----------------------------------------------------------------------------------------------------------

    private static void finish() {
        Run r = run;
        if (r == null) return;
        restore(r);
        report(r);
    }

    static void abort(String reasonKey) {
        Run r = run;
        if (r == null) return;
        r.report.aborted = reasonKey;
        restore(r);
        for (Capture[] row : r.captures) {
            for (Capture c : row) {
                if (c != null && c.image != null) c.image.close();
            }
        }
        report(r);
    }

    private static void restore(Run r) {
        run = null;
        Minecraft mc = Minecraft.getInstance();
        RenderClock.release();
        PerfHooks.holdTicks(false);
        mc.options.framerateLimit().set(r.savedFramerate);
        mc.options.enableVsync().set(r.savedVsync);
        if (r.savedDebug != mc.getDebugOverlay().showDebugScreen()) mc.getDebugOverlay().toggleOverlay();
        // the player's own choices again (sections rebuilt if their meshing differs from the last pass)
        Perf.applySettings();
        if (mc.player != null) {
            r.standBack();
            r.look(0, r.startPitch);
            r.aim(mc.player);
            if (r.froze) mc.player.connection.sendCommand("tick unfreeze");
        }
    }

    private static void report(Run r) {
        Path file = null;
        try {
            file = r.report.write(folder(), r.stamp);
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not write the benchmark report", e);
        }
        lastSummary = r.report.summary(file);
        for (String line : lastSummary) message(line);
    }

    private static void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.getChat().addMessage(Component.literal(text));
    }

    /** Where the run is (the log, for whoever watches a scripted run). */
    private static void progress(String what) {
        BlockLand.LOGGER.info("[BlockLand] Benchmark: {}", what);
    }

    private static SectionRenderDispatcher dispatcher(Minecraft mc) {
        return mc.levelRenderer.getSectionRenderDispatcher();
    }

    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }
}
